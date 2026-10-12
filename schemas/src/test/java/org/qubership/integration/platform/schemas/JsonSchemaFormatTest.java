package org.qubership.integration.platform.schemas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonSchemaFormatTest {
    private static final String ID_PREFIX =
            "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/";
    private static final String SCHEMA_FILE_SUFFIX = ".schema.yaml";
    private static final String MODEL_DIR = "/qip-model/";

    private static List<Resource> schemaResources;

    @BeforeAll
    public static void setUp() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        schemaResources = Arrays.asList(resolver.getResources("classpath:**/*.schema.yaml"));
    }

    @ParameterizedTest
    @FieldSource("schemaResources")
    public void testSchemaMandatoryFields(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        LinkedHashMap<String, Object> schemaYaml = mapper.readValue(source, LinkedHashMap.class);

        List.of("$id", "$schema", "title").forEach(field ->
                assertNotNull(schemaYaml.get(field), resource.getFilename() + ": '" + field + "' field must present!")
        );

        assertTrue(resource.getFilename().endsWith(SCHEMA_FILE_SUFFIX), resource.getFilename() + ": Schema file name must end with .schema.yaml");
        String path = resource.getURI().getPath();
        String relativePath = path.substring(path.lastIndexOf(MODEL_DIR) + MODEL_DIR.length());
        String expectedId = ID_PREFIX + relativePath.substring(0, relativePath.length() - SCHEMA_FILE_SUFFIX.length());
        assertEquals(expectedId, schemaYaml.get("$id"), resource.getFilename() + ": '$id' field must match the file path");
    }

    @ParameterizedTest
    @FieldSource("schemaResources")
    public void testSchemaFieldsOrder(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        LinkedHashMap<String, Object> schemaYaml = mapper.readValue(source, LinkedHashMap.class);

        List<String> listOfOrderedKeys = schemaYaml.sequencedKeySet().stream().filter(schemaFieldsOrder()::contains).toList();
        List<String> rightOrderOfKeys = schemaFieldsOrder().stream().filter(listOfOrderedKeys::contains).toList();
        assertEquals(rightOrderOfKeys, listOfOrderedKeys, resource.getFilename() + ": Schema fields order is incorrect!");
    }

    public static List<String> schemaFieldsOrder() {
        return List.of(
                "$id",
                "type",
                "allOf",
                "title",
                "description",
                "metaInfo",
                "properties"
        );
    }

    @ParameterizedTest
    @FieldSource("schemaResources")
    public void testSchemaPropsMandatoryFields(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        LinkedHashMap<String, Object> schemaYaml = mapper.readValue(source, LinkedHashMap.class);

        var props = (LinkedHashMap<String, LinkedHashMap<String, Object>>) schemaYaml.get("properties");

        if (props != null) {
            props.forEach((prop, propKeys) -> {
                List<String> listOfOrderedPropKeys = propKeys.sequencedKeySet().stream().filter(propertiesFieldsOrder()::contains).toList();
                List<String> rightOrderOfPropKeys = propertiesFieldsOrder().stream().filter(listOfOrderedPropKeys::contains).toList();
                assertEquals(rightOrderOfPropKeys, listOfOrderedPropKeys, resource.getFilename() + ": Schema property '" + prop + "' fields order is incorrect!");
            });
        }
    }

    public static List<String> propertiesFieldsOrder() {
        return List.of(
                "type",
                "properties",
                "required"
        );
    }

    @ParameterizedTest
    @FieldSource("schemaResources")
    public void testSchemaMetaInfoPresent(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        LinkedHashMap<String, Object> schemaYaml = mapper.readValue(source, LinkedHashMap.class);

        var metaInfo = (LinkedHashMap<String, Object>) schemaYaml.get("metaInfo");
        assertNotNull(metaInfo, resource.getFilename() + ": 'metaInfo' field must present!");
        assertEquals("Cloud Integration Platform", metaInfo.get("application"), resource.getFilename() + ": 'application' field must match!");

        var labels = (List<String>) metaInfo.get("labels");
        assertLinesMatch(List.of("CIP"), labels, resource.getFilename() + ": 'labels' must be [CIP]!");
    }

    @ParameterizedTest
    @FieldSource("schemaResources")
    public void testSchemaMetaInfoFileExtPresentForTopLevel(Resource resource) throws IOException {
        String source = resource.getContentAsString(Charset.defaultCharset());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        LinkedHashMap<String, Object> schemaYaml = mapper.readValue(source, LinkedHashMap.class);

        var metaInfo = (LinkedHashMap<String, Object>) schemaYaml.get("metaInfo");

        var topLevelAllOf = (List<LinkedHashMap<String, String>>) schemaYaml.get("allOf");
        if (topLevelAllOf == null) {
            return;
        }

        var topLevelAllOfRef = topLevelAllOf.get(0).get("$ref");
        if (topLevelAllOfRef == null) {
            return;
        }

        boolean isTopLevelEntity = topLevelAllOfRef.equals(ID_PREFIX + "common-properties/top-level-entity-properties");
        if (isTopLevelEntity) {
            String schemaName = resource.getFilename().replace(SCHEMA_FILE_SUFFIX, "");
            String expectedFileExt = schemaName + ".cip";
            assertEquals(expectedFileExt, metaInfo.get("fileExtension"), resource.getFilename() + ": 'fileExtension' field must match!");
        }
    }
}
