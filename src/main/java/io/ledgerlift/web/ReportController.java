package io.ledgerlift.web;

import io.ledgerlift.reports.ReportData;
import io.ledgerlift.reports.ReportDefinition;
import io.ledgerlift.reports.ReportNotFoundException;
import io.ledgerlift.reports.ReportRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Data-model + layout reports over the posted ledger")
public class ReportController {

    private final Map<String, ReportDefinition> catalogue;
    private final ReportRenderer renderer;

    public ReportController(List<ReportDefinition> defs, ReportRenderer renderer) {
        this.catalogue = new java.util.TreeMap<>();
        defs.forEach(d -> catalogue.put(d.name(), d));
        this.renderer = renderer;
    }

    public record ReportInfo(String name, String description, Map<String, Boolean> parameters) {}

    @GetMapping
    public List<ReportInfo> list() {
        return catalogue.values().stream().map(d -> new ReportInfo(d.name(), d.description(), d.parameters())).toList();
    }

    @Operation(summary = "Run a report. format=json|csv|html; other query params feed the report's data model.")
    @GetMapping("/{name}")
    public ResponseEntity<?> run(@PathVariable String name,
                                 @RequestParam(value = "format", defaultValue = "json") String format,
                                 @RequestParam Map<String, String> params) {
        ReportDefinition def = catalogue.get(name);
        if (def == null) throw new ReportNotFoundException(name);
        List<String> missing = def.missing(params);
        if (!missing.isEmpty()) throw new IllegalArgumentException("missing required parameter(s): " + String.join(", ", missing));
        ReportData data = def.run(params);
        return switch (format.toLowerCase()) {
            case "json" -> ResponseEntity.ok(data);
            case "csv" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
                    .body(renderer.csv(data));
            case "html" -> ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(renderer.html(data));
            default -> throw new IllegalArgumentException("unsupported format '" + format + "' (json, csv, html)");
        };
    }
}
