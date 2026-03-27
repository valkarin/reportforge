package com.buraktok.reportforge.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionRunEvidenceRecordTest {

    @Test
    void getThumbnailDisplayNameTruncatesBaseNameButKeepsExtensionVisible() {
        ExecutionRunEvidenceRecord evidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/abcdefghijklmnopqr.png",
                "abcdefghijklmnopqr.png",
                "image/png",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );

        assertEquals("abcdefghijklmno\u2026.png", evidence.getThumbnailDisplayName());
    }

    @Test
    void getThumbnailDisplayNameLeavesShortNamesAndStoredPathFallbackIntact() {
        ExecutionRunEvidenceRecord originalNameEvidence = new ExecutionRunEvidenceRecord(
                "evidence-1",
                "run-1",
                "evidence/execution-runs/run-1/result.png",
                "result.png",
                "image/png",
                0,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );
        ExecutionRunEvidenceRecord storedPathFallbackEvidence = new ExecutionRunEvidenceRecord(
                "evidence-2",
                "run-1",
                "evidence/execution-runs/run-1/clip.webp",
                "",
                "image/webp",
                1,
                "2026-03-06T08:00:00Z",
                "2026-03-06T08:00:00Z"
        );

        assertAll(
                () -> assertEquals("result.png", originalNameEvidence.getThumbnailDisplayName()),
                () -> assertEquals("clip.webp", storedPathFallbackEvidence.getThumbnailDisplayName())
        );
    }
}
