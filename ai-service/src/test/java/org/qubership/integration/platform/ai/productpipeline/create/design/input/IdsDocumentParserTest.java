package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

class IdsDocumentParserTest {

  private final IdsDocumentParser parser = new IdsDocumentParser();

  @Test
  void scriptOnlyHttpTriggerIsNotServiceCall() {
    String ids =
        """
        ### Integration flow for CIP Chain - Greetings

        ```mermaid
        sequenceDiagram
            autonumber
            participant Client as Client
            participant CIP as CIP Chain
            Client->>CIP: GET /greetings
            CIP-->>Client: Hello world
        ```
        """;

    NormalizedDesignFlow flow = parser.parseFirstFlow(ids);

    assertEquals("/greetings", flow.trigger().endpointOrTopic());
    assertEquals("GET", flow.trigger().operationName());
    assertTrue(
        flow.steps().stream().noneMatch(step -> "service-call".equalsIgnoreCase(step.kind())),
        () -> flow.steps().toString());
    assertTrue(
        flow.steps().stream().anyMatch(step -> "script".equalsIgnoreCase(step.kind())),
        () -> flow.steps().toString());
  }

  @Test
  void outboundExternalCallRemainsServiceCall() {
    String ids =
        """
        ### Integration flow for CIP Chain - Orders

        ```mermaid
        sequenceDiagram
            autonumber
            participant Client as Client
            participant Orders as Orders API
            Client->>Orders: POST /orders
        ```
        """;

    NormalizedDesignFlow flow = parser.parseFirstFlow(ids);

    assertEquals(1, flow.steps().size());
    assertEquals("service-call", flow.steps().getFirst().kind());
    assertEquals("POST /orders", flow.steps().getFirst().operationQuery());
  }

  /**
   * A repeated flow heading must not hide the diagram below it.
   *
   * <p>Several flows may share one document, so a heading closes the previous section. An author
   * that writes the heading twice — once from the template, once from an instruction quoting it —
   * therefore opens an empty section, and reading only that one rejects a document whose diagram
   * sits right underneath.
   */
  @org.junit.jupiter.api.Test
  void duplicatedFlowHeadingStillFindsTheDiagram() {
    String markdown =
        """
        ## Integration Process

        ### Integration flow for CIP Chain - HealthProxy
        ### Integration flow for CIP Chain - HealthProxy

        ```mermaid
        sequenceDiagram
            autonumber
            participant Client as Client
            participant CIP as HealthProxy Chain
            Client->>CIP: GET /health-proxy
            CIP-->>Client: 200 inventory JSON
        ```
        """;

    var flow = new IdsDocumentParser().parseFirstFlow(markdown);
    org.junit.jupiter.api.Assertions.assertEquals("HealthProxy", flow.chainName());
  }
}
