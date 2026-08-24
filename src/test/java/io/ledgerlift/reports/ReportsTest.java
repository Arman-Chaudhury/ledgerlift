package io.ledgerlift.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.posting.PostingService;
import io.ledgerlift.template.ParsePolicy;
import io.ledgerlift.validation.ValidationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReportsTest {

    @Autowired ImportService imports;
    @Autowired ValidationService validation;
    @Autowired PostingService posting;
    @Autowired List<ReportDefinition> defs;
    @Autowired ReportRenderer renderer;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long cleanBatch;
    long q2Batch;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_headers");
        jdbc.update("delete from import_errors");
        jdbc.update("delete from gl_interface");
        jdbc.update("delete from import_batches");
        for (String fx : new String[] {"clean_journal.csv", "demo_q2_journals.csv"}) {
            ImportBatch b = imports.upload(Files.readAllBytes(Path.of("src/test/resources/fixtures", fx)), fx, ParsePolicy.STRICT).batch();
            validation.validate(b.id());
            posting.post(b.id());
            if (fx.startsWith("clean")) cleanBatch = b.id(); else q2Batch = b.id();
        }
    }

    ReportDefinition def(String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void trialBalanceNetsToZeroAndMatchesPostedTotals() {
        ReportData tb = def("trial-balance").run(Map.of());
        int net = tb.columns().indexOf("net"), code = tb.columns().indexOf("account_code"), dr = tb.columns().indexOf("debits");
        BigDecimal sum = tb.rows().stream().map(r -> (BigDecimal) r.get(net)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0.00");
        assertThat(tb.rows()).hasSize(18); // every account appears, even with no activity
        BigDecimal salaries = tb.rows().stream().filter(r -> r.get(code).equals("6000")).map(r -> (BigDecimal) r.get(dr)).findFirst().orElseThrow();
        BigDecimal expected = jdbc.queryForObject(
                "select sum(jl.entered_dr) from journal_lines jl join accounts a on a.id = jl.account_id where a.code = '6000'", BigDecimal.class);
        assertThat(salaries).isEqualByComparingTo(expected);
    }

    @Test
    void trialBalancePeriodFilterIsolatesOnePeriod() {
        ReportData mar = def("trial-balance").run(Map.of("period", "Mar-26"));
        int code = mar.columns().indexOf("account_code"), dr = mar.columns().indexOf("debits");
        BigDecimal salariesMar = mar.rows().stream().filter(r -> r.get(code).equals("6000")).map(r -> (BigDecimal) r.get(dr)).findFirst().orElseThrow();
        assertThat(salariesMar).isEqualByComparingTo("125000.00"); // only the clean_journal payroll is in March
        assertThat(mar.parameters()).containsEntry("period", "Mar-26");
    }

    @Test
    void batchSummaryAndErrorSummary() throws Exception {
        ReportData bs = def("batch-summary").run(Map.of());
        assertThat(bs.rows()).hasSize(2);
        int journals = bs.columns().indexOf("journals"), status = bs.columns().indexOf("status");
        assertThat(bs.rows()).allMatch(r -> r.get(status).equals("POSTED"));
        assertThat(bs.rows().stream().mapToLong(r -> ((Number) r.get(journals)).longValue()).sum()).isEqualTo(3 + 144);

        ImportBatch bad = imports.upload(Files.readAllBytes(Path.of("src/test/resources/fixtures", "business_errors.csv")), "biz.csv", ParsePolicy.STRICT).batch();
        validation.validate(bad.id());
        ReportData es = def("error-summary").run(Map.of("batchId", String.valueOf(bad.id())));
        int rule = es.columns().indexOf("rule_code");
        assertThat(es.rows().stream().map(r -> r.get(rule))).contains("UNBALANCED_JOURNAL", "UNKNOWN_ACCOUNT", "FOREIGN_CURRENCY");
    }

    @Test
    void accountActivityRunningBalanceEndsAtTrialBalanceNet() {
        ReportData act = def("account-activity").run(Map.of("account", "1000"));
        assertThat(act.rows()).isNotEmpty();
        BigDecimal last = (BigDecimal) act.rows().get(act.rows().size() - 1).get(act.columns().indexOf("running_balance"));
        ReportData tb = def("trial-balance").run(Map.of());
        BigDecimal net = tb.rows().stream().filter(r -> r.get(0).equals("1000")).map(r -> (BigDecimal) r.get(tb.columns().indexOf("net"))).findFirst().orElseThrow();
        assertThat(last).isEqualByComparingTo(net);
    }

    @Test
    void sameDataModelRendersAsJsonCsvAndHtml() throws Exception {
        mvc.perform(get("/api/v1/reports").header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", Matchers.containsInAnyOrder("account-activity", "batch-summary", "error-summary", "trial-balance")));
        mvc.perform(get("/api/v1/reports/trial-balance").header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0]").value("account_code"))
                .andExpect(jsonPath("$.rows.length()").value(18));
        mvc.perform(get("/api/v1/reports/trial-balance").param("format", "csv").header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(Matchers.startsWith("account_code,account_name,account_type,debits,credits,net,line_count\r\n1000,")));
        mvc.perform(get("/api/v1/reports/trial-balance").param("format", "html").param("period", "Mar-26").header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString("<h1>Trial Balance</h1>")))
                .andExpect(content().string(Matchers.containsString("period=Mar-26")))
                .andExpect(content().string(Matchers.containsString("125,000.00")));
    }

    @Test
    void badRequestsAreExplicit() throws Exception {
        mvc.perform(get("/api/v1/reports/nope").header("X-API-Key", "dev-key")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reports/error-summary").header("X-API-Key", "dev-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("missing required parameter(s): batchId"));
        mvc.perform(get("/api/v1/reports/trial-balance").param("format", "pdf").header("X-API-Key", "dev-key"))
                .andExpect(status().isBadRequest());
    }
}
