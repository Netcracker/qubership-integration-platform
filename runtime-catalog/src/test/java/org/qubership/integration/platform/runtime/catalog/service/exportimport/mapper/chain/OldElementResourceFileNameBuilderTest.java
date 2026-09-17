package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.EXPORT_FILE_EXTENSION_PROPERTY;

class OldElementResourceFileNameBuilderTest {

    private final OldElementResourceFileNameBuilder builder = new OldElementResourceFileNameBuilder();

    private static ChainElementExternalEntity element(String id, String type, Map<String, Object> properties) {
        return ChainElementExternalEntity.builder()
                .id(id)
                .type(type)
                .properties(properties != null ? new HashMap<>(properties) : new HashMap<>())
                .build();
    }

    @DisplayName("a single property for a mapper type keeps its name as prefix")
    @Test
    void generatePropertiesFileNameSinglePropertyForMapperTypeKeepsName() {
        ChainElementExternalEntity el = element("el-1", "mapper-foo",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("mappingDescription"));

        assertEquals("mappingDescription-el-1.json", result);
    }

    @DisplayName("multiple properties for a mapper type collapses to mapper prefix")
    @Test
    void generatePropertiesFileNameMultiplePropertiesForMapperTypeCollapsesToMapper() {
        ChainElementExternalEntity el = element("el-1", "mapper",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("a", "b"));

        assertEquals("mapper-el-1.json", result);
    }

    @DisplayName("a single property for a non-mapper type keeps its name as prefix")
    @Test
    void generatePropertiesFileNameSinglePropertyForNonMapperKeepsName() {
        ChainElementExternalEntity el = element("el-7", "http-sender",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "groovy"));

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("script-el-7.groovy", result);
    }

    @DisplayName("multiple properties for a non-mapper type collapses to properties prefix")
    @Test
    void generatePropertiesFileNameMultiplePropertiesForNonMapperCollapsesToProperties() {
        ChainElementExternalEntity el = element("el-1", "service-call",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("a", "b"));

        assertEquals("properties-el-1.json", result);
    }

    @DisplayName("null type is treated as non-mapper")
    @Test
    void generatePropertiesFileNameNullTypeIsTreatedAsNonMapper() {
        ChainElementExternalEntity el = element("el-1", null,
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, "json"));

        String result = builder.generatePropertiesFileName(el, List.of("solo"));

        assertEquals("solo-el-1.json", result);
    }

    @DisplayName("strips a leading dot from the export file extension")
    @Test
    void generatePropertiesFileNameStripsLeadingDotFromExtension() {
        ChainElementExternalEntity el = element("el-1", "http-sender",
                Map.of(EXPORT_FILE_EXTENSION_PROPERTY, ".groovy"));

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("script-el-1.groovy", result);
    }

    @DisplayName("falls back to txt when no export file extension is set")
    @Test
    void generatePropertiesFileNameFallsBackToTxtWhenExtensionMissing() {
        ChainElementExternalEntity el = element("el-1", "http-sender", Map.of());

        String result = builder.generatePropertiesFileName(el, List.of("script"));

        assertEquals("script-el-1.txt", result);
    }

    @DisplayName("before script file uses the legacy script-before pattern")
    @Test
    void generateBeforeScriptFileNameUsesLegacyPattern() {
        String result = builder.generateBeforeScriptFileName("el-1");

        assertEquals("script-before-el-1.groovy", result);
    }

    @DisplayName("before mapper file uses the legacy mappingDescription-before pattern")
    @Test
    void generateBeforeMapperFileNameUsesLegacyPattern() {
        String result = builder.generateBeforeMapperFileName("el-1");

        assertEquals("mappingDescription-before-el-1.json", result);
    }

    @DisplayName("after script file embeds id before the element id")
    @Test
    void generateAfterScriptFileNameEmbedsIdBeforeElementId() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", "404"));

        assertEquals("script-404-el-1.groovy", result);
    }

    @DisplayName("after script file falls back to code when id is absent")
    @Test
    void generateAfterScriptFileNameFallsBackToCodeWhenIdAbsent() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("code", "myCode"));

        assertEquals("script-myCode-el-1.groovy", result);
    }

    @DisplayName("after mapper file embeds id before the element id")
    @Test
    void generateAfterMapperFileNameEmbedsIdBeforeElementId() {
        String result = builder.generateAfterMapperFileName("el-1", Map.of("id", "200"));

        assertEquals("mappingDescription-200-el-1.json", result);
    }

    @DisplayName("after script file does not normalize a status-code range in legacy mode")
    @Test
    void generateAfterScriptFileNameDoesNotNormalizeRangeInLegacyMode() {
        String result = builder.generateAfterScriptFileName("el-1", Map.of("id", "200..299"));

        assertEquals("script-200..299-el-1.groovy", result);
    }

    @DisplayName("after mapper file does not normalize a status-code range in legacy mode")
    @Test
    void generateAfterMapperFileNameDoesNotNormalizeRangeInLegacyMode() {
        String result = builder.generateAfterMapperFileName("el-1", Map.of("id", "200..299"));

        assertEquals("mappingDescription-200..299-el-1.json", result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"200..299", "500..599", "100..199"})
    @DisplayName("legacy builder keeps status ranges verbatim")
    void generateAfterScriptFileNameKeepsStatusRangesVerbatim(String range) {
        String result = builder.generateAfterScriptFileName("el-9", Map.of("id", range));

        assertEquals("script-" + range + "-el-9.groovy", result);
    }
}
