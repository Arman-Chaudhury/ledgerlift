package io.ledgerlift.reports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** The report catalogue. Each bean is one data model. */
@Configuration
public class Reports {

    private final JdbcTemplate jdbc;

    public Reports(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private ReportData table(String name, String title, Map<String, String> params, String sql, Object... args) {
        List<String> cols = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            var md = rs.getMetaData();
            if (cols.isEmpty()) {
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i).toLowerCase());
            }
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object v = rs.getObject(i);
                if (v instanceof java.sql.Date d) v = d.toLocalDate();
                if (v instanceof java.sql.Timestamp t) v = t.toInstant();
                row.add(v);
            }
            rows.add(row);
        }, args);
        return new ReportData(name, title, params, cols, rows);
    }

    private static ReportDefinition def(String name, String desc, Map<String, Boolean> params,
                                        java.util.function.Function<Map<String, String>, ReportData> fn) {
        return new ReportDefinition() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public Map<String, Boolean> parameters() { return params; }
            @Override public ReportData run(Map<String, String> p) { return fn.apply(p); }
        };
    }

    private static Map<String, Boolean> params(Object... kv) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Boolean) kv[i + 1]);
        return m;
    }

    private static long ledgerId(Map<String, String> p) {
        return Long.parseLong(p.getOrDefault("ledgerId", "1"));
    }

    @Bean
    ReportDefinition trialBalance() {
        return def("trial-balance", "Posted debits, credits and net balance per account; optionally one period.",
                params("ledgerId", false, "period", false), p -> {
                    long ledger = ledgerId(p);
                    String period = p.get("period");
                    Map<String, String> used = new LinkedHashMap<>();
                    used.put("ledgerId", String.valueOf(ledger));
                    if (period != null && !period.isBlank()) used.put("period", period);
                    String sql = "select a.code as account_code, a.name as account_name, a.account_type,"
                            + " cast(coalesce(sum(jl.entered_dr),0) as numeric(20,2)) as debits, cast(coalesce(sum(jl.entered_cr),0) as numeric(20,2)) as credits,"
                            + " cast(coalesce(sum(jl.entered_dr),0) - coalesce(sum(jl.entered_cr),0) as numeric(20,2)) as net, count(jl.id) as line_count"
                            + " from accounts a left join journal_lines jl on jl.account_id = a.id"
                            + " where a.ledger_id = ? group by a.code, a.name, a.account_type order by a.code";
                    if (period != null && !period.isBlank()) {
                        // period filter belongs inside the joined set, not the WHERE, so inactive accounts still appear
                        sql = "select a.code as account_code, a.name as account_name, a.account_type,"
                                + " cast(coalesce(sum(x.entered_dr),0) as numeric(20,2)) as debits, cast(coalesce(sum(x.entered_cr),0) as numeric(20,2)) as credits,"
                                + " cast(coalesce(sum(x.entered_dr),0) - coalesce(sum(x.entered_cr),0) as numeric(20,2)) as net, count(x.id) as line_count"
                                + " from accounts a left join ("
                                + "   select jl.* from journal_lines jl join journal_headers h on h.id = jl.header_id"
                                + "   join periods pd on pd.id = h.period_id where pd.name = ?) x on x.account_id = a.id"
                                + " where a.ledger_id = ? group by a.code, a.name, a.account_type order by a.code";
                        return table("trial-balance", "Trial Balance", used, sql, period, ledger);
                    }
                    return table("trial-balance", "Trial Balance", used, sql, ledger);
                });
    }

    @Bean
    ReportDefinition batchSummary() {
        return def("batch-summary", "One row per import batch: status, rows, errors, posted journals and totals.",
                params(), p -> table("batch-summary", "Import Batch Summary", Map.of(),
                        "select b.id as batch_id, b.source_name, b.status, b.row_count, b.parse_errors, b.error_count,"
                                + " (select count(*) from journal_headers h where h.batch_id = b.id) as journals,"
                                + " (select cast(coalesce(sum(h.total_dr),0) as numeric(20,2)) from journal_headers h where h.batch_id = b.id) as posted_debits,"
                                + " (select cast(coalesce(sum(h.total_cr),0) as numeric(20,2)) from journal_headers h where h.batch_id = b.id) as posted_credits,"
                                + " b.created_at, b.posted_at from import_batches b order by b.id"));
    }

    @Bean
    ReportDefinition errorSummary() {
        return def("error-summary", "Validation findings for one batch grouped by rule and severity.",
                params("batchId", true), p -> table("error-summary", "Validation Error Summary",
                        Map.of("batchId", p.get("batchId")),
                        "select rule_code, severity, count(*) as findings, min(line_no) as first_line, max(line_no) as last_line"
                                + " from import_errors where batch_id = ? group by rule_code, severity order by severity, findings desc, rule_code",
                        Long.parseLong(p.get("batchId"))));
    }

    @Bean
    ReportDefinition accountActivity() {
        return def("account-activity", "Posted lines for one account in date order with a running balance.",
                params("account", true, "ledgerId", false), p -> {
                    long ledger = ledgerId(p);
                    ReportData raw = table("account-activity", "Account Activity",
                            Map.of("account", p.get("account"), "ledgerId", String.valueOf(ledger)),
                            "select jl.accounting_date, pd.name as period, h.journal_name, jl.line_no, jl.description, jl.reference,"
                                    + " jl.entered_dr as debit, jl.entered_cr as credit, h.batch_id"
                                    + " from journal_lines jl join journal_headers h on h.id = jl.header_id"
                                    + " join periods pd on pd.id = h.period_id join accounts a on a.id = jl.account_id"
                                    + " where a.ledger_id = ? and a.code = ? order by jl.accounting_date, h.id, jl.line_no",
                            ledger, p.get("account"));
                    List<String> cols = new ArrayList<>(raw.columns());
                    cols.add("running_balance");
                    java.math.BigDecimal bal = java.math.BigDecimal.ZERO;
                    List<List<Object>> rows = new ArrayList<>();
                    int di = raw.columns().indexOf("debit"), ci = raw.columns().indexOf("credit");
                    for (List<Object> r : raw.rows()) {
                        bal = bal.add((java.math.BigDecimal) r.get(di)).subtract((java.math.BigDecimal) r.get(ci));
                        List<Object> row = new ArrayList<>(r);
                        row.add(bal);
                        rows.add(row);
                    }
                    return new ReportData(raw.name(), raw.title(), raw.parameters(), cols, rows);
                });
    }
}
