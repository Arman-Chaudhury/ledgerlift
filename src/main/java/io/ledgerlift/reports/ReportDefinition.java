package io.ledgerlift.reports;

import java.util.List;
import java.util.Map;

/**
 * A report = a data model (SQL, parameterised) + any template. Mirrors the
 * BI Publisher split: the data model here, layouts in {@link ReportRenderer}.
 */
public interface ReportDefinition {

    String name();

    String description();

    /** Accepted query parameters and whether each is required. */
    Map<String, Boolean> parameters();

    ReportData run(Map<String, String> params);

    default List<String> missing(Map<String, String> params) {
        return parameters().entrySet().stream().filter(Map.Entry::getValue)
                .map(Map.Entry::getKey).filter(k -> params.get(k) == null || params.get(k).isBlank()).toList();
    }
}
