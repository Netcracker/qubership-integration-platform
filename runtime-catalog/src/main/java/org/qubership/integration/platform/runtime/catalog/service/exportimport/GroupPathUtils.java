package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared helpers for the {@code metaInfo.group} path used to place chains into folders.
 * The forbidden-character set mirrors the canonical {@code group} pattern defined in
 * {@code top-level-entity-properties.schema.yaml} (qip-schemas).
 */
public final class GroupPathUtils {

    public static final String SEPARATOR = "/";

    // Characters not allowed inside a single group segment; replaced with '-'.
    private static final Pattern FORBIDDEN_SEGMENT_CHARS = Pattern.compile("[/:*?\"<>|,;\\\\]");

    private GroupPathUtils() {
    }

    public static String sanitizeSegment(String name) {
        return name == null ? "" : FORBIDDEN_SEGMENT_CHARS.matcher(name).replaceAll("-");
    }

    public static List<String> parseSegments(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        return Arrays.stream(group.split(SEPARATOR))
                .filter(segment -> !segment.isBlank())
                .toList();
    }
}
