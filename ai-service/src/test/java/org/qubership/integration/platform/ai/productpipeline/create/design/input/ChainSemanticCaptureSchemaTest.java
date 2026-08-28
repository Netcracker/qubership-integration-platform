package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;

/**
 * Boundary test for the real generated Quarkus tool schema and argument mapper. A plain {@code
 * ObjectMapper} round trip is not enough: the design-input capture used to fail inside the
 * generated mapper, before {@link ChainSemanticCaptureTool} ever ran, because the canonical
 * revision demanded server-owned ids the model could not supply.
 */
@QuarkusTest
class ChainSemanticCaptureSchemaTest {

  private static final String METHOD = "captureChainSemanticRevision";

  /**
   * Server-owned state that must never reach the model as a schema property. {@code serviceCallId}
   * is on this list because the server materializes every service-call node from the approved
   * brief; a model that cannot send the key cannot break the join to the catalog binding.
   */
  private static final Set<String> SERVER_OWNED =
      Set.of(
          "revisionId",
          "edgeId",
          "schemaVersion",
          "compilerContractVersion",
          "serviceCallId",
          "serviceCalls");

  /** Injected only to prove the tool bean still resolves with its new adapter dependency. */
  @Inject ChainSemanticCaptureTool bean;

  @AfterEach
  void unbind() {
    ProductCapabilityCaptureContext.unbind();
  }

  @Test
  void generatedSchemaHidesEveryServerOwnedField() {
    assertNotNull(bean);
    Set<String> properties = propertyNames(parameters());
    assertFalse(properties.isEmpty(), "the generated tool schema has no properties");
    for (String owned : SERVER_OWNED) {
      assertFalse(properties.contains(owned), owned + " must not be a tool schema property");
    }
    assertTrue(properties.contains("entryPointId"), properties.toString());
    assertTrue(properties.contains("mappingIntentId"), properties.toString());
  }

  @Test
  void generatedSchemaKeepsEachNodeAndRegionVariantInItsOwnList() {
    JsonObjectSchema capture = (JsonObjectSchema) parameters().properties().get(parameterName());
    for (String list :
        List.of(
            "triggers",
            "operations",
            "sequenceRegions",
            "conditionRegions",
            "splitRegions",
            "loopRegions",
            "retryRegions",
            "errorScopeRegions")) {
      assertTrue(
          capture.properties().get(list) instanceof JsonArraySchema,
          list + " must be a homogeneous array in the generated schema");
    }
  }

  @Test
  void generatedMapperAcceptsACaptureWithoutServerOwnedFields() {
    ProductCapabilityCaptureContext.bindDesign(
        "run-1", "conv-1", ChainSemanticCaptureFixtures.approvedBrief(), payload -> {});
    String result = execute(linearArguments(ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID, ""));
    assertTrue(result.contains(ChainSemanticCaptureTool.CAPTURED_MESSAGE), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isPresent());
  }

  @Test
  void generatedMapperIgnoresUnknownFieldsAndAbsentOptionalLists() {
    ProductCapabilityCaptureContext.bindDesign(
        "run-1", "conv-1", ChainSemanticCaptureFixtures.approvedBrief(), payload -> {});
    // A model still holding the old contract sends serviceCalls; the mapper drops it, and the
    // edge into a node the server never created is what fails.
    String result =
        execute(
            linearArguments(
                "ghost-call",
                """
                "serviceCalls": [
                  {"nodeId": "ghost-call", "serviceCallId": "ghost-call"}
                ],
                """));
    assertTrue(result.contains("ghost-call"), result);
  }

  /**
   * The capture arrives with no region lists, no containment, no order, and no route kind, which
   * proves the mapper accepts an absent optional field instead of rejecting the call.
   */
  private static String linearArguments(String callNodeId, String unknownFields) {
    return """
        {"%s": {
          "chainIdentity": "chain-orders",
          %s"entryPoints": [
            {"entryPointId": "http-in", "triggerNodeId": "trigger-http",
             "initialTargetNodeId": "op-shared", "sourceFactIds": ["trigger-1"]}
          ],
          "triggers": [{"nodeId": "trigger-http", "sourceFactIds": ["trigger-1"]}],
          "operations": [{"nodeId": "op-shared", "elementType": "script"}],
          "edges": [
            {"sourceNodeId": "trigger-http", "targetNodeId": "op-shared"},
            {"sourceNodeId": "op-shared", "targetNodeId": "%s"}
          ]
        }}
        """
        .formatted(parameterName(), unknownFields, callNodeId);
  }

  private String execute(String arguments) {
    ToolMethodCreateInfo info = createInfo();
    QuarkusToolExecutor executor =
        new QuarkusToolExecutor(
            new QuarkusToolExecutor.Context(
                ChainSemanticCaptureToolTest.tool(ChainSemanticCaptureToolTest.completePack()),
                info.invokerClassName(),
                info.methodName(),
                info.argumentMapperClassName(),
                info.executionModel(),
                info.returnBehavior(),
                false,
                info));
    return executor.execute(
        ToolExecutionRequest.builder()
            .id("call-1")
            .name(info.toolSpecification().name())
            .arguments(arguments)
            .build(),
        "conv-1");
  }

  private static ToolMethodCreateInfo createInfo() {
    List<ToolMethodCreateInfo> infos =
        ToolsRecorder.getMetadata().get(ChainSemanticCaptureTool.class.getName());
    if (infos == null) {
      throw new IllegalStateException(
          "No generated tool metadata for " + ChainSemanticCaptureTool.class.getName());
    }
    return infos.stream()
        .filter(info -> METHOD.equals(info.methodName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No generated tool metadata for " + METHOD));
  }

  private static JsonObjectSchema parameters() {
    return createInfo().toolSpecification().parameters();
  }

  private static String parameterName() {
    return parameters().properties().keySet().iterator().next();
  }

  private static Set<String> propertyNames(JsonSchemaElement element) {
    Set<String> names = new LinkedHashSet<>();
    collectPropertyNames(element, names);
    return names;
  }

  private static void collectPropertyNames(JsonSchemaElement element, Set<String> names) {
    if (element instanceof JsonObjectSchema object) {
      object
          .properties()
          .forEach(
              (name, child) -> {
                names.add(name);
                collectPropertyNames(child, names);
              });
    } else if (element instanceof JsonArraySchema array) {
      collectPropertyNames(array.items(), names);
    }
  }
}
