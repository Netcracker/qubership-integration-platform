package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ApiSpecificationExportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiSpecificationExportServiceMergeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiSpecificationExportService service;
    private SystemModelService systemModelService;
    private Method mergeAsyncApiSpec;
    private Method mergeObjectField;
    private Method buildAsyncApiSpecification;

    @BeforeEach
    void setUp() throws Exception {
        systemModelService = mock(SystemModelService.class);
        service = new ApiSpecificationExportService("/qip-routes", null, null, systemModelService);

        mergeAsyncApiSpec = ApiSpecificationExportService.class
                .getDeclaredMethod("mergeAsyncApiSpec", ObjectNode.class, ObjectNode.class);
        mergeAsyncApiSpec.setAccessible(true);

        mergeObjectField = ApiSpecificationExportService.class
                .getDeclaredMethod("mergeObjectField", ObjectNode.class, ObjectNode.class, String.class);
        mergeObjectField.setAccessible(true);

        Class<?> buildParamsClass = Class.forName(
                ApiSpecificationExportService.class.getName() + "$SpecificationBuildParameters");
        buildAsyncApiSpecification = ApiSpecificationExportService.class
                .getDeclaredMethod("buildAsyncApiSpecification", buildParamsClass);
        buildAsyncApiSpecification.setAccessible(true);
    }

    private Object createBuildParams(Collection<ChainElement> elements) throws Exception {
        Class<?> buildParamsClass = Class.forName(
                ApiSpecificationExportService.class.getName() + "$SpecificationBuildParameters");
        Method builderMethod = buildParamsClass.getDeclaredMethod("builder");
        builderMethod.setAccessible(true);
        Object builder = builderMethod.invoke(null);

        Class<?> builderClass = builder.getClass();
        builderClass.getDeclaredMethod("elements", Collection.class).invoke(builder, elements);
        builderClass.getDeclaredMethod("externalRoutes", boolean.class).invoke(builder, false);
        return builderClass.getDeclaredMethod("build").invoke(builder);
    }

    private ChainElement createTriggerElement(String modelId) {
        ChainElement element = new ChainElement();
        element.getProperties().put("integrationOperationPath", "/some/path");
        element.getProperties().put("integrationSpecificationId", modelId);
        return element;
    }

    @Test
    void mergeObjectFieldAddsNewEntries() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("channels").put("ch1", "v1");

        ObjectNode other = mapper.createObjectNode();
        other.putObject("channels").put("ch2", "v2");

        mergeObjectField.invoke(service, base, other, "channels");

        assertTrue(base.get("channels").has("ch1"));
        assertTrue(base.get("channels").has("ch2"));
    }

    @Test
    void mergeObjectFieldDoesNotOverwriteExisting() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("channels").put("ch1", "original");

        ObjectNode other = mapper.createObjectNode();
        other.putObject("channels").put("ch1", "overwritten");

        mergeObjectField.invoke(service, base, other, "channels");

        assertEquals("original", base.get("channels").get("ch1").asText());
    }

    @Test
    void mergeObjectFieldCreatesFieldIfBaseDoesNotHaveIt() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        ObjectNode other = mapper.createObjectNode();
        other.putObject("servers").put("prod", "host:5672");

        mergeObjectField.invoke(service, base, other, "servers");

        assertTrue(base.has("servers"));
        assertEquals("host:5672", base.get("servers").get("prod").asText());
    }

    @Test
    void mergeObjectFieldSkipsIfOtherDoesNotHaveField() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("channels").put("ch1", "v1");

        ObjectNode other = mapper.createObjectNode();

        mergeObjectField.invoke(service, base, other, "channels");

        assertTrue(base.get("channels").has("ch1"));
    }

    @Test
    void mergeObjectFieldSkipsIfOtherFieldIsNotObject() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("channels").put("ch1", "v1");

        ObjectNode other = mapper.createObjectNode();
        other.put("channels", "not-an-object");

        mergeObjectField.invoke(service, base, other, "channels");

        assertTrue(base.get("channels").has("ch1"));
    }

    @Test
    void mergeObjectFieldSkipsIfBaseFieldIsNotObject() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.put("channels", "not-an-object");

        ObjectNode other = mapper.createObjectNode();
        other.putObject("channels").put("ch2", "v2");

        mergeObjectField.invoke(service, base, other, "channels");

        assertEquals("not-an-object", base.get("channels").asText());
    }

    @Test
    void mergeAsyncApiSpecMergesAllTopLevelFields() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("servers").put("s1", "host1");
        base.putObject("channels").put("ch1", "addr1");

        ObjectNode other = mapper.createObjectNode();
        other.putObject("servers").put("s2", "host2");
        other.putObject("channels").put("ch2", "addr2");
        other.putObject("operations").put("op1", "data");

        mergeAsyncApiSpec.invoke(service, base, other);

        assertTrue(base.get("servers").has("s1"));
        assertTrue(base.get("servers").has("s2"));
        assertTrue(base.get("channels").has("ch1"));
        assertTrue(base.get("channels").has("ch2"));
        assertTrue(base.has("operations"));
        assertTrue(base.get("operations").has("op1"));
    }

    @Test
    void mergeAsyncApiSpecMergesComponentsSubFields() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        ObjectNode baseComponents = base.putObject("components");
        baseComponents.putObject("schemas").put("A", "schemaA");

        ObjectNode other = mapper.createObjectNode();
        ObjectNode otherComponents = other.putObject("components");
        otherComponents.putObject("schemas").put("B", "schemaB");
        otherComponents.putObject("messages").put("M1", "msg1");

        mergeAsyncApiSpec.invoke(service, base, other);

        assertTrue(base.at("/components/schemas").has("A"));
        assertTrue(base.at("/components/schemas").has("B"));
        assertTrue(base.at("/components/messages").has("M1"));
    }

    @Test
    void mergeAsyncApiSpecSetsComponentsIfBaseLacksThem() throws Exception {
        ObjectNode base = mapper.createObjectNode();

        ObjectNode other = mapper.createObjectNode();
        other.putObject("components").putObject("schemas").put("X", "val");

        mergeAsyncApiSpec.invoke(service, base, other);

        assertTrue(base.has("components"));
        assertTrue(base.at("/components/schemas").has("X"));
    }

    @Test
    void mergeAsyncApiSpecSkipsIfOtherHasNoComponents() throws Exception {
        ObjectNode base = mapper.createObjectNode();
        base.putObject("components").putObject("schemas").put("A", "val");

        ObjectNode other = mapper.createObjectNode();

        mergeAsyncApiSpec.invoke(service, base, other);

        assertTrue(base.at("/components/schemas").has("A"));
    }

    @Test
    void guessFormatDetectsJsonAndYaml() throws Exception {
        Method guessFormat = ApiSpecificationExportService.class
                .getDeclaredMethod("guessFormat", String.class);
        guessFormat.setAccessible(true);

        assertEquals(
                org.qubership.integration.platform.runtime.catalog.model.apispec.ApiSpecificationFormat.JSON,
                guessFormat.invoke(service, "{\"asyncapi\": \"3.0.0\"}")
        );
        assertEquals(
                org.qubership.integration.platform.runtime.catalog.model.apispec.ApiSpecificationFormat.YAML,
                guessFormat.invoke(service, "asyncapi: 3.0.0")
        );
    }

    @Test
    void buildAsyncApiSpecificationSingleYamlSpec() throws Exception {
        String yamlSpec = "asyncapi: 2.6.0\ninfo:\n  title: Test\nchannels:\n  test: {}";
        when(systemModelService.getMainSystemModelSource("model-1")).thenReturn(yamlSpec);

        ChainElement element = createTriggerElement("model-1");
        Object params = createBuildParams(List.of(element));

        JsonNode result = (JsonNode) buildAsyncApiSpecification.invoke(service, params);

        assertNotNull(result);
        assertEquals("2.6.0", result.get("asyncapi").asText());
        assertEquals("Test", result.at("/info/title").asText());
    }

    @Test
    void buildAsyncApiSpecificationSingleJsonSpec() throws Exception {
        String jsonSpec = "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"JSON Test\"}}";
        when(systemModelService.getMainSystemModelSource("model-json")).thenReturn(jsonSpec);

        ChainElement element = createTriggerElement("model-json");
        Object params = createBuildParams(List.of(element));

        JsonNode result = (JsonNode) buildAsyncApiSpecification.invoke(service, params);

        assertNotNull(result);
        assertEquals("3.0.0", result.get("asyncapi").asText());
    }

    @Test
    void buildAsyncApiSpecificationMergesMultipleSpecs() throws Exception {
        String spec1 = "asyncapi: 2.6.0\nchannels:\n  ch1:\n    description: first";
        String spec2 = "asyncapi: 2.6.0\nchannels:\n  ch2:\n    description: second";
        when(systemModelService.getMainSystemModelSource("m1")).thenReturn(spec1);
        when(systemModelService.getMainSystemModelSource("m2")).thenReturn(spec2);

        ChainElement e1 = createTriggerElement("m1");
        ChainElement e2 = createTriggerElement("m2");
        Object params = createBuildParams(List.of(e1, e2));

        JsonNode result = (JsonNode) buildAsyncApiSpecification.invoke(service, params);

        assertNotNull(result);
        assertTrue(result.has("channels"));
        assertTrue(result.get("channels").has("ch1"));
        assertTrue(result.get("channels").has("ch2"));
    }

    @Test
    void buildAsyncApiSpecificationThrowsForEmptyElements() throws Exception {
        Object params = createBuildParams(Collections.emptyList());

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> buildAsyncApiSpecification.invoke(service, params));
        assertInstanceOf(ApiSpecificationExportException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("No async trigger"));
    }

    @Test
    void buildAsyncApiSpecificationThrowsForNoModelIds() throws Exception {
        ChainElement element = new ChainElement();
        // no integrationOperationPath → isImplementedServiceTrigger returns false
        Object params = createBuildParams(List.of(element));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> buildAsyncApiSpecification.invoke(service, params));
        assertInstanceOf(ApiSpecificationExportException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("No specification model"));
    }

    @Test
    void buildAsyncApiSpecificationFiltersNullModelIds() throws Exception {
        String spec = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"Test\"}}";
        when(systemModelService.getMainSystemModelSource("valid")).thenReturn(spec);

        ChainElement withModel = createTriggerElement("valid");
        ChainElement withNullModel = new ChainElement();
        withNullModel.getProperties().put("integrationOperationPath", "/path");
        // no integrationSpecificationId → null model ID, should be filtered out
        Object params = createBuildParams(List.of(withModel, withNullModel));

        JsonNode result = (JsonNode) buildAsyncApiSpecification.invoke(service, params);
        assertNotNull(result);
    }
}
