package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlExportImportMapperTest {
    private static final List<String> AWKWARD = List.of(
            "1.10", "1_000", "0x1F", "1e3", "1234e56", "007", ".inf", "-.Inf", ".NaN", "0o17",
            "2024-01-01", "12:30", "y", "off", "Yes", "~", "null", "",
            "- x", "@x", "key: value", "a #b", "{x}", "[x]", " lead", "trail ",
            "Grüße, 世界", "a\tb", "line1\nline2\n",
            "x" + (char) 0x85 + "y", "x" + (char) 0x2028 + "y", "x" + (char) 0x2029 + "y");

    private final YAMLMapper writer = new MapperAutoConfiguration().yamlExportImportMapper(true);
    private final YAMLMapper reader = new YAMLMapper();

    public static class Doc {
        public String name;
        public Map<String, Object> properties;
    }

    private static Doc awkwardDoc() {
        Doc doc = new Doc();
        doc.name = AWKWARD.get(0);
        doc.properties = new LinkedHashMap<>();
        for (int i = 0; i < AWKWARD.size(); i++) {
            doc.properties.put("k" + i, AWKWARD.get(i));
        }
        return doc;
    }

    @Test
    void roundTripsEveryStringThroughPojo() throws Exception {
        Doc doc = awkwardDoc();
        Doc read = reader.readValue(writer.writeValueAsString(doc), Doc.class);
        assertEquals(doc.name, read.name);
        assertEquals(doc.properties, read.properties);
    }

    @Test
    void roundTripsEveryStringThroughObjectNode() throws Exception {
        Doc doc = awkwardDoc();
        JsonNode tree = writer.valueToTree(doc);
        Doc read = reader.readValue(writer.writeValueAsString(tree), Doc.class);
        assertEquals(doc.name, read.name);
        assertEquals(doc.properties, read.properties);
    }

    @Test
    void keepsRealScalarsPlainAndTyped() throws Exception {
        Doc doc = new Doc();
        doc.name = "n";
        doc.properties = new LinkedHashMap<>();
        doc.properties.put("realInt", 8080);
        doc.properties.put("realDouble", 1.5);
        doc.properties.put("realBool", true);
        doc.properties.put("list", List.of("1.10", 42));

        String text = writer.writeValueAsString(doc);

        assertTrue(text.contains("realInt: 8080"), text);
        assertTrue(text.contains("realBool: true"), text);
        assertEquals(doc.properties, reader.readValue(text, Doc.class).properties);
    }

    @Test
    void writesOrdinaryStringsWithoutQuotes() throws Exception {
        Doc doc = new Doc();
        doc.name = "plain text";
        assertTrue(writer.writeValueAsString(doc).contains("name: plain text"));
    }

    @Test
    void quotesYaml12OctalForJsParsers() throws Exception {
        Doc doc = new Doc();
        doc.name = "0o17";
        assertTrue(writer.writeValueAsString(doc).contains("name: \"0o17\""));
    }

    @Test
    void quotesSignedLeadingZeroDecimalsForJsParsers() throws Exception {
        for (String value : List.of("+08", "-09", "+0708", "-0_8")) {
            Doc doc = new Doc();
            doc.name = value;
            assertTrue(writer.writeValueAsString(doc).contains("name: \"" + value + "\""), value);
        }
    }

    @Test
    void quotesEveryStringWhenSettingIsOff() throws Exception {
        Doc doc = new Doc();
        doc.name = "plain text";
        String text = new MapperAutoConfiguration().yamlExportImportMapper(false).writeValueAsString(doc);
        assertTrue(text.contains("name: \"plain text\""), text);
    }
}
