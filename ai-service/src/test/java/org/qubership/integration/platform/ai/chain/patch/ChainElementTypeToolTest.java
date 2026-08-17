package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * The answers a model configures an element from. Each one has to be usable on its own: the model
 * reads it and writes the patch, with no second chance to ask what a phrase meant.
 */
class ChainElementTypeToolTest {

  private DeterministicElementSchemaService schemaService;
  private ChainElementCatalog elementCatalog;
  private ChainElementTypeTool tool;

  @BeforeEach
  void setUp() {
    schemaService = mock(DeterministicElementSchemaService.class);
    elementCatalog = mock(ChainElementCatalog.class);
    tool = new ChainElementTypeTool(schemaService, elementCatalog);
  }

  @Test
  void namesWhatTheTypeAcceptsAndWhatItInsistsOn() {
    when(schemaService.hasElementSchema("service-call")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("service-call"))
        .thenReturn(Set.of("integrationOperationId", "retryCount", "errorThrowing"));
    when(schemaService.requiredPatchPropertyKeys("service-call"))
        .thenReturn(Set.of("integrationOperationId"));

    String answer = tool.describeElementType("service-call");

    assertTrue(answer.contains("Required: integrationOperationId"), answer);
    assertTrue(answer.contains("errorThrowing"), answer);
    assertTrue(answer.contains("retryCount"), answer);
  }

  /** The catalog suffixes the current form of several elements, so the guess is usually near. */
  @Test
  void pointsAtTheRealNameWhenTheTypeWasGuessed() {
    when(schemaService.hasElementSchema("mapper")).thenReturn(false);
    when(elementCatalog.allTypes()).thenReturn(Set.of("mapper-2", "script"));
    when(elementCatalog.isDeprecated("mapper-2")).thenReturn(false);

    String answer = tool.describeElementType("mapper");

    assertTrue(answer.contains("No element type 'mapper' exists"), answer);
    assertTrue(answer.contains("Did you mean 'mapper-2'?"), answer);
  }

  @Test
  void saysSoPlainlyWhenNothingIsNear() {
    when(schemaService.hasElementSchema("teleporter")).thenReturn(false);
    when(elementCatalog.allTypes()).thenReturn(Set.of("script", "service-call"));

    String answer = tool.describeElementType("teleporter");

    assertTrue(answer.contains("No element type 'teleporter' exists"), answer);
    assertTrue(answer.contains("types listed in the request"), answer);
  }

  @Test
  void saysWhenATypeTakesNoPropertiesAtAll() {
    when(schemaService.hasElementSchema("else")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("else")).thenReturn(Set.of());
    when(schemaService.requiredPatchPropertyKeys("else")).thenReturn(Set.of());

    String answer = tool.describeElementType("else");

    assertTrue(answer.contains("No required properties"), answer);
    assertTrue(answer.contains("takes no properties"), answer);
  }

  @Test
  void warnsThatADeprecatedTypeIsTheOldForm() {
    when(schemaService.hasElementSchema("split-element")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("split-element")).thenReturn(Set.of("script"));
    when(schemaService.requiredPatchPropertyKeys("split-element")).thenReturn(Set.of());
    when(elementCatalog.isDeprecated("split-element")).thenReturn(true);

    String answer = tool.describeElementType("split-element");

    assertTrue(answer.contains("Deprecated"), answer);
  }

  @Test
  void asksForATypeWhenGivenNone() {
    assertTrue(tool.describeElementType("  ").contains("elementType is required"));
  }
}
