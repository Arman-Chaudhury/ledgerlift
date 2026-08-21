package io.ledgerlift.validation;

/** One validation result. {@code interfaceId}/{@code lineNo} are null for batch-level findings. */
public record Finding(Long interfaceId, Integer lineNo, String ruleCode, Severity severity, String column, String message) {

    public enum Severity { ERROR, WARNING }

    public static Finding error(Long interfaceId, Integer lineNo, String rule, String column, String message) {
        return new Finding(interfaceId, lineNo, rule, Severity.ERROR, column, message);
    }

    public static Finding warning(Long interfaceId, Integer lineNo, String rule, String column, String message) {
        return new Finding(interfaceId, lineNo, rule, Severity.WARNING, column, message);
    }

    public static Finding batchError(String rule, String message) {
        return new Finding(null, null, rule, Severity.ERROR, null, message);
    }
}
