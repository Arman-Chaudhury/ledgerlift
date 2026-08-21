package io.ledgerlift.validation;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** The rule pack. Each bean is one rule; order matters only for report readability. */
@Configuration
public class SqlRules {

    private final JdbcTemplate jdbc;

    public SqlRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ValidationRule rule(String code, java.util.function.LongFunction<List<Finding>> fn) {
        return new ValidationRule() {
            @Override public String code() { return code; }
            @Override public List<Finding> apply(long batchId) { return fn.apply(batchId); }
        };
    }

    private RowMapper<Finding> lineError(String code, String column, java.util.function.Function<java.sql.ResultSet, String> msg) {
        return (rs, i) -> Finding.error(rs.getLong("id"), rs.getInt("line_no"), code, column, msg.apply(rs));
    }

    private static String str(java.sql.ResultSet rs, String col) {
        try { return rs.getString(col); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }

    @Bean @Order(10)
    ValidationRule requiredFields() {
        return rule("REQUIRED_FIELD", batchId -> {
            List<Finding> out = new java.util.ArrayList<>();
            String[][] cols = {
                    {"ledger_name", "LEDGER_NAME"}, {"period_name", "PERIOD_NAME"}, {"journal_name", "JOURNAL_NAME"},
                    {"account_code", "ACCOUNT_CODE"}, {"currency_code", "CURRENCY_CODE"}, {"accounting_date", "ACCOUNTING_DATE"}};
            for (String[] c : cols) {
                out.addAll(jdbc.query(
                        "select id, line_no from gl_interface where batch_id = ? and " + c[0] + " is null order by line_no",
                        lineError("REQUIRED_FIELD", c[1], rs -> c[1] + " is required"), batchId));
            }
            out.addAll(jdbc.query(
                    "select id, line_no from gl_interface where batch_id = ? and entered_dr is null and entered_cr is null order by line_no",
                    lineError("REQUIRED_FIELD", "ENTERED_DR", rs -> "line needs ENTERED_DR or ENTERED_CR"), batchId));
            out.addAll(jdbc.query(
                    "select id, line_no from gl_interface where batch_id = ? and coalesce(entered_dr,0) <> 0 and coalesce(entered_cr,0) <> 0 order by line_no",
                    lineError("DR_AND_CR", "ENTERED_CR", rs -> "line carries both a debit and a credit; split it into two lines"), batchId));
            return out;
        });
    }

    @Bean @Order(20)
    ValidationRule unknownLedger() {
        return rule("UNKNOWN_LEDGER", batchId -> jdbc.query(
                "select gi.id, gi.line_no, gi.ledger_name from gl_interface gi left join ledgers l on l.name = gi.ledger_name"
                        + " where gi.batch_id = ? and gi.ledger_name is not null and l.id is null order by gi.line_no",
                lineError("UNKNOWN_LEDGER", "LEDGER_NAME", rs -> "ledger '" + str(rs, "ledger_name") + "' does not exist"), batchId));
    }

    @Bean @Order(25)
    ValidationRule mixedLedgers() {
        return rule("MIXED_LEDGERS", batchId -> {
            Integer n = jdbc.queryForObject(
                    "select count(distinct ledger_name) from gl_interface where batch_id = ? and ledger_name is not null", Integer.class, batchId);
            return n != null && n > 1
                    ? List.of(Finding.batchError("MIXED_LEDGERS", "file names " + n + " ledgers; one import file targets one ledger"))
                    : List.of();
        });
    }

    @Bean @Order(30)
    ValidationRule unknownOrDisabledAccount() {
        return rule("UNKNOWN_ACCOUNT", batchId -> {
            List<Finding> out = new java.util.ArrayList<>(jdbc.query(
                    "select gi.id, gi.line_no, gi.account_code from gl_interface gi join ledgers l on l.name = gi.ledger_name"
                            + " left join accounts a on a.ledger_id = l.id and a.code = gi.account_code"
                            + " where gi.batch_id = ? and gi.account_code is not null and a.id is null order by gi.line_no",
                    lineError("UNKNOWN_ACCOUNT", "ACCOUNT_CODE", rs -> "account '" + str(rs, "account_code") + "' is not in the chart of accounts"), batchId));
            out.addAll(jdbc.query(
                    "select gi.id, gi.line_no, gi.account_code from gl_interface gi join ledgers l on l.name = gi.ledger_name"
                            + " join accounts a on a.ledger_id = l.id and a.code = gi.account_code"
                            + " where gi.batch_id = ? and a.enabled = false order by gi.line_no",
                    lineError("ACCOUNT_DISABLED", "ACCOUNT_CODE", rs -> "account '" + str(rs, "account_code") + "' is disabled"), batchId));
            return out;
        });
    }

    @Bean @Order(40)
    ValidationRule periodRules() {
        return rule("PERIOD", batchId -> {
            List<Finding> out = new java.util.ArrayList<>(jdbc.query(
                    "select gi.id, gi.line_no, gi.period_name from gl_interface gi join ledgers l on l.name = gi.ledger_name"
                            + " left join periods p on p.ledger_id = l.id and p.name = gi.period_name"
                            + " where gi.batch_id = ? and gi.period_name is not null and p.id is null order by gi.line_no",
                    lineError("UNKNOWN_PERIOD", "PERIOD_NAME", rs -> "period '" + str(rs, "period_name") + "' is not defined"), batchId));
            out.addAll(jdbc.query(
                    "select gi.id, gi.line_no, gi.period_name from gl_interface gi join ledgers l on l.name = gi.ledger_name"
                            + " join periods p on p.ledger_id = l.id and p.name = gi.period_name"
                            + " where gi.batch_id = ? and p.status <> 'OPEN' order by gi.line_no",
                    lineError("PERIOD_CLOSED", "PERIOD_NAME", rs -> "period '" + str(rs, "period_name") + "' is closed"), batchId));
            out.addAll(jdbc.query(
                    "select gi.id, gi.line_no, gi.period_name, gi.accounting_date from gl_interface gi join ledgers l on l.name = gi.ledger_name"
                            + " join periods p on p.ledger_id = l.id and p.name = gi.period_name"
                            + " where gi.batch_id = ? and gi.accounting_date is not null"
                            + " and (gi.accounting_date < p.start_date or gi.accounting_date > p.end_date) order by gi.line_no",
                    lineError("DATE_OUTSIDE_PERIOD", "ACCOUNTING_DATE",
                            rs -> "accounting date " + str(rs, "accounting_date") + " falls outside period " + str(rs, "period_name")), batchId));
            return out;
        });
    }

    @Bean @Order(50)
    ValidationRule currencyRules() {
        return rule("CURRENCY", batchId -> {
            List<Finding> out = new java.util.ArrayList<>(jdbc.query(
                    "select gi.id, gi.line_no, gi.currency_code from gl_interface gi left join currencies c on c.code = gi.currency_code"
                            + " where gi.batch_id = ? and gi.currency_code is not null and c.code is null order by gi.line_no",
                    lineError("UNKNOWN_CURRENCY", "CURRENCY_CODE", rs -> "currency '" + str(rs, "currency_code") + "' is not enabled"), batchId));
            out.addAll(jdbc.query(
                    "select gi.id, gi.line_no, gi.currency_code, l.currency_code as ledger_ccy from gl_interface gi"
                            + " join ledgers l on l.name = gi.ledger_name join currencies c on c.code = gi.currency_code"
                            + " where gi.batch_id = ? and gi.currency_code <> l.currency_code order by gi.line_no",
                    (rs, i) -> Finding.warning(rs.getLong("id"), rs.getInt("line_no"), "FOREIGN_CURRENCY", "CURRENCY_CODE",
                            "line currency " + rs.getString("currency_code") + " differs from ledger currency " + rs.getString("ledger_ccy")
                                    + "; posted at entered amounts (no conversion)"), batchId));
            return out;
        });
    }

    @Bean @Order(60)
    ValidationRule balancedJournals() {
        return rule("UNBALANCED_JOURNAL", batchId -> jdbc.query(
                "select journal_name, coalesce(sum(entered_dr),0) dr, coalesce(sum(entered_cr),0) cr from gl_interface"
                        + " where batch_id = ? and journal_name is not null group by journal_name"
                        + " having coalesce(sum(entered_dr),0) <> coalesce(sum(entered_cr),0) order by journal_name",
                (rs, i) -> {
                    BigDecimal dr = rs.getBigDecimal("dr"), cr = rs.getBigDecimal("cr");
                    return Finding.batchError("UNBALANCED_JOURNAL", "journal '" + rs.getString("journal_name") + "' debits " + dr
                            + " != credits " + cr + " (difference " + dr.subtract(cr) + ")");
                }, batchId));
    }

    @Bean @Order(70)
    ValidationRule duplicateLines() {
        return rule("DUPLICATE_LINE", batchId -> jdbc.query(
                "select gi.id, gi.line_no from gl_interface gi join ("
                        + " select journal_name, account_code, reference, coalesce(entered_dr,0) dr, coalesce(entered_cr,0) cr, min(line_no) first_line"
                        + " from gl_interface where batch_id = ? and reference is not null"
                        + " group by journal_name, account_code, reference, coalesce(entered_dr,0), coalesce(entered_cr,0) having count(*) > 1) d"
                        + " on d.journal_name = gi.journal_name and d.account_code = gi.account_code and d.reference = gi.reference"
                        + " and coalesce(gi.entered_dr,0) = d.dr and coalesce(gi.entered_cr,0) = d.cr and gi.line_no <> d.first_line"
                        + " where gi.batch_id = ? order by gi.line_no",
                (rs, i) -> Finding.warning(rs.getLong("id"), rs.getInt("line_no"), "DUPLICATE_LINE", "REFERENCE",
                        "identical journal/account/reference/amount appears earlier in the file"), batchId, batchId));
    }
}
