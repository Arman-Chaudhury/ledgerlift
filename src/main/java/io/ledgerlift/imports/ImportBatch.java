package io.ledgerlift.imports;

import java.time.Instant;

public record ImportBatch(
        long id,
        String sourceName,
        String checksum,
        Long ledgerId,
        BatchStatus status,
        int rowCount,
        int parseErrors,
        int errorCount,
        Instant createdAt,
        Instant updatedAt,
        Instant postedAt) {
}
