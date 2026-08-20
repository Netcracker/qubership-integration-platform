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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JsonSchemaFormatTest {

    /**
     * The file extension a document of each schema is written under, read from the naming corpus in this module's own
     * test resources rather than restated here. Most schemas follow the convention "the schema's own name plus
     * {@code .qip}"; the three per-type service schemas deliberately do not, because a plain service file is named
     * {@code <id>.service.<app>.yaml} whatever its type. Reading the corpus keeps that exception a consequence of the
     * declared rule instead of a carve-out list nothing links back to it.
     */
    private static Map<String, String> extensionBySchemaName;

    private static List<Resource> schemaResources;

    @BeforeAll
    public static void setUp() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        schemaResources = Arrays.asList(resolver.getResources("classpath:**/*.schema.yaml"));
        extensionBySchemaName = readDeclaredExtensions();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readDeclaredExtensions() throws IOException {
        Resource corpus = new PathMatchingResourcePatternResolver()
                .getResource("classpath:naming/service-file-names.yaml");
        assertTrue(corpus.exists(), "the naming corpus is missing from src/test/resources/naming");
        var declaration = new ObjectMapper(new YAMLFactory())
                .readValue(corpus.getContentAsString(Charset.defaultCharset()), LinkedHashMap.class);
        var postfixByKind = (Map<String, String>) ((Map<String, Object>)
                ((Map<String, Object>) declaration.get("rule")).get("current")).get("postfixes");
        var stemByKind = (Map<String, String>) ((Map<String, Object>) declaration.get("types")).get("schemaFileStems");

        // Keyed by the schema's own file name, which is what this test holds: `external-service` is written under
        // whatever the corpus says an EXTERNAL service is named.
        Map<String, String> byName = new LinkedHashMap<>();
        stemByKind.forEach((kind, stem) -> byName.put(stem, postfixByKind.get(kind).replace(".", "") + ".qip"));
        return byName;
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

        assertTrue(resource.getFilename().endsWith(".schema.yaml"), resource.getFilename() + ": Schema file name must end with .schema.yaml");
        assertTrue(schemaYaml.get("$id").toString().endsWith(resource.getFilename()), resource.getFilename() + ": '$id' field must end with file name");
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
        assertEquals("Qubership Integration Platform", metaInfo.get("application"), resource.getFilename() + ": 'application' field must match!");

        var labels = (List<String>) metaInfo.get("labels");
        assertLinesMatch(List.of("QIP"), labels, resource.getFilename() + ": 'labels' must be [QIP]!");
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

        boolean isTopLevelEntity = topLevelAllOfRef.equals("http://qubership.org/schemas/product/qip/common-properties/top-level-entity-properties.schema.yaml");
        if (isTopLevelEntity) {
            String schemaName = resource.getFilename().replace(".schema.yaml", "");
            String expectedFileExt = extensionBySchemaName.getOrDefault(schemaName, schemaName + ".qip");
            assertEquals(expectedFileExt, metaInfo.get("fileExtension"), resource.getFilename() + ": 'fileExtension' field must match!");
        }
    }
}
