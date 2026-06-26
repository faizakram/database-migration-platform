package com.migration.platform.connector;

import java.util.List;
import java.util.Map;

/**
 * Typed view over the free-form {@code project.config} JSONB, with sensible defaults.
 * These are the knobs the UI migration/CDC wizard (#38) edits.
 */
public record MigrationConfig(
        String topicPrefix,
        String tableIncludeList,
        String snapshotMode,
        DeleteStrategy deleteStrategy,
        String targetSchema,
        List<String> uuidColumns,
        List<String> jsonColumns,
        int tasksMax
) {
    public static MigrationConfig from(Map<String, Object> cfg, String projectName) {
        cfg = cfg == null ? Map.of() : cfg;
        return new MigrationConfig(
                str(cfg, "topicPrefix", sanitize(projectName)),
                str(cfg, "tableIncludeList", "dbo.*"),
                str(cfg, "snapshotMode", "initial"),
                DeleteStrategy.valueOf(str(cfg, "deleteStrategy", "SOFT").toUpperCase()),
                str(cfg, "targetSchema", "public"),
                strList(cfg, "uuidColumns"),
                strList(cfg, "jsonColumns"),
                intVal(cfg, "tasksMax", 1)
        );
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? def : Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Connector names must be DNS-ish; keep it conservative. */
    public static String sanitize(String s) {
        String out = s == null ? "project" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isBlank() ? "project" : out;
    }
}
