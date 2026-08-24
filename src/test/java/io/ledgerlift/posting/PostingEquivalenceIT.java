package io.ledgerlift.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ledgerlift.imports.BatchStatus;
import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.template.ParsePolicy;
import io.ledgerlift.validation.ValidationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs only against PostgreSQL (CI service container). Posts the same file
 * once through the Java engine and once through the PL/pgSQL procedure and
 * asserts the ledger rows are identical column for column.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = "postgres")
class PostingEquivalenceIT {

    @Autowired ImportService imports;
    @Autowired ValidationService validation;
    @Autowired PostingService posting;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_headers");
        jdbc.update("delete from import_errors");
        jdbc.update("delete from gl_interface");
        jdbc.update("delete from import_batches");
    }

    private static final String[] FIXTURES = {"clean_journal.csv", "demo_q2_journals.csv"};

    @Test
    void javaAndProcedureEnginesProduceIdenticalLedgerRows() throws IOException {
        for (String fx : FIXTURES) {
            clean();
            byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures", fx));
            // second copy differs by a comment line so the checksum idempotency does not collapse them
            byte[] bytes2 = (new String(bytes, StandardCharsets.UTF_8) + "\n# engine=procedure\n").getBytes(StandardCharsets.UTF_8);

            ImportBatch a = imports.upload(bytes, fx, ParsePolicy.STRICT).batch();
            ImportBatch b = imports.upload(bytes2, fx, ParsePolicy.STRICT).batch();
            validation.validate(a.id());
            validation.validate(b.id());
            var ra = posting.post(a.id(), "java");
            var rb = posting.post(b.id(), "procedure");
            assertThat(rb.engine()).isEqualTo("procedure");
            assertThat(rb.batch().status()).isEqualTo(BatchStatus.POSTED);
            assertThat(rb.journals()).isEqualTo(ra.journals());
            assertThat(rb.lines()).isEqualTo(ra.lines());

            List<Map<String, Object>> ha = headers(a.id()), hb = headers(b.id());
            List<Map<String, Object>> la = lines(a.id()), lb = lines(b.id());
            assertThat(hb).as("journal_headers for " + fx).isEqualTo(ha);
            assertThat(lb).as("journal_lines for " + fx).isEqualTo(la);
            assertThat(la).isNotEmpty();
        }
    }

    @Test
    void procedureRefusesNonValidatedBatch() throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/fixtures", "clean_journal.csv"));
        ImportBatch a = imports.upload(bytes, "x.csv", ParsePolicy.STRICT).batch();
        // bypass the service guard to prove the procedure guards itself
        assertThatThrownBy(() -> jdbc.update("call ll_post_batch(?)", a.id()))
                .hasMessageContaining("only VALIDATED batches can be posted");
        assertThat(jdbc.queryForObject("select count(*) from journal_headers", Integer.class)).isZero();
    }

    private List<Map<String, Object>> headers(long batchId) {
        return jdbc.queryForList(
                "select ledger_id, period_id, journal_name, currency_code, total_dr, total_cr from journal_headers"
                        + " where batch_id = ? order by journal_name", batchId);
    }

    private List<Map<String, Object>> lines(long batchId) {
        return jdbc.queryForList(
                "select h.journal_name, jl.line_no, jl.account_id, jl.entered_dr, jl.entered_cr, jl.accounting_date, jl.description, jl.reference"
                        + " from journal_lines jl join journal_headers h on h.id = jl.header_id where h.batch_id = ?"
                        + " order by h.journal_name, jl.line_no", batchId);
    }
}
