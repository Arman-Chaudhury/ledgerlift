package io.ledgerlift.imports;

import static org.assertj.core.api.Assertions.assertThat;

import io.ledgerlift.template.ParsePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ImportServiceTest {

    @Autowired ImportService service;
    @Autowired ImportBatchRepository repo;
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
    void cleanFileIsStagedRowForRow() throws IOException {
        var out = service.upload(fixture("clean_journal.csv"), "clean_journal.csv", ParsePolicy.STRICT);
        assertThat(out.duplicate()).isFalse();
        ImportBatch b = out.batch();
        assertThat(b.status()).isEqualTo(BatchStatus.LOADED);
        assertThat(b.rowCount()).isEqualTo(6);
        assertThat(b.parseErrors()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from gl_interface where batch_id = ?", Integer.class, b.id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("select sum(entered_dr) from gl_interface where batch_id = ?", java.math.BigDecimal.class, b.id()))
                .isEqualByComparingTo("453750.75");
    }

    @Test
    void sameBytesTwiceReturnsSameBatch() throws IOException {
        var first = service.upload(fixture("clean_journal.csv"), "a.csv", ParsePolicy.STRICT);
        var second = service.upload(fixture("clean_journal.csv"), "renamed.csv", ParsePolicy.STRICT);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.batch().id()).isEqualTo(first.batch().id());
        assertThat(repo.findAll()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from gl_interface", Integer.class)).isEqualTo(6);
    }

    @Test
    void strictRejectsAtParseAndStagesNothing() throws IOException {
        ImportBatch b = service.upload(fixture("parse_errors.csv"), "parse_errors.csv", ParsePolicy.STRICT).batch();
        assertThat(b.status()).isEqualTo(BatchStatus.REJECTED);
        assertThat(b.rowCount()).isZero();
        assertThat(b.parseErrors()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from gl_interface where batch_id = ?", Integer.class, b.id())).isZero();
        assertThat(repo.errors(b.id())).hasSize(5).allMatch(e -> e.ruleCode().equals("PARSE") && e.interfaceId() == null);
    }

    @Test
    void lenientStagesRowsAndLinksParseErrorsToStagedRows() throws IOException {
        ImportBatch b = service.upload(fixture("parse_errors.csv"), "parse_errors.csv", ParsePolicy.LENIENT).batch();
        assertThat(b.status()).isEqualTo(BatchStatus.LOADED);
        assertThat(b.rowCount()).isEqualTo(6);
        assertThat(b.parseErrors()).isEqualTo(5);
        var errors = repo.errors(b.id());
        assertThat(errors).hasSize(5).allMatch(e -> e.interfaceId() != null, "linked to a staged row");
        assertThat(errors).extracting(ImportError::lineNo).containsExactly(2, 3, 4, 5, 6);
    }
}
