package io.ledgerlift.reports;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Layouts. One data model, three outputs. */
@Component
public class ReportRenderer {

    private final TemplateEngine thymeleaf;

    public ReportRenderer(TemplateEngine thymeleaf) {
        this.thymeleaf = thymeleaf;
    }

    public String csv(ReportData d) {
        StringBuilder sb = new StringBuilder(String.join(",", d.columns())).append("\r\n");
        for (List<Object> r : d.rows()) {
            List<String> cells = new ArrayList<>();
            for (Object v : r) cells.add(cell(v));
            sb.append(String.join(",", cells)).append("\r\n");
        }
        return sb.toString();
    }

    public String html(ReportData d) {
        Context ctx = new Context();
        ctx.setVariable("report", d);
        return thymeleaf.process("report", ctx);
    }

    static String cell(Object v) {
        if (v == null) return "";
        String s = v instanceof java.math.BigDecimal b ? b.toPlainString() : v.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
