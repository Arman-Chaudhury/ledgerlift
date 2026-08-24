package io.ledgerlift.validation;

import java.util.List;

/**
 * A rule runs against every staged row of one batch and returns its findings.
 * Rules are set-based (one SQL statement per rule, not one per row) so a
 * 100k-line file validates in a handful of queries.
 */
public interface ValidationRule {

    String code();

    List<Finding> apply(long batchId);
}
