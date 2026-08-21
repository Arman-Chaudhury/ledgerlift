package io.ledgerlift.template;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One data line of the GL interface template, typed where parsing succeeded.
 * Fields that failed to parse are null and have a matching {@link ParseError};
 * the raw line is always retained so the error-correction file can echo it.
 */
public record GlInterfaceRow(
        int lineNo,
        String ledgerName,
        String periodName,
        String journalName,
        String lineDescription,
        String accountCode,
        String currencyCode,
        BigDecimal enteredDr,
        BigDecimal enteredCr,
        LocalDate accountingDate,
        String reference,
        String rawLine) {
}
