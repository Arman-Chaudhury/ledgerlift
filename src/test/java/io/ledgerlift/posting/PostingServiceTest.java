package io.ledgerlift.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ledgerlift.imports.BatchStatus;
import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.template.ParsePolicy;
import io.ledgerlift.validation.ValidationService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PostingServiceTest {

    @Autowired ImportService imports;
    @Autowired ValidationService validation;
    @Autowired PostingService posting;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${ledgerlift.posting.engine}") String defaultEngine;

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
    void postsValidatedBatchIntoBalancedJournals() throws IOException {
        ImportBatch b = imports.upload(fixture("clean_journal.csv"), "clean.csv", ParsePolicy.STRICT).batch();
        validation.validate(b.id());
        var r = posting.post(b.id());
        assertThat(r.engine()).isEqualTo(defaultEngine); // java on H2, procedure under the postgres profile
        assertThat(r.journals()).isEqualTo(3);
        assertThat(r.lines()).isEqualTo(6);
        assertThat(r.batch().status()).isEqualTo(BatchStatus.POSTED);
        assertThat(r.batch().postedAt()).isNotNull();
        // every header balances and totals match the file
        assertThat(jdbc.queryForObject("select count(*) from journal_headers where total_dr <> total_cr", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select sum(total_dr) from journal_headers", BigDecimal.class)).isEqualByComparingTo("453750.75");
        // lines resolved to real account ids and the right period
        assertThat(jdbc.queryForObject(
                "select count(*) from journal_lines jl join accounts a on a.id = jl.account_id where a.code = '1000' and jl.entered_cr > 0",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(distinct p.name) from journal_headers h join periods p on p.id = h.period_id", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyValidatedBatchesPostAndNeverTwice() throws IOException {
        ImportBatch b = imports.upload(fixture("clean_journal.csv"), "clean.csv", ParsePolicy.STRICT).batch();
        assertThatThrownBy(() -> posting.post(b.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("LOADED");
        validation.validate(b.id());
        posting.post(b.id());
        assertThatThrownBy(() -> posting.post(b.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("POSTED");
        assertThat(jdbc.queryForObject("select count(*) from journal_lines", Integer.class)).isEqualTo(6);
        assertThatThrownBy(() -> validation.validate(b.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("POSTED");
    }

    @Test
    void rejectedBatchCannotPost() throws IOException {
        ImportBatch b = imports.upload(fixture("business_errors.csv"), "biz.csv", ParsePolicy.STRICT).batch();
        validation.validate(b.id());
        assertThatThrownBy(() -> posting.post(b.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from journal_headers", Integer.class)).isZero();
    }

    @Test
    void unknownEngineIsRejected() throws IOException {
        ImportBatch b = imports.upload(fixture("clean_journal.csv"), "clean.csv", ParsePolicy.STRICT).batch();
        validation.validate(b.id());
        assertThatThrownBy(() -> posting.post(b.id(), "cobol")).isInstanceOf(IllegalArgumentException.class);
    }
}
