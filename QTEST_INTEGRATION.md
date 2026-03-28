# qTest Integration Research

This document covers the design and implementation considerations for integrating
ReportForge with Tricentis qTest Manager — specifically:
- **Import from qTest**: pull test cycle and test run data from qTest into a ReportForge report
- **Push to qTest**: publish a completed ReportForge execution report back to qTest as a test cycle with results

---

## Table of Contents

1. [qTest API Overview](#1-qtest-api-overview)
2. [Authentication](#2-authentication)
3. [Rate Limits](#3-rate-limits)
4. [Data Model Mapping](#4-data-model-mapping)
5. [Field Mapping: Import Direction (qTest → ReportForge)](#5-field-mapping-import-direction)
6. [Field Mapping: Push Direction (ReportForge → qTest)](#6-field-mapping-push-direction)
7. [Status Mapping](#7-status-mapping)
8. [Import Flow — Step by Step](#8-import-flow)
9. [Push Flow — Step by Step](#9-push-flow)
10. [Evidence / Attachment Handling](#10-evidence--attachment-handling)
11. [Conflict Resolution Strategy](#11-conflict-resolution-strategy)
12. [Sync Model](#12-sync-model)
13. [Java HTTP Client Approach](#13-java-http-client-approach)
14. [Error Handling and Rate Limiting](#14-error-handling-and-rate-limiting)
15. [UI Entry Points](#15-ui-entry-points)
16. [Clarifying Questions](#16-clarifying-questions)
17. [Future Considerations](#17-future-considerations)

---

## 1. qTest API Overview

**Interactive API docs**: https://qtest.dev.tricentis.com/  
**Swagger YAML**: https://qtest-config.s3.amazonaws.com/api-docs/manager/api-manager-v3.0.yaml  
**Official docs**: https://docs.tricentis.com/qtest-saas/content/apis/overview/qtest_api_specification.htm

### Base URL Pattern

| Deployment   | Base URL pattern |
|--------------|------------------|
| SaaS         | `https://{company}.qtestnet.com` |
| On-Premises  | Customer-configured URL |

All REST endpoints live under `/api/v3/...` (or `/api/v3.1/...` for newer automation endpoints).

### Key Resources Used

| Resource       | Primary Endpoints |
|----------------|-------------------|
| Projects       | `GET /api/v3/projects` |
| Test Cycles    | `GET/POST /api/v3/projects/{id}/test-cycles` |
| Test Suites    | `GET/POST /api/v3/projects/{id}/test-suites` |
| Test Cases     | `GET/POST /api/v3/projects/{id}/test-cases` |
| Test Runs      | `GET/POST /api/v3/projects/{id}/test-runs` |
| Test Logs      | `GET/POST /api/v3/projects/{id}/test-runs/{id}/test-logs` |
| Attachments    | `GET/POST /api/v3/projects/{id}/attachments?type=test-logs` |

---

## 2. Authentication

qTest uses **Bearer token authentication** on every request via the `Authorization` HTTP header.

### Token Acquisition

**Option A — API Token (recommended for integrations):**
- Generated from the qTest UI: Project → API & SDK → Download token
- Never expires unless explicitly revoked
- Safe to store in application settings

**Option B — Username/Password Login:**
- `POST /api/v3/login` with `{"username": "...", "password": "..."}` (URL-encoded form body)
- Returns a bearer token valid for the current session
- Expires when the session ends or is revoked

### Recommended UX

Store the base URL and API token in an encrypted keystore in the ReportForge settings
(`settings/qtest-connection.json` in the project directory). Provide a "Test Connection"
button that calls `GET /api/v3/projects?pageSize=1` to validate the token before saving.

### Request Header

```
Authorization: Bearer {token}
Content-Type: application/json
Accept: application/json
```

---

## 3. Rate Limits

qTest SaaS enforces rate limiting. Applications should handle 429 responses gracefully.

| Header | Meaning |
|--------|---------|
| `x-ratelimit-limit-minute` | Max calls per rolling minute |
| `x-ratelimit-remaining-minute` | Remaining calls this minute |
| `x-ratelimit-limit-month` | Monthly quota |
| `x-ratelimit-remaining-month` | Remaining monthly quota |
| `retry-after` | Seconds to wait before retrying (in 429 response) |

**Implementation guidance:** Inspect `x-ratelimit-remaining-minute` on every response. If it
drops below a configurable threshold (e.g., 5), insert a short delay before the next call.
On a 429, wait `retry-after` seconds then retry with exponential backoff.

**Attachment uploads count as API calls** and tend to be rate-limited more aggressively.
Batch all text operations first, then upload attachments at the end.

---

## 4. Data Model Mapping

### Conceptual Hierarchy

**qTest hierarchy:**
```
Project
  └── Release (optional grouping)
        └── Test Cycle
              └── Test Suite
                    └── Test Run (links to a Test Case)
                          └── Test Log (execution result)
                                └── Attachment
```

**ReportForge hierarchy:**
```
Project
  └── Environment
        └── Report (title, status, fields)
              ├── ExecutionRun (test case result)
              │     └── ExecutionRunEvidence (image attachment)
              └── report_fields (free-text sections)
```

### Entity Mapping Table

| ReportForge Entity | qTest Entity | Notes |
|---------------------|--------------|-------|
| `ProjectSummary` | `Project` | 1:1 if names match; otherwise user selects target project |
| `ReportRecord` | `Test Cycle` | One report = one Test Cycle in qTest |
| `ExecutionRunRecord` | `Test Run` + `Test Log` | Test Run = planned instance; Test Log = execution result |
| `ExecutionRunEvidenceRecord` | `Attachment` on Test Log | Image files attached to the test log |
| `ApplicationEntry` | Custom field on Test Cycle | qTest Test Cycles support custom fields |
| `EnvironmentRecord` | Custom field on Test Cycle | Environment info stored in custom fields |
| `testCaseKey` + `testCaseName` | `Test Case` | Looked up by PID/name; created if missing |
| `testSteps` (free text) | `TestStep[]` on Test Case | Each line maps to one test step |
| `suiteName` | `Test Suite` name | Group execution runs under a test suite |
| `notes.content` | Test Cycle `description` | Report-level notes |
| `projectOverview.*` | Test Cycle description or custom fields | Narrative metadata |

---

## 5. Field Mapping: Import Direction

When importing a Test Cycle from qTest into a new ReportForge report:

### Report-Level Fields

| qTest Field | ReportForge `report_fields` Key | Transformation |
|-------------|----------------------------------|----------------|
| Test Cycle `name` | `ReportRecord.title` | Direct copy |
| Test Cycle `description` | `notes.content` | Direct copy |
| Test Cycle `start_date` | `projectOverview.preparedDate` | Date only (strip time) |
| Test Cycle custom field "Release/Sprint" | `projectOverview.releaseIterationName` | If field exists |
| Test Cycle custom field "Prepared By" | `projectOverview.preparedBy` | If field exists |

### Execution Run Fields (per Test Run + Test Log)

| qTest Field | ReportForge `ExecutionRunRecord` Field | Notes |
|-------------|----------------------------------------|-------|
| Test Case `pid` | `testCaseKey` | e.g., "TC-42" |
| Test Case `name` | `testCaseName` | Display name |
| Test Run `name` | `suiteName` | Suite/run label |
| Test Case `description` | `expectedResultSummary` | Maps to expected description |
| Test Log `status` | `status` | See status mapping table |
| Test Log `note` | `comments` | Tester comments |
| Test Log `exe_start_date` | `startDate` | ISO 8601 |
| Test Log `exe_end_date` | `endDate` | ISO 8601 |
| Derived from start/end | `durationText` | Format: "Xh Ym Zs" |
| Test Log `actualResult` | `actualResult` | Actual test outcome |
| Test Step logs → formatted text | `testSteps` | Steps joined as newline-delimited text |
| Test Log `attachments` | `ExecutionRunEvidenceRecord[]` | Downloaded images |
| Test Case `precondition` | prepend to `testSteps` | Optional — see Q3 |
| Test Case module path | `moduleName` + `sectionName` | Module hierarchy flattened |
| Test Run `properties` (custom fields) | `remarks` or `notes` | Catch-all for custom fields |

---

## 6. Field Mapping: Push Direction

When pushing a ReportForge report to qTest:

### Test Cycle Creation

| ReportForge Source | qTest Field | Notes |
|--------------------|-------------|-------|
| `ReportRecord.title` | Test Cycle `name` | Direct |
| `notes.content` | Test Cycle `description` | Direct |
| `projectOverview.releaseIterationName` | Test Cycle `description` suffix | Or custom field |
| `ReportRecord.createdAt` | Test Cycle `start_date` | Approximate |

### Test Case Lookup / Creation (per ExecutionRun)

For each ExecutionRunRecord:
1. Search qTest for a Test Case with matching PID (`testCaseKey`) using
   `POST /api/v3/projects/{id}/test-cases/search`
2. If found: use existing Test Case ID
3. If not found: create new Test Case with:
   - `name` = `testCaseName` (or `suiteName` if blank)
   - `description` = `expectedResultSummary`
   - `test_steps` = parsed from `testSteps` text (see §10)
   - Place under a module named `moduleName` or a default "ReportForge Imports" module

### Test Run Creation

Each ExecutionRunRecord becomes one Test Run under the Test Cycle:

| ReportForge Field | qTest Field | Notes |
|-------------------|-------------|-------|
| `testCaseName` / `suiteName` | Test Run `name` | Fallback chain as in `getDisplayLabel()` |
| (linked Test Case ID) | Test Run `test_case_version_id` | From lookup/create step |

### Test Log Submission

| ReportForge Field | qTest Field | Notes |
|-------------------|-------------|-------|
| `status` | Test Log `status` | See status mapping table |
| `startDate` or `executionDate` | `exe_start_date` | Parse ISO 8601 |
| `endDate` or derived | `exe_end_date` | Fallback: startDate + durationText |
| `comments` + `remarks` | `note` | Concatenated with separator |
| `actualResult` | `actual_result` on step log (or custom field) | Best-effort |
| `relatedIssue` | `note` suffix | Prepend "Related Issue: {value}" |
| `defectSummary` | `note` suffix | Prepend "Defect: {value}" |
| `blockedReason` | `note` suffix | Prepend "Blocked Reason: {value}" |
| `testSteps` lines | `test_step_logs[].status` = same as overall | Steps echoed as pass/fail |

### Attachments (per Test Log)

See [§10 Evidence / Attachment Handling](#10-evidence--attachment-handling).

---

## 7. Status Mapping

ReportForge stores status as a free-text string normalized to one of six values.
qTest uses named statuses configured per project. A configurable mapping table
should be stored in `settings/qtest-status-map.json`.

### Default Mapping

| ReportForge Status | qTest Status Name (default) | Notes |
|--------------------|-----------------------------|-------|
| `PASS` | `PASS` | Maps to green status |
| `FAIL` | `FAIL` | Maps to red status |
| `BLOCKED` | `BLOCKED` | Maps to orange/yellow |
| `NOT_RUN` | `UNEXECUTED` | qTest default for unrun tests |
| `DEFERRED` | `INCOMPLETE` | Closest qTest equivalent |
| `SKIPPED` | `SKIP` | If configured in project |

**Important:** qTest test log statuses are project-specific. The actual status names
are retrieved via `GET /api/v3/projects/{id}/test-runs/{id}/execution-statuses`. On
first connection, the app should fetch available statuses and let the user configure
the mapping. The mapping is then saved to `settings/qtest-status-map.json`.

### Import Direction

When importing, qTest statuses are reverse-mapped to ReportForge statuses using the
same configurable table. Unmapped qTest statuses default to `NOT_RUN`.

---

## 8. Import Flow

### Prerequisites

- User has configured the qTest base URL and API token (stored in settings)
- An active ReportForge project is open

### Step-by-Step Flow

```
1. User opens "Import from qTest" dialog
   │
2. App fetches available projects
   │   GET /api/v3/projects
   │
3. User selects a qTest project
   │
4. App fetches Test Cycles in the project
   │   GET /api/v3/projects/{projectId}/test-cycles
   │
5. User selects a Test Cycle
   │
6. App fetches Test Suites under the Test Cycle
   │   GET /api/v3/projects/{projectId}/test-suites?parentId={cycleId}&parentType=test-cycle
   │
7. For each Test Suite, fetch Test Runs
   │   GET /api/v3/projects/{projectId}/test-runs?parentId={suiteId}&parentType=test-suite
   │
8. For each Test Run:
   │   a. GET /api/v3/projects/{projectId}/test-runs/{testRunId}/test-logs/last-run
   │      (fetches latest execution result)
   │   b. GET /api/v3/projects/{projectId}/test-cases/{testCaseId}
   │      (fetches test case details including steps)
   │
9. For each Test Log with attachments:
   │   GET /api/v3/projects/{projectId}/attachments?type=test-logs&id={logId}
   │   Download binary content for each image attachment
   │
10. App creates a new ReportForge Report in the selected environment
    with all data mapped per §5
    │
11. Evidence images are written to the project's evidence/ directory
    and registered in the database
    │
12. Import complete — report opens in the workspace
```

### Progress Reporting

Steps 8–9 can involve many API calls for large cycles. Show a progress dialog:
- "Importing test runs (X / Y)"
- Run on a background JavaFX Task
- Allow cancellation (stop processing new runs; keep already-imported ones)

---

## 9. Push Flow

### Prerequisites

- A ReportForge report with at least one ExecutionRun exists
- User has configured qTest connection settings

### Step-by-Step Flow

```
1. User triggers "Push to qTest" action
   │
2. App shows "Push to qTest" dialog:
   │   - Select target qTest project (fetched from API)
   │   - Optional: select parent Release (or push to root)
   │   - Preview: number of execution runs to be pushed
   │
3. Validate: ensure all execution runs have at minimum a test case name or key
   │
4. App fetches existing modules to find or create "ReportForge Imports" module
   │   GET /api/v3/projects/{projectId}/modules
   │
5. Create Test Cycle in qTest
   │   POST /api/v3/projects/{projectId}/test-cycles
   │   Body: { name: report.title, description: notes.content, ... }
   │
6. Create a Test Suite under the new cycle
   │   POST /api/v3/projects/{projectId}/test-suites
   │   Body: { name: report.title + " Suite", ... }
   │   Query: parentId={cycleId}&parentType=test-cycle
   │
7. For each ExecutionRunRecord (in sort order):
   │
   │   a. Resolve Test Case
   │      - If testCaseKey matches a known PID:
   │          POST /api/v3/projects/{id}/test-cases/search with PID query
   │      - If not found: create new Test Case
   │          POST /api/v3/projects/{id}/test-cases?parentId={moduleId}
   │          with steps parsed from testSteps text
   │
   │   b. Create Test Run
   │      POST /api/v3/projects/{projectId}/test-runs
   │      Body: { name, test_case_version_id, ... }
   │      Query: parentId={suiteId}&parentType=test-suite
   │
   │   c. Submit Test Log
   │      POST /api/v3/projects/{projectId}/test-runs/{runId}/test-logs
   │      Body: { status, exe_start_date, exe_end_date, note, test_step_logs }
   │
   │   d. Upload Evidence (if any)
   │      See §10 — upload each image as attachment to the test log
   │
8. Store push result in report_fields:
   │   qtest.lastPushProjectId, qtest.lastPushCycleId, qtest.lastPushTimestamp
   │
9. Show summary dialog: "Pushed X / Y runs. View in qTest →" (deep link URL)
```

### Test Steps Parsing

`ExecutionRunRecord.testSteps` is a multi-line free-text string. To push to qTest:
- Split by `\n`, strip empty lines
- Each non-empty line becomes one `TestStepResource`
- `description` = the line text (HTML-encode it)
- `expected` = empty (or `expectedResultSummary` for the final step only)
- If `testSteps` is blank but `expectedResultSummary` is populated, create one synthetic
  step with description = "Execute test" and expected = `expectedResultSummary`

---

## 10. Evidence / Attachment Handling

### Import Direction (qTest → ReportForge)

qTest test log attachments are fetched and stored as evidence in the project:

1. For each `Attachment` on the test log, check `content_type`
2. Accept only image/* MIME types (skip PDFs, ZIPs, etc.)
3. Attachment size limit: 50 MB per attachment on qTest SaaS
4. Download binary content from attachment's `download_url` property
5. Run the downloaded bytes through `EvidenceMediaOptimizer` (existing compression pipeline)
6. Write to `evidence/{executionRunId}/{uuid}.{ext}` in the project directory
7. Register in `report_execution_run_evidence` table

### Push Direction (ReportForge → qTest)

Attachments are uploaded to qTest after the test log is created:

```
POST /api/v3/projects/{projectId}/attachments?type=test-logs
```

Request body is `multipart/form-data` with fields:
- `file` — the image binary
- `type` — `"test-logs"` (query param)
- Entity ID is specified in the URL path

**Size limit**: 50 MB per attachment (SaaS); configurable on-premises.

**Format considerations**:
- qTest accepts JPEG, PNG, WebP, GIF, PDF, and most common formats
- Evidence files in the project are already in optimized format (JPEG/WebP/PNG)
- No additional re-encoding needed for push unless the file exceeds the size limit
- If a file exceeds 50 MB, skip the attachment and log a warning (do not fail the run push)

**Ordering**: Upload attachments in the same `sortOrder` as `ExecutionRunEvidenceRecord`
to preserve gallery order; qTest does not guarantee attachment ordering so this is
best-effort.

---

## 11. Conflict Resolution Strategy

Conflicts arise when pushing to a qTest project that already contains data for the
same test cases or cycles.

### Test Cycle Conflict

- If a Test Cycle with an identical name already exists under the target Release/root,
  the app will **always create a new cycle** (append timestamp suffix if needed, e.g.,
  "Report Title — 2025-01-15T14:30:00").
- Overwriting an existing cycle is not supported in the initial implementation.

### Test Case Conflict (Same PID)

When `testCaseKey` matches an existing qTest PID:
- **Do not modify the existing Test Case** — use it as-is (link the Test Run to it)
- Rationale: the Test Case in qTest may have additional approved versions, shared steps,
  or traceability links that should not be disturbed by an import

When no matching PID is found:
- Create a new Test Case
- Use a module named after `moduleName` field (or "ReportForge Imports" as fallback)

### Re-Push (Same Report, Push Again)

If `report_fields` contains a `qtest.lastPushCycleId` for the target project:
- Warn the user: "This report was previously pushed to qTest (Cycle ID: X). Push again?"
- If confirmed: create a second Test Cycle (same rules as above, with timestamp suffix)
- If user wants to update the existing cycle: that is a future feature (see §17)

---

## 12. Sync Model

The initial implementation uses a **one-way, on-demand** sync model:

| Direction | Trigger | Conflict strategy |
|-----------|---------|-------------------|
| qTest → ReportForge | Manual "Import" action | Always creates new report; never overwrites |
| ReportForge → qTest | Manual "Push" action | Always creates new Test Cycle; never updates |

This is the safest model for a first release. It avoids complex merge logic and
preserves data integrity in both systems.

**qtest_id tracking in report_fields:**
```
qtest.sourceProjectId      — qTest project ID this report was imported from (import only)
qtest.sourceCycleId        — qTest Test Cycle ID imported from
qtest.lastPushProjectId    — qTest project ID last pushed to
qtest.lastPushCycleId      — qTest Test Cycle ID created on last push
qtest.lastPushTimestamp    — ISO 8601 timestamp of last push
```

These keys are set during import/push and displayed in the UI as "qTest sync metadata"
for informational purposes.

---

## 13. Java HTTP Client Approach

### Recommended Library

Use **`java.net.http.HttpClient`** (built-in since Java 11):
- No additional dependencies
- Supports async requests (`sendAsync`)
- Handles gzip encoding
- Compatible with the project's Java module system

For multipart form-data (attachment upload), Jackson's `ObjectMapper` handles JSON
serialization; multipart boundary encoding requires manual construction or a thin
utility class since `java.net.http.HttpClient` does not include a multipart builder.

### Proposed Package Layout

```
com.buraktok.reportforge.integration.qtest
├── QtestConnectionSettings.java       — stores baseUrl, apiToken (loaded from settings/qtest-connection.json)
├── QtestClient.java                   — low-level HTTP facade (get, post, postMultipart, with retry)
├── QtestProjectService.java           — listProjects(), getCycles(), getSuites(), getRuns(), ...
├── QtestImportService.java            — orchestrates the full import flow
├── QtestPushService.java              — orchestrates the full push flow
├── QtestStatusMapper.java             — bidirectional status translation (configurable)
└── model/
    ├── QtestProject.java              — minimal API response models
    ├── QtestTestCycle.java
    ├── QtestTestRun.java
    ├── QtestTestLog.java
    ├── QtestTestCase.java
    └── QtestAttachment.java
```

### JSON Serialization

Use **Jackson** (already in the project) for:
- Serializing request bodies to JSON
- Deserializing qTest JSON responses

Annotate qTest model classes with `@JsonIgnoreProperties(ignoreUnknown = true)` to
tolerate API version variations.

### Error Handling in HttpClient

```java
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() == 429) {
    String retryAfter = response.headers().firstValue("retry-after").orElse("10");
    Thread.sleep(Long.parseLong(retryAfter) * 1000L);
    // retry
}
if (response.statusCode() >= 400) {
    throw new QtestApiException(response.statusCode(), response.body());
}
```

---

## 14. Error Handling and Rate Limiting

### Per-Request Error Strategy

| HTTP Status | Handling |
|-------------|----------|
| 200/201 | Success — continue |
| 400 | Log response body; abort the current run; continue with next run |
| 401 | Abort entire operation; show "Invalid API token" dialog |
| 403 | Abort entire operation; show "Insufficient permissions" dialog |
| 404 | Test Case / Test Cycle not found — create it; log the miss |
| 413 | Attachment too large — skip attachment; log warning; continue |
| 429 | Wait `retry-after` seconds; retry up to 3 times with exponential backoff |
| 5xx | Retry up to 3 times with 2-second delay; abort if all retries fail |

### Operation-Level Error Strategy

- If Test Cycle creation fails: abort the entire push — nothing was committed
- If Test Run creation fails for one run: log the failure, skip that run, continue with others
- If Test Log submission fails: log it; the Test Run exists but has no result — that is acceptable
- If attachment upload fails: log it; do not fail the run
- At the end, show a summary: "X runs pushed successfully, Y skipped due to errors. See logs."

### Logging

Errors during qTest operations should be written to the application logger (using the
existing logging infrastructure) at WARN/ERROR level with full HTTP status and response
body for diagnostics.

---

## 15. UI Entry Points

### New Menu Items

Under a new **"Integrations"** top-level menu (or under "File"):

| Menu Item | Action |
|-----------|--------|
| "Import from qTest…" | Opens import dialog; target = selected environment or prompts for new |
| "Push to qTest…" | Opens push dialog for the currently selected report |
| "qTest Connection Settings…" | Opens credential/URL configuration dialog |

### Import Dialog Layout

1. Connection status indicator (green/red dot + "Connected to {base-url}")
2. Project picker (combo box, fetched on open)
3. Test Cycle picker (combo box, populated after project selection)
4. Preview: "X test runs will be imported"
5. Target environment picker (existing or "Create new environment")
6. [Import] and [Cancel] buttons
7. Progress bar (indeterminate while fetching, then determinate X/Y during import)

### Push Dialog Layout

1. Connection status indicator
2. Target project picker
3. Parent Release picker (optional; "No release / root" option)
4. Preview: report title, X execution runs, Y with evidence
5. Status mapping review (collapsed by default; expand to reconfigure)
6. [Push] and [Cancel] buttons
7. Progress bar (X/Y runs pushed)
8. On completion: "View in qTest" button (opens `https://{company}.qtestnet.com/p/{projectId}/portal/...`)

---

## 16. Clarifying Questions

The following questions need answers before full implementation begins:

**1.** Should "Import from qTest" create a brand-new ReportForge report (blank except for
imported data), or should it merge into an existing report? The current plan assumes
always-new; merging is significantly more complex.

**2.** When importing a Test Cycle that has no Test Log yet (test runs planned but not
executed), should the execution runs be imported with status `NOT_RUN`, or should
unexecuted runs be skipped entirely?

**3.** Should test case preconditions from qTest be imported into ReportForge? If yes,
where — prepended to `testSteps` text, or stored in a separate field like `remarks`?

**4.** Do you have qTest SaaS or qTest On-Premises? This affects the authentication flow
(SaaS always uses Bearer token; on-premises may have additional SSO configurations).

**5.** Which qTest fields do you consider mandatory for the push to be "valid"? For example,
should a push fail if `testCaseKey` is blank (cannot match existing test cases), or
should it auto-create test cases with a generated name?

**6.** Should evidence images be pushed to qTest when pushing execution results? Images
are embedded in the HTML export but pushing large attachments to qTest could be slow
and consume storage. An option "Include evidence images" (mirroring the existing
export preset) would allow the user to control this.

**7.** Should the app store qTest IDs (test case ID, test run ID, test log ID) back into
the ReportForge database after a successful push, to enable future "update existing"
operations? Storing them now would enable a smarter re-push flow later.

**8.** For the status mapping, do your qTest projects use the default status names (PASS,
FAIL, BLOCKED, etc.) or custom status names configured by your qTest admin? If custom,
the mapping UI needs to be exposed.

**9.** Do you want to support importing from **Test Suites** (lower-level groupings) in
addition to Test Cycles, or is Test Cycle granularity sufficient for your workflow?

**10.** When pushing a report with multiple execution runs that reference the same
`testCaseKey`, should all runs map to the same Test Case (multiple test logs on the
same Test Run), or should each ReportForge ExecutionRun always produce a separate
Test Run in qTest?

**11.** Should there be a "dry run" preview mode that shows what would be created/updated
in qTest without actually committing anything? This would be helpful for validating
the mapping before pushing a large report.

**12.** Where should the qTest API token be stored? Options:
  - a) In the project file (per-project credentials, portable with the project)
  - b) In Java `Preferences` (per-machine, not in the project file — recommended for security)
  - c) In an OS keychain/credential store (most secure, requires additional library)

---

## 17. Future Considerations

### Bidirectional Sync (Two-Way)

Once qTest IDs are stored back in ReportForge (see Q7), a bidirectional sync becomes
feasible:
- Detect new test logs added to qTest after the last push and pull them as new execution runs
- Detect status changes in qTest and reflect them in ReportForge
- Requires a "last sync timestamp" stored in `report_fields` and a poll-based or
  webhook-based change detection strategy

### Webhook-Based Change Notifications

qTest supports webhooks (`POST /api/v3/webhooks`). A future integration could
register a webhook for `TestRun.status.changed` events to auto-update ReportForge
reports when results are updated in qTest.

### qTest Parameters (Data-Driven Tests)

qTest supports parameterized test steps using `[~paramName]` syntax. If imported
test cases use parameters, the app could render them with sample values during import
or strip the parameter markers.

### qTest Insights / Analytics

qTest's Insights API provides aggregated metrics. These could be fetched and pre-filled
into the `executionSummary.summaryNarrative` field to speed up report writing.

### qTest Pulse / Launch Integration

Tricentis qTest also has Pulse (CI pipeline integration) and Launch (test execution
scheduling) products. Integration with those is out of scope for the initial release
but should be evaluated once the Manager API integration is stable.

### Multiple qTest Connections

If users have multiple qTest instances (e.g., one for QA, one for UAT), the settings
model should support a named list of connections rather than a single connection.

### Defect System Integration via qTest

qTest can bridge to JIRA/Azure DevOps for defect linking. When pushing from ReportForge,
the `relatedIssue` field value could be passed to qTest's defect linking endpoint
(`POST /api/v3/projects/{id}/linked-artifacts`) if the qTest project has a defect
tracker configured.
