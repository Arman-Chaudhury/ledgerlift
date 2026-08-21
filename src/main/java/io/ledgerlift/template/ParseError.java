package io.ledgerlift.template;

/** A line-level problem found while parsing the template. Never discarded. */
public record ParseError(int lineNo, String column, String message, String rawLine) {
}
