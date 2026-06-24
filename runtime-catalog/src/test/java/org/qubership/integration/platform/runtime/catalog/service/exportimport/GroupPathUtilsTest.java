package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupPathUtilsTest {

    @DisplayName("sanitizeSegment should keep a clean segment unchanged")
    @Test
    void shouldKeepCleanSegment() {
        assertEquals("My Folder 1", GroupPathUtils.sanitizeSegment("My Folder 1"));
    }

    @DisplayName("sanitizeSegment should return an empty string for null")
    @Test
    void shouldReturnEmptyStringForNull() {
        assertEquals("", GroupPathUtils.sanitizeSegment(null));
    }

    @DisplayName("sanitizeSegment should replace every forbidden character with '-'")
    @Test
    void shouldReplaceForbiddenCharacters() {
        assertEquals("a-b", GroupPathUtils.sanitizeSegment("a:b"));
        assertEquals("a-b", GroupPathUtils.sanitizeSegment("a/b"));
        // Every forbidden char: / : * ? " < > | , ; \
        assertEquals(
                "a" + "-".repeat(11) + "b",
                GroupPathUtils.sanitizeSegment("a/:*?\"<>|,;\\b"));
    }

    @DisplayName("parseSegments should split a path into non-blank segments")
    @Test
    void shouldSplitPathIntoSegments() {
        assertEquals(List.of("a", "b", "c"), GroupPathUtils.parseSegments("a/b/c"));
    }

    @DisplayName("parseSegments should drop leading, trailing and repeated separators")
    @Test
    void shouldDropEmptySegments() {
        assertEquals(List.of("a", "b"), GroupPathUtils.parseSegments("/a//b/"));
    }

    @DisplayName("parseSegments should return an empty list for null or blank input")
    @Test
    void shouldReturnEmptyListForBlankInput() {
        assertEquals(List.of(), GroupPathUtils.parseSegments(null));
        assertEquals(List.of(), GroupPathUtils.parseSegments(""));
        assertEquals(List.of(), GroupPathUtils.parseSegments("   "));
    }
}
