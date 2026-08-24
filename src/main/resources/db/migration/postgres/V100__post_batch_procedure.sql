-- PostgreSQL-only: stored-procedure posting path. Mirrors JdbcPostingEngine
-- statement for statement; PostingEquivalenceIT asserts the two agree.
CREATE OR REPLACE PROCEDURE ll_post_batch(p_batch_id BIGINT)
LANGUAGE plpgsql
AS $$
DECLARE
    v_status   TEXT;
    v_staged   INTEGER;
    v_headers  INTEGER;
    v_lines    INTEGER;
    v_now      TIMESTAMP := now();
BEGIN
    SELECT status INTO v_status FROM import_batches WHERE id = p_batch_id FOR UPDATE;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'import batch % not found', p_batch_id;
    END IF;
    IF v_status <> 'VALIDATED' THEN
        RAISE EXCEPTION 'batch % is %; only VALIDATED batches can be posted', p_batch_id, v_status;
    END IF;

    INSERT INTO journal_headers (batch_id, ledger_id, period_id, journal_name, currency_code, total_dr, total_cr, posted_at)
    SELECT gi.batch_id, l.id, p.id, gi.journal_name, MIN(gi.currency_code),
           COALESCE(SUM(gi.entered_dr), 0), COALESCE(SUM(gi.entered_cr), 0), v_now
    FROM gl_interface gi
    JOIN ledgers l ON l.name = gi.ledger_name
    JOIN periods p ON p.ledger_id = l.id AND p.name = gi.period_name
    WHERE gi.batch_id = p_batch_id
    GROUP BY gi.batch_id, l.id, p.id, gi.journal_name;
    GET DIAGNOSTICS v_headers = ROW_COUNT;

    INSERT INTO journal_lines (header_id, line_no, account_id, entered_dr, entered_cr, accounting_date, description, reference)
    SELECT h.id, gi.line_no, a.id, COALESCE(gi.entered_dr, 0), COALESCE(gi.entered_cr, 0), gi.accounting_date,
           gi.line_description, gi.reference
    FROM gl_interface gi
    JOIN journal_headers h ON h.batch_id = gi.batch_id AND h.journal_name = gi.journal_name
    JOIN accounts a ON a.ledger_id = h.ledger_id AND a.code = gi.account_code
    WHERE gi.batch_id = p_batch_id;
    GET DIAGNOSTICS v_lines = ROW_COUNT;

    SELECT COUNT(*) INTO v_staged FROM gl_interface WHERE batch_id = p_batch_id;
    IF v_lines <> v_staged THEN
        RAISE EXCEPTION 'posting batch %: % lines posted for % staged rows (% headers) - validation state is stale, re-validate',
            p_batch_id, v_lines, v_staged, v_headers;
    END IF;

    UPDATE import_batches SET status = 'POSTED', posted_at = v_now, updated_at = v_now WHERE id = p_batch_id;
END;
$$;
