package io.ledgerlift.template;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses the GL interface template (FBDI-style CSV, optionally zipped).
 *
 * <p>Template columns, in order:
 * {@code LEDGER_NAME, PERIOD_NAME, JOURNAL_NAME, LINE_DESCRIPTION, ACCOUNT_CODE,
 * CURRENCY_CODE, ENTERED_DR, ENTERED_CR, ACCOUNTING_DATE, REFERENCE}.
 *
 * <p>Like the Oracle templates, a header row is optional: a first record whose
 * first cell equals {@code LEDGER_NAME} (case-insensitive) is skipped. Lines that
 * are blank or start with {@code #} are ignored. A UTF-8 BOM is tolerated.
 */
public final class TemplateParser {

    public static final List<String> COLUMNS = List.of(
            "LEDGER_NAME", "PERIOD_NAME", "JOURNAL_NAME", "LINE_DESCRIPTION", "ACCOUNT_CODE",
            "CURRENCY_CODE", "ENTERED_DR", "ENTERED_CR", "ACCOUNTING_DATE", "REFERENCE");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US),
    };

    private final ParsePolicy policy;

    public TemplateParser(ParsePolicy policy) {
        this.policy = policy;
    }

    public ParseResult parse(byte[] bytes, String fileName) {
        byte[] csv = isZip(bytes) ? firstCsvFromZip(bytes, fileName) : bytes;
        return parseCsv(new ByteArrayInputStream(csv));
    }

    ParseResult parseCsv(InputStream in) {
        List<GlInterfaceRow> rows = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        try {
            CsvReader reader = new CsvReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            CsvReader.Record rec;
            int dataLine = 0;
            boolean first = true;
            while ((rec = reader.next()) != null) {
                List<String> f = rec.fields();
                if (first) {
                    first = false;
                    if (!f.isEmpty()) f.set(0, stripBom(f.get(0)));
                    if (!f.isEmpty() && f.get(0).trim().equalsIgnoreCase(COLUMNS.get(0))) {
                        continue; // header row
                    }
                }
                String raw = rec.raw();
                if (raw.isBlank() || raw.startsWith("#")) continue;
                dataLine++;
                rows.add(parseRow(dataLine, f, raw, errors));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ParseResult(List.copyOf(rows), List.copyOf(errors), policy);
    }

    private GlInterfaceRow parseRow(int lineNo, List<String> f, String raw, List<ParseError> errors) {
        if (f.size() != COLUMNS.size()) {
            errors.add(new ParseError(lineNo, null,
                    "expected " + COLUMNS.size() + " columns, found " + f.size(), raw));
            // pad / truncate so we can still keep what is there
            List<String> fixed = new ArrayList<>(f);
            while (fixed.size() < COLUMNS.size()) fixed.add("");
            f = fixed.subList(0, COLUMNS.size());
        }
        String ledger = text(f.get(0));
        String period = text(f.get(1));
        String journal = text(f.get(2));
        String desc = text(f.get(3));
        String account = text(f.get(4));
        String currency = text(f.get(5));
        BigDecimal dr = amount(lineNo, "ENTERED_DR", f.get(6), raw, errors);
        BigDecimal cr = amount(lineNo, "ENTERED_CR", f.get(7), raw, errors);
        LocalDate date = date(lineNo, f.get(8), raw, errors);
        String ref = text(f.get(9));
        if (currency != null && currency.length() != 3) {
            errors.add(new ParseError(lineNo, "CURRENCY_CODE", "currency code must be 3 letters: '" + currency + "'", raw));
            currency = null;
        }
        return new GlInterfaceRow(lineNo, ledger, period, journal, desc, account,
                currency == null ? null : currency.toUpperCase(Locale.ROOT), dr, cr, date, ref, raw);
    }

    private static String text(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal amount(int lineNo, String col, String s, String raw, List<ParseError> errors) {
        String t = text(s);
        if (t == null) return null;
        try {
            BigDecimal v = new BigDecimal(t.replace(",", ""));
            if (v.signum() < 0) {
                errors.add(new ParseError(lineNo, col, "amount must not be negative: " + t, raw));
                return null;
            }
            if (v.scale() > 2) {
                errors.add(new ParseError(lineNo, col, "amount has more than 2 decimal places: " + t, raw));
                return null;
            }
            return v.setScale(2);
        } catch (NumberFormatException e) {
            errors.add(new ParseError(lineNo, col, "not a number: '" + t + "'", raw));
            return null;
        }
    }

    private static LocalDate date(int lineNo, String s, String raw, List<ParseError> errors) {
        String t = text(s);
        if (t == null) return null;
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(t, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        errors.add(new ParseError(lineNo, "ACCOUNTING_DATE",
                "unrecognised date '" + t + "' (use yyyy-MM-dd, MM/dd/yyyy or dd-MMM-yyyy)", raw));
        return null;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static boolean isZip(byte[] b) {
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    private static byte[] firstCsvFromZip(byte[] bytes, String fileName) {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".csv")
                        && !e.getName().startsWith("__MACOSX")) {
                    return zin.readAllBytes();
                }
            }
        } catch (IOException ex) {
            throw new TemplateException("unreadable zip archive " + fileName, ex);
        }
        throw new TemplateException("zip archive " + fileName + " contains no .csv entry");
    }
}
