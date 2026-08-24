package io.ledgerlift.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ledgerlift.imports.BatchStatus;
import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportBatchRepository;
import io.ledgerlift.imports.ImportError;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.template.ParsePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ValidationServiceTest {

    @Autowired ImportService imports;
    @Autowired ValidationService validation;
    @Autowired ImportBatchRepository repo;
    @Autowired ErrorCorrectionFile corrections;
    @Autowired JdbcTemplate jdbc;

    static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/fixtures", name));
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_headers");
        jdbc.update("delete from import_errors");
        jdbc.update("delete from gl_interface");
        jdbc.update("delete from import_batches");
    }

    @Test
    void cleanFileValidatesWithNoFindingsAndResolvesLedger() throws IOException {
        ImportBatch b = imports.upload(fixture("clean_journal.csv"), "clean.csv", ParsePolicy.STRICT).batch();
        var s = validation.validate(b.id());
        assertThat(s.errors()).isZero();
        assertThat(s.warnings()).isZero();
        assertThat(s.batch().status()).isEqualTo(BatchStatus.VALIDATED);
        assertThat(s.batch().ledgerId()).isEqualTo(1L);
    }

    @Test
    void everyRuleFiresOnTheBusinessErrorsFixture() throws IOException {
        ImportBatch b = imports.upload(fixture("business_errors.csv"), "biz.csv", ParsePolicy.STRICT).batch();
        var s = validation.validate(b.id());
        assertThat(s.batch().status()).isEqualTo(BatchStatus.REJECTED);
        List<ImportError> errors = repo.errors(b.id());
        assertThat(errors).extracting(ImportError::ruleCode).contains(
                "UNKNOWN_ACCOUNT", "ACCOUNT_DISABLED", "PERIOD_CLOSED", "DATE_OUTSIDE_PERIOD", "UNBALANCED_JOURNAL",
                "FOREIGN_CURRENCY", "UNKNOWN_CURRENCY", "DUPLICATE_LINE", "DR_AND_CR", "REQUIRED_FIELD",
                "UNKNOWN_LEDGER", "MIXED_LEDGERS", "UNKNOWN_PERIOD");
        // line-level findings point at the right lines
        assertThat(errors.stream().filter(e -> e.ruleCode().equals("UNKNOWN_ACCOUNT")).map(ImportError::lineNo)).containsExactly(1);
        assertThat(errors.stream().filter(e -> e.ruleCode().equals("DUPLICATE_LINE")).map(ImportError::lineNo)).containsExactly(16);
        assertThat(errors.stream().filter(e -> e.ruleCode().equals("DATE_OUTSIDE_PERIOD")).map(ImportError::lineNo)).containsExactly(7);
        // batch-level findings have no line
        assertThat(errors.stream().filter(e -> e.ruleCode().equals("UNBALANCED_JOURNAL")).map(ImportError::lineNo)).containsOnlyNulls();
        assertThat(errors.stream().filter(e -> e.ruleCode().equals("UNBALANCED_JOURNAL")).map(ImportError::message))
                .anySatisfy(m -> assertThat(m).contains("J-UNBAL").contains("difference 10.00"));
        // warnings don't count as errors
        assertThat(s.warnings()).isEqualTo(3); // 2x FOREIGN_CURRENCY + 1x DUPLICATE_LINE
        assertThat(s.errors()).isEqualTo(errors.size() - 3);
        // ledger unresolved because the file mixes ledgers
        assertThat(s.batch().ledgerId()).isNull();
    }

    @Test
    void revalidationReplacesBusinessFindingsButKeepsParseErrors() throws IOException {
        ImportBatch b = imports.upload(fixture("parse_errors.csv"), "p.csv", ParsePolicy.LENIENT).batch();
        validation.validate(b.id());
        int afterFirst = repo.errors(b.id()).size();
        validation.validate(b.id());
        assertThat(repo.errors(b.id())).hasSize(afterFirst);
        assertThat(repo.errors(b.id()).stream().filter(e -> e.ruleCode().equals("PARSE"))).hasSize(5);
    }

    @Test
    void parseRejectedBatchCannotBeValidated() throws IOException {
        ImportBatch b = imports.upload(fixture("parse_errors.csv"), "p.csv", ParsePolicy.STRICT).batch();
        assertThatThrownBy(() -> validation.validate(b.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("no staged rows");
    }

    @Test
    void errorCorrectionFileRoundTrips() throws IOException {
        ImportBatch b = imports.upload(fixture("business_errors.csv"), "biz.csv", ParsePolicy.STRICT).batch();
        validation.validate(b.id());
        String csv = corrections.build(b.id());
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo(String.join(",", io.ledgerlift.template.TemplateParser.COLUMNS) + ",ERRORS");
        // only failing lines are included; clean lines (e.g. the cash side of J-UNKNOWN-ACCT) are not
        assertThat(csv).contains("ERROR UNKNOWN_ACCOUNT: account '1234'").contains("# BATCH ERROR UNBALANCED_JOURNAL");
        long dataLines = java.util.Arrays.stream(lines).skip(1).filter(l -> !l.startsWith("#")).count();
        assertThat(dataLines).isLessThan(24).isGreaterThan(5);

        // The user "fixes" the file: keep only the first journal and repair its account code.
        String fixed = lines[0] + "\r\n"
                + java.util.Arrays.stream(lines).skip(1).filter(l -> l.contains("J-UNKNOWN-ACCT")).findFirst().orElseThrow()
                        .replace(",1234,", ",6000,") + "\r\n"
                + "US Primary,Mar-26,J-UNKNOWN-ACCT,cash,1000,USD,,100.00,2026-03-05,A1,\r\n";
        ImportBatch b2 = imports.upload(fixed.getBytes(StandardCharsets.UTF_8), "biz-fixed.csv", ParsePolicy.STRICT).batch();
        assertThat(b2.status()).isEqualTo(BatchStatus.LOADED);
        assertThat(b2.rowCount()).isEqualTo(2);
        var s = validation.validate(b2.id());
        assertThat(s.errors()).isZero();
        assertThat(s.batch().status()).isEqualTo(BatchStatus.VALIDATED);
    }
}
