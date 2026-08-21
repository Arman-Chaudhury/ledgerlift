package io.ledgerlift.posting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Delegates to the PL/pgSQL procedure {@code ll_post_batch} (PostgreSQL profile only). */
@Component
public class ProcedurePostingEngine implements PostingEngine {

    private final JdbcTemplate jdbc;

    public ProcedurePostingEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() { return "procedure"; }

    @Override
    @Transactional
    public void post(long batchId) {
        jdbc.update("call ll_post_batch(?)", batchId);
    }
}
