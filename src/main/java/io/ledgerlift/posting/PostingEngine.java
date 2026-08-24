package io.ledgerlift.posting;

/**
 * Moves a VALIDATED batch from the interface table into journal_headers /
 * journal_lines in one transaction and marks the batch POSTED. Two
 * implementations exist on purpose: a portable JDBC one and a PL/pgSQL
 * stored procedure; {@code PostingEquivalenceIT} proves they agree.
 */
public interface PostingEngine {

    String name();

    void post(long batchId);
}
