package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.HttpTriggerCapture;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;

class E2eRecoveryTestControlTest {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void elementFaultChangesOnlyDeclaredValuesOnce() throws Exception {
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "owned", "boundary", "completion", "mutation", "element-property-values",
        "propertyValues", Map.of("logLevel", "unsupported")));
    var control = new E2eRecoveryTestControl(directory, mapper);
    var proposal = mapper.readTree("""
        {"patchId":"patch","ownerCapabilityId":"monitoring","propertyPatches":[
          {"operation":"ADD","targetNodeId":"log","key":"logLevel","value":{},"scalarValue":"Info"},
          {"operation":"ADD","targetNodeId":"log","key":"message","scalarValue":"keep this"}]}
        """);
    assertSame(proposal, control.elementPatch("foreign", "monitoring", proposal));
    assertSame(proposal, new E2eRecoveryTestControl((Path) null, mapper).elementPatch("owned", "monitoring", proposal));
    var changed = control.elementPatch("owned", "monitoring", proposal);
    var expected = proposal.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("propertyPatches").get(0)).put("scalarValue", "unsupported");
    for (String field : List.of("arrayValue", "mapEntries", "mapValue")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("propertyPatches").get(0)).putNull(field);
    }
    assertEquals(expected, changed);
    assertEquals("Info", proposal.path("propertyPatches").get(0).path("scalarValue").asText());
    assertSame(proposal, control.elementPatch("owned", "monitoring", proposal));
    try (var events = Files.list(directory.resolve("events"))) { assertEquals(1, events.count()); }
  }

  @Test void disabledAndForeignConversationControlsAreInert() throws Exception {
    var proposal = new HttpTriggerCapture(List.of(new HttpTriggerCapture.Endpoint("role", "node", false)));
    assertSame(proposal, new E2eRecoveryTestControl((Path) null, mapper).httpCapture("owned", "trigger", proposal));
    writePlan("checkpoint");
    var control = new E2eRecoveryTestControl(directory, mapper);
    assertSame(proposal, control.httpCapture("foreign", "trigger", proposal));
    assertFalse(control.materializationInputsAccepted(context("foreign"), ref(), List.of(ref())));
    assertFalse(Files.exists(directory.resolve("events")));
  }

  @Test void onlyOneReferenceIsChangedAndFullRunsContinue() throws Exception {
    writePlan("completion");
    var control = new E2eRecoveryTestControl(directory, mapper);
    var proposal = new HttpTriggerCapture(List.of(new HttpTriggerCapture.Endpoint("role", "node", false)));
    var changed = control.httpCapture("owned", "trigger", proposal);
    assertEquals("role", changed.endpoints().getFirst().roleId());
    assertEquals(false, changed.endpoints().getFirst().externalRoute());
    assertFalse(changed.endpoints().getFirst().semanticNodeId().equals("node"));
    assertSame(proposal, control.httpCapture("owned", "trigger", proposal));
    assertFalse(control.materializationInputsAccepted(context("owned"), ref(), List.of(ref())));
    assertTrue(Files.exists(directory.resolve("checkpoint.json")));
  }

  @Test void rawArgumentFaultChangesOneBooleanTypeOnceAndIsIsolated() throws Exception {
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "owned", "boundary", "checkpoint", "mutation", "http-exposure-type"));
    var control = new E2eRecoveryTestControl(directory, mapper);
    var request = ToolExecutionRequest.builder().id("capture-1").name("captureHttpTriggers").arguments("""
        {"capture":{"endpoints":[{"roleId":"entry","semanticNodeId":"http-1","externalRoute":false}]}}
        """).build();
    assertSame(request, control.httpArguments("foreign", "trigger", request));
    var changed = control.httpArguments("owned", "trigger", request);
    assertEquals(request.id(), changed.id());
    assertEquals(request.name(), changed.name());
    var expected = mapper.readTree(request.arguments());
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("capture").path("endpoints").path(0))
        .put("externalRoute", "false");
    assertEquals(expected, mapper.readTree(changed.arguments()));
    assertSame(request, control.httpArguments("owned", "trigger", request));
    assertSame(request, new E2eRecoveryTestControl((Path) null, mapper).httpArguments("owned", "trigger", request));
    try (var events = Files.list(directory.resolve("events"))) { assertEquals(1, events.count()); }
  }

  @Test void distinctFieldFaultsAreFiniteAndRetainTheRestOfTheModelCandidate() throws Exception {
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "owned", "boundary", "checkpoint", "mutation", "http-distinct-fields"));
    var control = new E2eRecoveryTestControl(directory, mapper);
    var request = ToolExecutionRequest.builder().id("first").name("captureHttpTriggers").arguments("""
        {"capture":{"endpoints":[{"roleId":"entry","semanticNodeId":"http-1","externalRoute":false}]}}
        """).build();
    var first = mapper.readTree(control.httpArguments("owned", "trigger", request).arguments());
    var second = mapper.readTree(control.httpArguments("owned", "trigger", request.toBuilder().id("second").build()).arguments());
    assertTrue(first.at("/capture/endpoints/0/externalRoute").isTextual());
    assertEquals("http-1", first.at("/capture/endpoints/0/semanticNodeId").asText());
    assertTrue(second.at("/capture/endpoints/0/semanticNodeId").isBoolean());
    assertTrue(second.at("/capture/endpoints/0/externalRoute").isBoolean());
    assertSame(request, control.httpArguments("owned", "trigger", request));
    try (var events = Files.list(directory.resolve("events"))) { assertEquals(2, events.count()); }
  }

  private void writePlan(String boundary) throws Exception {
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "owned", "boundary", boundary, "mutation", "http-semantic-reference"));
  }

  private static Reference ref() { return new Reference(Kind.CHAIN_PLAN_GRAPH, "graph", "hash"); }
  private static StageExecutionContext context(String conversation) {
    return new StageExecutionContext("run", conversation, "materialization", "key", "attempt",
        null, null, List.of(ref()), Map.of());
  }
}
