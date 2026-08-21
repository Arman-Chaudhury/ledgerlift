package io.ledgerlift.posting;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Set-based posting in three statements; no per-row Java loop. */
@Component
public class JdbcPostingEngine implements PostingEngine {

    private final JdbcTemplate jdbc;

    public JdbcPostingEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() { return "java"; }

    @Override
    @Transactional
    public void post(long batchId) {
        Timestamp now = Timestamp.from(Instant.now());
        int headers = jdbc.update(
                "insert into journal_headers (batch_id, ledger_id, period_id, journal_name, currency_code, total_dr, total_cr, posted_at)"
                        + " select gi.batch_id, l.id, p.id, gi.journal_name, min(gi.currency_code),"
                        + " coalesce(sum(gi.entered_dr),0), coalesce(sum(gi.entered_cr),0), ?"
                        + " from gl_interface gi"
                        + " join ledgers l on l.name = gi.ledger_name"
                        + " join periods p on p.ledger_id = l.id and p.name = gi.period_name"
                        + " where gi.batch_id = ?"
                        + " group by gi.batch_id, l.id, p.id, gi.journal_name",
                now, batchId);
        int lines = jdbc.update(
                "insert into journal_lines (header_id, line_no, account_id, entered_dr, entered_cr, accounting_date, description, reference)"
                        + " select h.id, gi.line_no, a.id, coalesce(gi.entered_dr,0), coalesce(gi.entered_cr,0), gi.accounting_date,"
                        + " gi.line_description, gi.reference"
                        + " from gl_interface gi"
                        + " join journal_headers h on h.batch_id = gi.batch_id and h.journal_name = gi.journal_name"
                        + " join accounts a on a.ledger_id = h.ledger_id and a.code = gi.account_code"
                        + " where gi.batch_id = ?",
                batchId);
        Integer staged = jdbc.queryForObject("select count(*) from gl_interface where batch_id = ?", Integer.class, batchId);
        if (staged == null || lines != staged) {
            throw new IllegalStateException("posting batch " + batchId + ": " + lines + " lines posted for " + staged
                    + " staged rows (" + headers + " headers) — validation state is stale, re-validate");
        }
        jdbc.update("update import_batches set status = 'POSTED', posted_at = ?, updated_at = ? where id = ?", now, now, batchId);
    }
}
