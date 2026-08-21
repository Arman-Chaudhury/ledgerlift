package io.ledgerlift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SchemaSmokeTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesSchemaAndSeedsReferenceData() {
        Integer accounts = jdbc.queryForObject("select count(*) from accounts", Integer.class);
        Integer periods = jdbc.queryForObject("select count(*) from periods where status = 'OPEN'", Integer.class);
        String ledger = jdbc.queryForObject("select name from ledgers where id = 1", String.class);
        assertThat(accounts).isEqualTo(18);
        assertThat(periods).isEqualTo(10);
        assertThat(ledger).isEqualTo("US Primary");
    }

    @Test
    void coreTablesStartEmpty() {
        for (String t : new String[] {"import_batches", "gl_interface", "import_errors", "journal_headers", "journal_lines"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + t, Integer.class)).as(t).isZero();
        }
    }
}
