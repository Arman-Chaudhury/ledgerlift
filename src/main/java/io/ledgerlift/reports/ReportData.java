package io.ledgerlift.reports;

import java.util.List;
import java.util.Map;

/** The output of a report's data model: ordered columns and rows, independent of presentation. */
public record ReportData(String name, String title, Map<String, String> parameters, List<String> columns,
                         List<List<Object>> rows) {
}
