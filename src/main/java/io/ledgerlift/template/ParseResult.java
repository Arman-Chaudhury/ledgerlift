package io.ledgerlift.template;

import java.util.List;

public record ParseResult(List<GlInterfaceRow> rows, List<ParseError> errors, ParsePolicy policy) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Under STRICT, a single parse error means nothing may be staged. */
    public boolean rejected() {
        return policy == ParsePolicy.STRICT && hasErrors();
    }
}
