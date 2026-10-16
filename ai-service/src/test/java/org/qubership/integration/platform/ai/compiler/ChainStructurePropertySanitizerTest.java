package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ChainStructurePropertySanitizerTest {

  @Test
  void preservesOnlyUnchangedImportedPropertiesAcrossPlacementChanges() {
    DeterministicElementSchemaService schemas = mock(DeterministicElementSchemaService.class);
    when(schemas.hasElementSchema("chain-call-2")).thenReturn(true);
    when(schemas.allowedPatchPropertyKeys("chain-call-2")).thenReturn(Set.of());
    var sanitizer = new ChainStructurePropertySanitizer(schemas);
    var property = new PlanProperty("block", "true");
    var retained = new ChainPlanNode("call", "chain-call-2", "Call", null, null, List.of(property));
    var base = new ChainPlanGraph("1.0", new ChainSection("Edit", "Edit"), List.of(retained), List.of());
    var moved = new ChainPlanNode("call", "chain-call-2", "Call", "try", 1, List.of(property));
    var changed = new ChainPlanNode("changed", "chain-call-2", "Changed", null, null, List.of(property));
    var captured = new ChainStructure(
        new ChainPlanGraph("1.0", base.chain(), List.of(moved, changed), List.of()), List.of(), List.of());
    var result = sanitizer.sanitize(captured, base);
    assertEquals(List.of(property), result.structure().graph().nodes().getFirst().properties());
    assertEquals(List.of(), result.structure().graph().nodes().getLast().properties());

    when(schemas.hasElementSchema("service-call")).thenReturn(true);
    for (var updated : List.of(
        new ChainPlanNode("call", "chain-call-2", "Call", null, null,
            List.of(new PlanProperty("block", "false"))),
        new ChainPlanNode("call", "service-call", "Call", null, null, retained.properties()),
        new ChainPlanNode("call", "chain-call-2", "Call", null, null,
            List.of(new PlanProperty("failIfNoConsumers", "true"))))) {
      var altered = sanitizer.sanitize(new ChainStructure(
          new ChainPlanGraph("1.0", base.chain(), List.of(updated), List.of()), List.of(), List.of()), base);
      assertEquals(List.of(), altered.structure().graph().nodes().getFirst().properties(), updated.toString());
    }
  }

  @Test
  void removesOnlySchemaUnknownPropertiesAndPreservesTopology() {
    DeterministicElementSchemaService schemaService =
        mock(DeterministicElementSchemaService.class);
    when(schemaService.hasElementSchema("script")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("script")).thenReturn(Set.of("script"));
    ChainStructurePropertySanitizer sanitizer =
        new ChainStructurePropertySanitizer(schemaService);
    ChainPlanEdge edge = new ChainPlanEdge("edge-1", "http-1", "script-1", null);
    ChainStructure capture =
        new ChainStructure(new ChainPlanGraph(
                "1.0",
                new ChainSection("Greeting", "Greeting"),
                List.of(
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Greeting script",
                        null,
                        2,
                        List.of(
                            new PlanProperty("language", "Groovy"),
                            new PlanProperty("script", "return 'hello'")))),
                List.of(edge)),
            List.of("fact-1"),
            List.of());

    ChainStructurePropertySanitizer.SanitizationResult result = sanitizer.sanitize(capture);

    assertEquals(List.of(new PlanProperty("script", "return 'hello'")),
        result.structure().graph().nodes().getFirst().properties());
    assertEquals(List.of(edge), result.structure().graph().edges());
    assertEquals(List.of("fact-1"), result.structure().sourceRequirementFactIds());
    assertEquals(
        List.of(new ChainStructurePropertySanitizer.RemovedProperty(
            "script-1", "script", "language")),
        result.removedProperties());
  }

  @Test
  void preservesPropertiesWhenElementSchemaIsUnavailable() {
    DeterministicElementSchemaService schemaService =
        mock(DeterministicElementSchemaService.class);
    when(schemaService.hasElementSchema("future-element")).thenReturn(false);
    ChainStructurePropertySanitizer sanitizer =
        new ChainStructurePropertySanitizer(schemaService);
    PlanProperty property = new PlanProperty("futureKey", "value");
    ChainStructure capture =
        new ChainStructure(new ChainPlanGraph(
                "1.0",
                new ChainSection("Future", "Future"),
                List.of(new ChainPlanNode(
                    "future-1", "future-element", "Future", null, 1, List.of(property))),
                List.of()),
            List.of(),
            List.of());

    ChainStructurePropertySanitizer.SanitizationResult result = sanitizer.sanitize(capture);

    assertEquals(List.of(property), result.structure().graph().nodes().getFirst().properties());
    assertEquals(List.of(), result.removedProperties());
  }

  @Test
  void keepsMappingIntentIdOnMapper2Shell() {
    DeterministicElementSchemaService schemaService =
        mock(DeterministicElementSchemaService.class);
    when(schemaService.hasElementSchema("mapper-2")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("mapper-2"))
        .thenReturn(Set.of("mappingDescription", "throwException"));
    ChainStructurePropertySanitizer sanitizer =
        new ChainStructurePropertySanitizer(schemaService);
    PlanProperty intentId =
        new PlanProperty(MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, "map-init");
    PlanProperty mapping =
        new PlanProperty(MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY, "[]");
    ChainStructure capture =
        new ChainStructure(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("Orders", "Orders"),
                List.of(
                    new ChainPlanNode(
                        "transform-map-init",
                        "mapper-2",
                        "Map",
                        null,
                        null,
                        List.of(intentId, mapping))),
                List.of()),
            List.of(),
            List.of());

    ChainStructurePropertySanitizer.SanitizationResult result = sanitizer.sanitize(capture);

    assertEquals(
        List.of(intentId, mapping), result.structure().graph().nodes().getFirst().properties());
    assertEquals(List.of(), result.removedProperties());
  }
}
