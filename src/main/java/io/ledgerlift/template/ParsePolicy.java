package io.ledgerlift.template;

/**
 * STRICT: any parse error rejects the whole file (nothing is staged).
 * LENIENT: rows with parse errors are staged with the unparseable fields
 * left null so validation can report them next to business errors.
 */
public enum ParsePolicy { STRICT, LENIENT }
