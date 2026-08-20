package com.kavin.auditlog.web;

import java.util.List;

/** Minimal RFC 4180-ish CSV writer: quotes a field whenever it contains a comma, quote, or newline. */
final class CsvWriter {

    private CsvWriter() {
    }

    static String row(List<?> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(String.valueOf(fields.get(i))));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
