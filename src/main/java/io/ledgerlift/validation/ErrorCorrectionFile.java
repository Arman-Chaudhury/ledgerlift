package io.ledgerlift.validation;

import io.ledgerlift.template.TemplateParser;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the error-correction CSV: every staged line that has at least one
 * finding, in template column order, plus an {@code ERRORS} column. The file
 * re-imports as-is (the parser drops the trailing ERRORS column when the
 * header declares it), so the fix-and-reload loop needs no spreadsheet surgery.
 */
@Component
public class ErrorCorrectionFile {

    public static final String ERRORS_COLUMN = "ERRORS";

    private final JdbcTemplate jdbc;

    public ErrorCorrectionFile(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String build(long batchId) {
        StringBuilder sb = new StringBuilder();
        List<String> header = new ArrayList<>(TemplateParser.COLUMNS);
        header.add(ERRORS_COLUMN);
        sb.append(String.join(",", header)).append("\r\n");

        jdbc.query("select gi.line_no, gi.ledger_name, gi.period_name, gi.journal_name, gi.line_description, gi.account_code,"
                        + " gi.currency_code, gi.entered_dr, gi.entered_cr, gi.accounting_date, gi.reference, gi.raw_line,"
                        + " (select count(*) from import_errors e where e.interface_id = gi.id) n"
                        + " from gl_interface gi where gi.batch_id = ? order by gi.line_no", rs -> {
                    if (rs.getInt("n") == 0) return;
                    List<String> msgs = jdbc.queryForList(
                            "select e.severity || ' ' || e.rule_code || ': ' || e.message from import_errors e"
                                    + " join gl_interface gi on gi.id = e.interface_id where gi.batch_id = ? and gi.line_no = ? order by e.id",
                            String.class, batchId, rs.getInt("line_no"));
                    String[] cells = {
                            rs.getString("ledger_name"), rs.getString("period_name"), rs.getString("journal_name"),
                            rs.getString("line_description"), rs.getString("account_code"), rs.getString("currency_code"),
                            plain(rs.getBigDecimal("entered_dr")), plain(rs.getBigDecimal("entered_cr")),
                            rs.getDate("accounting_date") == null ? null : rs.getDate("accounting_date").toLocalDate().toString(),
                            rs.getString("reference"), String.join(" | ", msgs)};
                    // If a typed cell failed to parse, echo the original raw text so the user sees what they sent.
                    String[] raw = rs.getString("raw_line").split(",", -1);
                    for (int i = 0; i < 10; i++) {
                        if (cells[i] == null && raw.length == 10 && !raw[i].isBlank()) cells[i] = raw[i].trim();
                    }
                    List<String> q = new ArrayList<>();
                    for (String c : cells) q.add(quote(c));
                    sb.append(String.join(",", q)).append("\r\n");
                }, batchId);

        // batch-level findings go in a trailing comment block the parser ignores
        List<String> batchLevel = jdbc.queryForList(
                "select severity || ' ' || rule_code || ': ' || message from import_errors where batch_id = ? and interface_id is null and rule_code <> 'PARSE' order by id",
                String.class, batchId);
        for (String m : batchLevel) sb.append("# BATCH ").append(m).append("\r\n");
        return sb.toString();
    }

    private static String plain(java.math.BigDecimal b) {
        return b == null ? null : b.toPlainString();
    }

    static String quote(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
