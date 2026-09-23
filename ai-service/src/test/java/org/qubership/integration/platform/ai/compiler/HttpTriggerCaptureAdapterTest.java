package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;

class HttpTriggerCaptureAdapterTest {

  private static final String CONVERSATION = "http-capture-test";

  @AfterEach
  void clear() {
    HttpTriggerCaptureSession.unbind(CONVERSATION);
  }

  @Test
  void derivesEndpointFieldsFromApprovedRequirements() {
    var binding = binding();
    var capture = new HttpTriggerCapture(List.of(
        new HttpTriggerCapture.Endpoint("http-entry", "http-trigger-1", false)));

    var result = new HttpTriggerCaptureAdapter().adapt(capture, binding);

    assertEquals(1, result.schemaVersion());
    assertEquals(List.of("fact-http"), result.sourceRequirementFactIds());
    assertEquals("POST /tasks", result.triggers().getFirst().label());
    assertEquals("/tasks", result.triggers().getFirst().properties().get(0).value());
    assertEquals("POST", result.triggers().getFirst().properties().get(1).value());
    assertEquals("false", result.triggers().getFirst().properties().get(2).value());
  }

  @Test
  void rejectsUnknownAndMissingApprovedTargets() {
    var binding = binding();
    var adapter = new HttpTriggerCaptureAdapter();

    assertThrows(IllegalArgumentException.class,
        () -> adapter.adapt(new HttpTriggerCapture(List.of()), binding));
    assertThrows(IllegalArgumentException.class,
        () -> adapter.adapt(new HttpTriggerCapture(List.of(
            new HttpTriggerCapture.Endpoint("http-entry", "ghost", false))), binding));
    assertThrows(IllegalArgumentException.class,
        () -> adapter.adapt(new HttpTriggerCapture(List.of(
            new HttpTriggerCapture.Endpoint("http-entry", "http-trigger-1", null))), binding));
  }

  static HttpTriggerCaptureSession.Binding binding() {
    var revision = SemanticFixtures.revision(List.of(
        SemanticFixtures.entry("entry-1", "http-trigger-1")));
    var brief = new RequirementBrief(
        "Tasks", List.of(), List.of(), List.of(), List.of(), "Create tasks", null, "",
        List.of(),
        List.of(new RequirementEntryPoint(
            "entry-1", "fact-http", "http-trigger", "", "POST", "/tasks", "")),
        List.of(), List.of(), List.of(), null, List.of());
    var skeleton = new ElementSkeleton(1, "pattern", List.of("http-entry"),
        List.of(new ElementRole("http-entry", "http-trigger", null, 1, 1)),
        List.of(), List.of(), List.of(), List.of());
    HttpTriggerCaptureSession.bind(CONVERSATION, revision, brief, skeleton);
    return HttpTriggerCaptureSession.get(CONVERSATION).orElseThrow();
  }
}
