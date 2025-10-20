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
}
