package io.ledgerlift.imports;

public record ImportError(long id, long batchId, Long interfaceId, Integer lineNo,
                          String ruleCode, String severity, String columnName, String message) {
}
