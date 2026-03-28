# ReportForge → qTest Excel Export

This document covers the design and implementation of an **"Export to qTest Excel"** feature
in ReportForge. qTest Manager supports importing test cases and test execution results from
`.xlsx` files, making this an alternative to the REST API push described in `QTEST_INTEGRATION.md`.

The Excel path is useful when:
- The user does not have API credentials or API access is restricted
- A QA manager wants to review the exported data in Excel before uploading
- The qTest instance is behind a firewall that blocks direct API calls from a desktop app
- Batch importing into multiple qTest projects without scripting

---

## Table of Contents

1. [qTest Excel Import Overview](#1-qtest-excel-import-overview)
2. [Two Export Targets](#2-two-export-targets)
3. [Sheet 1: Test Cases (Test Design Import)](#3-sheet-1-test-cases)
4. [Sheet 2: Test Execution Results](#4-sheet-2-test-execution-results)
5. [ReportForge → Excel Column Mapping](#5-reportforge--excel-column-mapping)
6. [File Format and Structural Rules](#6-file-format-and-structural-rules)
7. [Status Mapping](#7-status-mapping)
8. [Excel Library Choice](#8-excel-library-choice)
9. [Implementation Plan](#9-implementation-plan)
10. [Export UI Design](#10-export-ui-design)
11. [Export Options / Presets](#11-export-options--presets)
12. [Verification Checklist](#12-verification-checklist)
13. [Clarifying Questions](#13-clarifying-questions)
14. [Future Considerations](#14-future-considerations)

---

## 1. qTest Excel Import Overview

qTest Manager provides two distinct Excel import flows, each in a different module of
the application:

| Import Type | Location in qTest UI | Purpose |
|-------------|----------------------|---------|
| **Test Case Import** | Test Design → (three-dot menu) → Import | Creates/updates test cases in the test repository |
| **Test Execution Import** | Test Execution → (test cycle context menu) → Import | Imports test run results into an existing test cycle |

Both flows open a multi-step wizard:
1. Upload the `.xlsx` file
2. **Column mapping step** — maps your spreadsheet columns to qTest fields
3. Preview / validation
4. Import

Because of the **column mapping step**, the exact column header names are flexible —
qTest lets you map any header to any field. However, producing a file that exactly
matches the expected headers avoids the manual mapping step and enables zero-configuration
upload.

> ⚠️ **Template verification note:** The column header names documented below are based
> on established community knowledge of qTest Manager v8–v10. To verify against your
> exact qTest version, download the official template from within qTest:
> - Test Case Import: **Download Sample File** button in the import wizard
> - Test Execution Import: **Download Sample File** button in the test execution import wizard
> 
> Adjust the header names in this export accordingly if they differ.

---

## 2. Two Export Targets

ReportForge's execution data maps to both qTest import types. A single `.xlsx` file
can contain both sheets, allowing the user to import into qTest in two steps:

```
ReportForge Report
       │
       ├──► Sheet 1: "Test Cases"
       │         └── qTest: Test Design → Import Test Cases
       │
       └──► Sheet 2: "Test Execution Results"
                 └── qTest: Test Execution → Import Test Results
                           (requires the test cycle to exist first —
                            created by Sheet 1 import or via API/UI)
```

Sheet 1 creates the test cases in qTest's Test Design module.  
Sheet 2 imports the execution results for those test cases.

Alternatively, the user can export only Sheet 2 if the test cases already exist in qTest
and are matched by PID/name.

---

## 3. Sheet 1: Test Cases

### Sheet Name
`Test Cases`

### Format: Row-Based Steps (Primary)

qTest's test case import supports two step layouts. The **row-based** format is the
standard and most flexible:

- Each **test case** occupies one or more rows.
- The **first row** of a test case fills all metadata columns.
- **Subsequent rows** for the same test case contain only the step-specific columns
  (all other columns are left blank — this signals qTest to treat the row as a
  continuation step of the preceding test case).

#### Column Headers (row-based format)

| Column | Required | Description |
|--------|----------|-------------|
| `Test Case Name` | ✓ (TC row only) | Name of the test case; blank on step rows |
| `Module` | ✓ (TC row) | Full module path using ` > ` separator (e.g., `Login > Auth`) |
| `Test Case Status` | — | `Draft` or `Approved` (defaults to `Draft` if omitted) |
| `Priority` | — | `Critical`, `High`, `Medium`, or `Low` |
| `Description` | — | Test case description / objective |
| `Pre-Condition` | — | Preconditions text |
| `Assigned To` | — | User email or name for assignment |
| `Estimated Duration` | — | Duration in minutes (integer) |
| `Test Step Description` | — | Step text (step rows only) |
| `Test Step Expected Result` | — | Expected result text (step rows only) |

#### Example Layout

```
Test Case Name | Module         | Priority | Description         | Pre-Condition   | Test Step Description          | Test Step Expected Result
TC-001: Login  | Login > Auth   | High     | Verify login flow   | App is loaded   |                                |
               |                |          |                     |                 | Navigate to /login             | Login page is visible
               |                |          |                     |                 | Enter valid credentials        | Fields accept input
               |                |          |                     |                 | Click Submit                   | Redirected to dashboard
TC-002: Logout | Login > Auth   | Medium   | Verify logout flow  |                 |                                |
               |                |          |                     |                 | Click Logout button            | Confirmation prompt shown
               |                |          |                     |                 | Confirm logout                 | Session ends, login shown
```

### Format: Column-Based Steps (Flat, Alternative)

Some qTest versions and team configurations prefer a single row per test case with
step columns in pairs:

| Column | Notes |
|--------|-------|
| `Test Case Name`, `Module`, `Priority`, `Description`, `Pre-Condition` | Same as above |
| `Step 1 - Test Step Description` | First step text |
| `Step 1 - Test Step Expected Result` | First step expected |
| `Step 2 - Test Step Description` | Second step text |
| `Step 2 - Test Step Expected Result` | Second step expected |
| `Step N - ...` | Up to N steps |

This is less preferred for ReportForge export because:
- `testSteps` in ReportForge is a free-text multiline field; the number of steps
  is variable and can be large
- Column-based format creates very wide spreadsheets that are hard to review
- qTest's column mapper handles row-based format more reliably

**Recommendation:** Default to row-based format. Offer column-based as an export option.

---

## 4. Sheet 2: Test Execution Results

### Sheet Name
`Test Execution Results`

### Purpose

This sheet imports execution results into a **pre-existing** test cycle/suite in qTest.
Before importing this sheet, the test cycle must exist. This can be:
- Created manually in qTest UI
- Created by the Sheet 1 import + running the tests
- Created via the ReportForge API push (from `QTEST_INTEGRATION.md`)

### Column Headers

| Column | Required | Description |
|--------|----------|-------------|
| `Test Suite` | ✓ | Name of the test suite within the cycle (used to locate the Test Run) |
| `Test Case Name` | ✓* | Name of the test case (*required if no Test Case PID) |
| `Test Case PID` | ✓* | qTest PID like `TC-123` (*required if no Test Case Name) |
| `Execution Status` | ✓ | Result status (see §7 for valid values) |
| `Execution Date` | ✓ | Date/time of execution (`MM/DD/YYYY HH:mm` or ISO 8601) |
| `Executed By` | — | Username or display name of tester |
| `Note` | — | Execution notes / comments |
| `Actual Result` | — | What actually happened (overall, not per-step) |
| `Planned Start Date` | — | Planned execution start date |
| `Planned End Date` | — | Planned execution end date |
| `Build` | — | Build number under test |

#### Per-Step Result Columns (Optional)

If step-level results are needed:

| Column | Description |
|--------|-------------|
| `Step 1 - Actual Result` | Actual result for step 1 |
| `Step 1 - Status` | Pass/Fail status for step 1 |
| `Step 2 - Actual Result` | Actual result for step 2 |
| `Step 2 - Status` | Pass/Fail status for step 2 |
| `Step N - ...` | Up to N steps |

Step-level columns are optional. If omitted, all steps inherit the overall execution status.

#### Example Layout

```
Test Suite      | Test Case PID | Test Case Name     | Execution Status | Execution Date     | Executed By | Note
Sprint 42 Suite | TC-001        | TC-001: Login      | PASS             | 03/28/2025 10:30   | j.doe       | All checks passed
Sprint 42 Suite | TC-002        | TC-002: Logout     | FAIL             | 03/28/2025 10:45   | j.doe       | Redirect missing on confirm
Sprint 42 Suite | TC-003        | TC-003: Forgot Pwd | BLOCKED          | 03/28/2025 11:00   | j.doe       | Environment issue, reset srv down
```

---

## 5. ReportForge → Excel Column Mapping

### Sheet 1: Test Cases Column Mapping

| ReportForge Field | Excel Column | Notes |
|-------------------|--------------|-------|
| `testCaseName` | `Test Case Name` | On the TC header row |
| `moduleName` | `Module` | Full path; sub-modules separated by ` > ` |
| `sectionName` + `subsectionName` | Appended to `Module` | e.g., `moduleName > sectionName > subsectionName` |
| `priority` | `Priority` | Pass through; normalize to Critical/High/Medium/Low |
| `expectedResultSummary` | `Description` | TC description row |
| `testSteps` (parsed, each line) | `Test Step Description` | One step row per line |
| — | `Test Step Expected Result` | Empty unless `expectedResultSummary` maps to last step |
| `remarks` / `comments` | `Pre-Condition` | Optional — include if non-empty |
| `testCaseKey` | Custom field or `Description` prefix | e.g., prefix "Ref: TC-42" in Description |

### Sheet 2: Test Execution Results Column Mapping

| ReportForge Field | Excel Column | Notes |
|-------------------|--------------|-------|
| `suiteName` (or `testCaseName`) | `Test Suite` | Group by `suiteName`; fallback to report title |
| `testCaseKey` | `Test Case PID` | e.g., `TC-42` |
| `testCaseName` | `Test Case Name` | Display name |
| `status` | `Execution Status` | See §7 for mapping |
| `executionDate` or `startDate` | `Execution Date` | Prefer `startDate`; fallback to `executionDate` |
| `executedBy` | `Executed By` | Direct copy |
| `comments` | `Note` | Main notes field |
| `remarks` | `Note` suffix | Appended to Note with separator if non-blank |
| `actualResult` | `Actual Result` | Direct copy |
| `defectSummary` | Appended to `Note` | Prefix: "Defect: " |
| `relatedIssue` | Appended to `Note` | Prefix: "Related Issue: " |
| `blockedReason` | Appended to `Note` | Prefix: "Blocked: " |
| `testSteps` (parsed) | `Step N - Actual Result` / `Step N - Status` | Optional; same status as overall |
| `startDate` | `Planned Start Date` | ISO → `MM/DD/YYYY HH:mm` |
| `endDate` | `Planned End Date` | ISO → `MM/DD/YYYY HH:mm` |
| `dataSourceReference` | `Build` | If populated |

### Metadata Sheet (Optional Third Sheet)

A third sheet `Report Metadata` can contain the report-level narrative fields that
don't map to qTest test data but may be useful to the user when reviewing the file
before upload:

| Row | Content |
|-----|---------|
| Project Name | `projectOverview.projectName` |
| Report Type | `projectOverview.reportType` |
| Release / Iteration | `projectOverview.releaseIterationName` |
| Prepared By | `projectOverview.preparedBy` |
| Prepared Date | `projectOverview.preparedDate` |
| Test Objective | `scope.objectiveSummary` |
| In Scope | `scope.inScopeItems` |
| Out of Scope | `scope.outOfScopeItems` |
| Overall Conclusion | `conclusion.overallConclusion` |
| Recommendations | `conclusion.recommendations` |
| Open Concerns | `conclusion.openConcerns` |

---

## 6. File Format and Structural Rules

### File Format
- Output format: `.xlsx` (Office Open XML, OOXML)
- File extension: `.xlsx`
- Default file name: `{report-title}-qtest-import.xlsx` (sanitized for filesystem)

### Workbook Structure
```
{report-title}-qtest-import.xlsx
├── Sheet: "Test Cases"           (always present)
├── Sheet: "Test Execution Results" (always present)
└── Sheet: "Report Metadata"      (optional, controlled by export preset)
```

### Row 1: Headers
- Bold font, light blue background (`#DCE6F1`) matching qTest's own template style
- Frozen pane: first row frozen for scrolling

### Data Rows
- Start at row 2
- One execution run = one result row in Sheet 2
- One execution run = one TC header row + N step rows in Sheet 1

### Module Path Construction

The `Module` column value is constructed by joining available hierarchy fields with ` > `:

```
{moduleName} > {sectionName} > {subsectionName}
```

- If any segment is blank, it is omitted from the path
- If all segments are blank, use the report title as the module name:
  `ReportForge Imports > {report.title}`
- Module names longer than 255 characters are truncated (qTest limit)

### Date Format

qTest accepts several date formats in the import wizard. Recommended output: `MM/DD/YYYY HH:mm`

If `startDate` / `endDate` / `executionDate` are stored as ISO 8601 (`2025-03-28T10:30:00`),
convert them during export. If the time component is missing, output date-only: `MM/DD/YYYY`.

### Priority Normalization

ReportForge stores priority as free text. Normalize to qTest's known values:

| ReportForge value (case-insensitive) | Excel output |
|--------------------------------------|--------------|
| `critical`, `p0`, `blocker` | `Critical` |
| `high`, `p1` | `High` |
| `medium`, `normal`, `p2` | `Medium` |
| `low`, `p3`, `minor` | `Low` |
| anything else / blank | `Medium` (default) |

---

## 7. Status Mapping

Sheet 2 uses qTest's execution status names. The same configurable mapping from
`QTEST_INTEGRATION.md §7` applies here:

| ReportForge Status | Default Excel Value | Notes |
|--------------------|---------------------|-------|
| `PASS` | `PASS` | |
| `FAIL` | `FAIL` | |
| `BLOCKED` | `BLOCKED` | |
| `NOT_RUN` | `UNEXECUTED` | Some qTest versions use `NOT_EXECUTED` |
| `DEFERRED` | `INCOMPLETE` | Closest standard equivalent |
| `SKIPPED` | `SKIP` | May need `SKIPPED` depending on configuration |

The mapping should be configurable in the export dialog (same table used for both
API push and Excel export, stored in `settings/qtest-status-map.json`).

---

## 8. Excel Library Choice

No Excel library is currently in the ReportForge `pom.xml`. Two strong candidates:

### Option A: Apache POI (Recommended)

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>
```

**Pros:**
- Industry standard; full `.xlsx` read/write capability
- Rich API: cell styles, merged cells, frozen panes, column widths, fonts, colors
- Large community and documentation
- Used by thousands of Java projects

**Cons:**
- Heavy: ~10–15 MB transitive dependency footprint (poi + poi-ooxml + xmlbeans + commons-*)
- Has known issues with the Java module system (JPMS) — requires `--add-opens` JVM args
  or running with `useModulePath=false` (already configured in Surefire)
- Loads entire workbook into memory (fine for typical report sizes)

**JPMS note:** Apache POI uses reflection internally and doesn't publish a `module-info.java`.
It works fine as an automatic module with `useModulePath=false`, which is already the test
configuration. For the runtime app (launched via `mvn javafx:run`), additional `--add-opens`
args may be required in the JavaFX plugin configuration.

### Option B: FastExcel (Lightweight Write-Only)

```xml
<dependency>
    <groupId>org.dhatim</groupId>
    <artifactId>fastexcel</artifactId>
    <version>0.18.4</version>
</dependency>
```

**Pros:**
- Very small: ~300 KB (write-only streaming OOXML)
- No reflection, clean JPMS compatibility
- Fast for generating large files

**Cons:**
- Write-only (no reading needed for export, so this is acceptable)
- Fewer styling options vs Apache POI
- Less documentation

### Recommendation

**Apache POI** for the richer formatting output (frozen panes, header styles, column
auto-sizing, cell validation). The JPMS friction is manageable given the existing
`useModulePath=false` Surefire configuration and the fact that ReportForge already has
several non-modular dependencies.

If keeping the distribution small is a priority, FastExcel is the better pick — the
resulting `.xlsx` will have the same data, just simpler formatting.

---

## 9. Implementation Plan

### Proposed Package

```
com.buraktok.reportforge.export
├── HtmlReportExporter.java          (existing)
└── QtestExcelExporter.java          (new)
    │
    ├── buildTestCasesSheet(...)      → Sheet 1
    ├── buildTestExecutionSheet(...)  → Sheet 2
    ├── buildMetadataSheet(...)       → Sheet 3 (optional)
    ├── mapStatus(String)             → normalized qTest status string
    ├── buildModulePath(...)          → "mod > section > sub"
    └── formatDate(String isoDate)   → "MM/DD/YYYY HH:mm"
```

### Core Export Method Signature

```java
public class QtestExcelExporter {

    /**
     * Exports a ReportForge report to a qTest-compatible .xlsx file.
     *
     * @param report      The report record
     * @param runs        Ordered list of execution runs
     * @param fields      Map of report_fields key → value
     * @param options     Export options (which sheets, status map, etc.)
     * @param outputPath  Target file path for the .xlsx
     */
    public void export(
        ReportRecord report,
        List<ExecutionRunRecord> runs,
        Map<String, String> fields,
        QtestExcelExportOptions options,
        Path outputPath
    ) throws IOException { ... }
}
```

### Export Options Model

```java
public class QtestExcelExportOptions {
    boolean includeTestCasesSheet = true;
    boolean includeTestExecutionSheet = true;
    boolean includeMetadataSheet = false;
    boolean useRowBasedSteps = true;         // true = row-based; false = column-based
    boolean includeStepResults = false;      // step-level status in Sheet 2
    String defaultModuleName = "ReportForge Imports";
    Map<String, String> statusMap = DEFAULT_STATUS_MAP;
}
```

### Step-by-Step Build Logic

**Sheet 1 — Test Cases:**
```
for each ExecutionRunRecord run in sorted order:
    emit TC header row:
        Test Case Name  = run.testCaseName (or suiteName fallback)
        Module          = buildModulePath(run.moduleName, run.sectionName, run.subsectionName)
        Priority        = normalizePriority(run.priority)
        Description     = run.expectedResultSummary
        Pre-Condition   = run.remarks (if non-blank)

    if run.testSteps is non-blank:
        parse lines = run.testSteps.split("\n").filter(not blank)
        for each line:
            emit step row:
                Test Case Name            = "" (blank = step continuation row)
                Test Step Description     = line (strip leading numbering: "1. ", "1) ", etc.)
                Test Step Expected Result = "" (or last line gets expectedResultSummary if only 1 step)
    else if run.expectedResultSummary is non-blank:
        emit synthetic step row:
            Test Step Description     = "Execute test case"
            Test Step Expected Result = run.expectedResultSummary
```

**Sheet 2 — Test Execution Results:**
```
for each ExecutionRunRecord run in sorted order:
    emit result row:
        Test Suite        = run.suiteName (or report.title if blank)
        Test Case PID     = run.testCaseKey (blank if not set)
        Test Case Name    = run.testCaseName (or run.getDisplayLabel())
        Execution Status  = mapStatus(run.status)
        Execution Date    = formatDate(run.startDate ?? run.executionDate)
        Executed By       = run.executedBy
        Note              = buildNote(run)     // see below
        Actual Result     = run.actualResult
        Planned Start     = formatDate(run.startDate)
        Planned End       = formatDate(run.endDate)
        Build             = run.dataSourceReference
```

**`buildNote(run)` helper:**
```
parts = []
if run.comments non-blank:    parts.add(run.comments)
if run.remarks non-blank:     parts.add("Remarks: " + run.remarks)
if run.defectSummary non-blank: parts.add("Defect: " + run.defectSummary)
if run.relatedIssue non-blank:  parts.add("Issue: " + run.relatedIssue)
if run.blockedReason non-blank: parts.add("Blocked: " + run.blockedReason)
return parts.join("\n")
```

---

## 10. Export UI Design

### Menu Location

The export action should live alongside the existing HTML export:

**Report node context menu / toolbar:**
- "Export as HTML…" (existing)
- "Export as qTest Excel…" (new)

Or under a consolidated "Export" submenu:
- Export → HTML Report
- Export → qTest Excel

### Export Dialog

A simple dialog with:

1. **File path picker** — default `{report-title}-qtest-import.xlsx` in the last-used directory
2. **Sheet selection checkboxes:**
   - ☑ Include Test Cases sheet (Sheet 1)
   - ☑ Include Test Execution Results sheet (Sheet 2)
   - ☐ Include Report Metadata sheet (Sheet 3)
3. **Step format radio buttons** (when Sheet 1 is selected):
   - ● Row-based steps (recommended)
   - ○ Column-based steps (flat)
4. **Status mapping** — collapsed link: "Configure status mapping…"
   Opens a small table editor showing the mapping (shared with API push settings)
5. **[Export]** and **[Cancel]** buttons

The export runs on a background Task (consistent with the evidence threading pattern).
Show an indeterminate progress bar during generation. On success, offer "Open in file
explorer" or "Open file" actions.

---

## 11. Export Options / Presets

Store qTest Excel export preferences in `report_fields` under the same `exportPresets.*`
namespace used for HTML export, or in a new `qtestExportPresets.*` namespace:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `qtestExportPresets.includeTestCasesSheet` | boolean | true | Include Sheet 1 |
| `qtestExportPresets.includeTestExecutionSheet` | boolean | true | Include Sheet 2 |
| `qtestExportPresets.includeMetadataSheet` | boolean | false | Include Sheet 3 |
| `qtestExportPresets.useRowBasedSteps` | boolean | true | Row vs column steps |
| `qtestExportPresets.includeStepResults` | boolean | false | Per-step status in Sheet 2 |
| `qtestExportPresets.defaultModuleName` | string | `"ReportForge Imports"` | Fallback module |

---

## 12. Verification Checklist

Before finalizing the implementation, verify the following against a live qTest instance
or the downloaded official template:

- [ ] Column header names for Test Case import match exactly (case-sensitive headers may matter)
- [ ] Column header names for Test Execution import match exactly
- [ ] `Module` path separator is ` > ` (space-greater-sign-space, not just `>`)
- [ ] `Test Case Status` accepts `Draft` / `Approved` (vs `DRAFT` / `APPROVED`)
- [ ] Date format accepted: test `MM/DD/YYYY HH:mm`, `YYYY-MM-DD`, and ISO 8601
- [ ] Status values: confirm exact strings accepted (PASS vs Pass vs passed)
- [ ] Row-based steps: confirm that a blank `Test Case Name` column on a row makes qTest treat it as a step continuation
- [ ] Maximum number of rows per import (if any)
- [ ] Maximum cell character limit (typical Excel limit: 32,767 chars per cell; qTest may be lower)
- [ ] Whether Test Execution import requires Test Runs to already exist, or creates them from Test Case Name/PID match
- [ ] Whether a Test Cycle must already exist or can be created during Test Execution import
- [ ] Multi-sheet workbook: does qTest's import wizard read only the first sheet, or does it let you select a sheet?

---

## 13. Clarifying Questions

**1.** Do you already have access to a live qTest instance where you can download the
official Excel import template? If so, please download both templates (Test Case import
and Test Execution import) and share the column headers. This would let us lock in the
exact header names before implementation.

**2.** For the Test Execution Results import: does your qTest instance require test cases
and test runs to already exist before importing results, or does qTest create new test
cases/runs automatically when importing from Excel?

**3.** When generating Sheet 1 (Test Cases), should each `ExecutionRunRecord` always create
a separate test case row, even if two runs have the same `testCaseKey`? Or should duplicate
`testCaseKey` values be merged into a single test case (de-duplicating the test case definition
while keeping multiple result rows in Sheet 2)?

**4.** Should the export include execution runs with status `NOT_RUN`? Including them
populates Sheet 1 with the full test scope, but Sheet 2 would have unexecuted rows.

**5.** Is there a need to export evidence images into the Excel file? Excel supports
embedded images but this would significantly increase file size and is not required
for qTest import. Alternatively, evidence could be listed as file paths/references in
a note column.

**6.** For the `Module` column in Sheet 1: should the qTest module path include the
report title as a top-level folder (e.g., `Sprint 42 Report > Login > Auth`), or
should it map directly to the `moduleName` field (e.g., `Login > Auth`)?

**7.** Should there be a separate "Export to qTest Excel" action per report (current plan),
or a project-level export that generates one workbook covering all reports in a project?

**8.** Apache POI adds ~15 MB to the distribution. Is this acceptable, or should we use
the lighter FastExcel library (less styling but same data content)?

---

## 14. Future Considerations

### Read-Back / Import from qTest Excel

If a user downloads the official qTest Excel template, manually fills it in, and wants
to import it into ReportForge, a reverse parser could be built. This would cover the
case where teams already maintain test case plans in Excel and want to bring them into
ReportForge without using the API.

### Excel as Universal Import Format

The qTest Excel format is close to a universal "test case spreadsheet" format used by
many other tools (TestRail, Zephyr, Xray). With minor column renaming, ReportForge's
Excel exporter could produce exports compatible with multiple test management tools
via a "target format" selector in the export dialog.

### JIRA Xray Excel Import

Xray (JIRA test management) has its own Excel import format for test cases and test
executions. The column structure is similar (Test Summary, Precondition, Step, Expected,
Status). A second export target alongside qTest could be added later with minimal
additional work.

### TestRail CSV/Excel Export

TestRail supports importing test cases from CSV (and Excel via CSV export). A
"TestRail CSV" export target would cover users on that platform.

### Validation Before Export

Add a pre-export validation step that warns the user if:
- Any execution run has a blank `testCaseName` (will create unnamed test cases in qTest)
- Any execution run has a blank `status` (will be mapped to default `UNEXECUTED`)
- `testSteps` field contains more than a configurable max lines per step (e.g., 50)
  to avoid hitting qTest's per-cell character limits
- The report has 0 execution runs (nothing to export)
