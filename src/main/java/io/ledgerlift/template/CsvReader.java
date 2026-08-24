package io.ledgerlift.template;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, CRLF/LF, embedded newlines. */
final class CsvReader {

    record Record(int physicalLine, List<String> fields, String raw) {}

    private final BufferedReader in;
    private int physicalLine = 0;

    CsvReader(Reader reader) {
        this.in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
    }

    /** @return next record or null at EOF. */
    Record next() throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        physicalLine++;
        int startLine = physicalLine;
        StringBuilder raw = new StringBuilder(line);
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (true) {
            if (i >= line.length()) {
                if (quoted) {
                    String more = in.readLine();
                    if (more == null) break; // unterminated quote: take what we have
                    physicalLine++;
                    raw.append('\n').append(more);
                    field.append('\n');
                    line = more;
                    i = 0;
                    continue;
                }
                break;
            }
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
            } else {
                if (c == '"' && field.isEmpty()) {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
                i++;
            }
        }
        fields.add(field.toString());
        return new Record(startLine, fields, raw.toString());
    }
}
