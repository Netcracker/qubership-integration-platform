package org.qubership.integration.platform.engine.service;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MergedVariablesMapTest {

    @Test
    void shouldMaskSecretValuesWhenConvertedToString() {
        MergedVariablesMap<String, String> variables = new MergedVariablesMap<>();
        variables.put("public", "plain");
        variables.putAll(Map.of("secret", "hidden"), true);

        String result = variables.toString();

        assertTrue(result.contains("public=plain"));
        assertTrue(result.contains("secret=***"));
        assertFalse(result.contains("secret=hidden"));
    }

    @Test
    void shouldKeepSecretMarkersWhenMergedFromAnotherMergedVariablesMap() {
        MergedVariablesMap<String, String> source = new MergedVariablesMap<>();
        source.putAll(Map.of("secret", "hidden"), true);
        MergedVariablesMap<String, String> target = new MergedVariablesMap<>();

        target.putAll(source);

        assertEquals("hidden", target.get("secret"));
        assertTrue(target.toString().contains("secret=***"));
    }

    @Test
    void shouldClearSecretMarkerWhenEntryRemoved() {
        MergedVariablesMap<String, String> variables = new MergedVariablesMap<>();
        variables.putAll(Map.of("secret", "hidden"), true);

        assertEquals("hidden", variables.remove("secret"));
        variables.put("secret", "visible");

        assertTrue(variables.toString().contains("secret=visible"));
    }

    @Test
    void shouldClearSecretMarkerWhenEntryRemovedByMatchingValue() {
        MergedVariablesMap<String, String> variables = new MergedVariablesMap<>();
        variables.putAll(Map.of("secret", "hidden"), true);

        assertTrue(variables.remove("secret", "hidden"));
        variables.put("secret", "visible");

        assertTrue(variables.toString().contains("secret=visible"));
    }
}
