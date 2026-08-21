package io.ledgerlift.reports;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String name) { super("report '" + name + "' not found"); }
}
