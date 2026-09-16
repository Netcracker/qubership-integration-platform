package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.EXPORT_FILE_EXTENSION_PROPERTY;

class ElementResourceFileNameBuilderImplTest {

    private final ElementResourceFileNameBuilderImpl builder = new ElementResourceFileNameBuilderImpl();

    private static ChainElementExternalEntity element(String id, String type, Map<String, Object> properties) {
        return ChainElementExternalEntity.builder()
                .id(id)
                .type(type)
                .properties(properties != null ? new HashMap<>(properties) : new HashMap<>())
                .build();
    }

    @DisplayName("a single mappingDescription property for a mapper type builds a mapper kind file name")
    @Test
    void generatePropertiesFileNameSingleMappingDescriptionForMapperTypeBuildsMapperKind() {
        ChainElementExternalEntity el = element("el-1", "mapper-custom",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("mappingDescription"));

        assertEquals("el-1.element.mapper.cip.json", result);
    }

    @DisplayName("a single script property for a mapper type builds a script kind file name")
    @Test
    void generatePropertiesFileNameSingleScriptForMapperTypeBuildsScriptKind() {
        ChainElementExternalEntity el = element("el-1", "mapper",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "groovy"));

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("el-1.element.script.cip.groovy", result);
    }

    @DisplayName("a single custom property for a mapper type preserves the property name as kind")
    @Test
    void generatePropertiesFileNameSingleCustomPropertyForMapperTypePreservesName() {
        ChainElementExternalEntity el = element("el-42", "mapper-foo",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "txt"));

        String result = builder.generatePropertiesFileName(el, List.of("customProp"));

        assertEquals("el-42.element.customProp.cip.txt", result);
    }

    @DisplayName("multiple properties for a mapper type collapses to mapper kind")
    @Test
    void generatePropertiesFileNameMultiplePropertiesForMapperTypeCollapsesToMapper() {
        ChainElementExternalEntity el = element("el-1", "mapper",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("a", "b"));

        assertEquals("el-1.element.mapper.cip.json", result);
    }

    @DisplayName("a single custom property for a non-mapper type preserves the property name")
    @Test
    void generatePropertiesFileNameSingleCustomPropertyForNonMapperPreservesName() {
        ChainElementExternalEntity el = element("el-7", "http-sender",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("myProp"));

        assertEquals("el-7.element.myProp.cip.json", result);
    }

    @DisplayName("a single script property for a non-mapper type builds a script kind")
    @Test
    void generatePropertiesFileNameSingleScriptForNonMapperBuildsScriptKind() {
        ChainElementExternalEntity el = element("el-7", "http-sender",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "groovy"));

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("el-7.element.script.cip.groovy", result);
    }

    @DisplayName("multiple properties for a non-mapper type collapses to properties kind")
    @Test
    void generatePropertiesFileNameMultiplePropertiesForNonMapperCollapsesToProperties() {
        ChainElementExternalEntity el = element("el-1", "service-call",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("a", "b"));

        assertEquals("el-1.element.properties.cip.json", result);
    }

    @DisplayName("null type is treated as non-mapper")
    @Test
    void generatePropertiesFileNameNullTypeIsTreatedAsNonMapper() {
        ChainElementExternalEntity el = element("el-1", null,
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("solo"));

        assertEquals("el-1.element.solo.cip.json", result);
    }

    @DisplayName("strips a leading dot from the export file extension")
    @Test
    void generatePropertiesFileNameStripsLeadingDotFromExtension() {
        ChainElementExternalEntity el = element("el-1", "http-sender",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, ".groovy"));

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("el-1.element.script.cip.groovy", result);
    }

    @DisplayName("falls back to txt when no export file extension is set")
    @Test
    void generatePropertiesFileNameFallsBackToTxtWhenExtensionMissing() {
        ChainElementExternalEntity el = element("el-1", "http-sender", Map.of());

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("el-1.element.script.cip.txt", result);
    }

    @DisplayName("falls back to txt when properties map is empty")
    @Test
    void generatePropertiesFileNameFallsBackToTxtWhenPropertiesEmpty() {
        ChainElementExternalEntity el = element("el-99", "http-sender", new HashMap<>());

        String result = builder.generatePropertiesFileName(el, List.of("a", "b"));

        assertEquals("el-99.element.properties.cip.txt", result);
    }

    @DisplayName("uses the extension literal when it is not a string with a dot")
    @Test
    void generatePropertiesFileNameUsesExtensionLiteralWhenNumeric() {
        Map<String, Object> props = new HashMap<>();
        props.put(EXPORT_FILE_EXTENSION_PROPERTY, 123);
        ChainElementExternalEntity el = element("el-1", "http-sender", props);

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("el-1.element.script.cip.123", result);
    }

    @DisplayName("before script file uses the cip before segment")
    @Test
    void generateBeforeScriptFileNameUsesCipBeforeSegment() {
        String result = builder.generateBeforeScriptFileName("el-1");

        assertEquals("el-1.before.script.cip.groovy", result);
    }

    @DisplayName("before mapper file uses the cip before segment")
    @Test
    void generateBeforeMapperFileNameUsesCipBeforeSegment() {
        String result = builder.generateBeforeMapperFileName("el-1");

        assertEquals("el-1.before.mapper.cip.json", result);
    }

    @DisplayName("after script file embeds the after id")
    @Test
    void generateAfterScriptFileNameEmbedsAfterId() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", "404"));

        assertEquals("el-1.after-404.script.cip.groovy", result);
    }

    @DisplayName("after script file falls back to code when id is absent")
    @Test
    void generateAfterScriptFileNameFallsBackToCodeWhenIdAbsent() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("code", "myCode"));

        assertEquals("el-1.after-myCode.script.cip.groovy", result);
    }

    @DisplayName("after mapper file embeds the after id")
    @Test
    void generateAfterMapperFileNameEmbedsAfterId() {
        String result = builder.generateAfterMapperFileName("el-1", Map.of("id", "200"));

        assertEquals("el-1.after-200.mapper.cip.json", result);
    }

    @DisplayName("after mapper file normalizes a status-code range to xx")
    @Test
    void generateAfterMapperFileNameNormalizesStatusRange() {
        String result = builder.generateAfterMapperFileName("el-1", Map.of("id", "200..299"));

        assertEquals("el-1.after-2xx.mapper.cip.json", result);
    }

    @DisplayName("after script file normalizes a status-code range to xx")
    @Test
    void generateAfterScriptFileNameNormalizesStatusRange() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", "500..599"));

        assertEquals("el-1.after-5xx.script.cip.groovy", result);
    }

    @DisplayName("after file keeps a non-matching range unchanged")
    @Test
    void generateAfterScriptFileNameKeepsNonMatchingRangeUnchanged() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", "100..299"));

        assertEquals("el-1.after-100..299.script.cip.groovy", result);
    }

    @ParameterizedTest(name = "{0} normalizes to {1}")
    @CsvSource({
        "100..199, 1xx",
        "200..299, 2xx",
        "300..399, 3xx",
        "400..499, 4xx",
        "500..599, 5xx"
    })
    @DisplayName("normalizes each  n00..n99 range to nxx")
    void generateAfterScriptFileNameNormalizesEachRange(String input, String expected) {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", input));

        assertEquals("el-1.after-" + expected + ".script.cip.groovy", result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"600..699", "100..299", "200..200", "abc", "1xx", ""})
    @DisplayName("leaves non-matching after ids unchanged")
    void generateAfterMapperFileNameLeavesNonMatchingAfterIdsUnchanged(String input) {
        String result = builder.generateAfterMapperFileName("el-1", Map.of("id", input));

        assertEquals("el-1.after-" + input + ".mapper.cip.json", result);
    }
}
