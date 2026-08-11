package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

class MinimalIdsRendererTest {

  @Test
  void rendersDeterministicOrdersHeaderAndGoldenBody() throws Exception {
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            "Orders",
            "Create order",
            new NormalizedDesignFlow.Trigger(
                "http",
                "p-client",
                "Orders API",
                "/orders",
                "createOrder",
                List.of("fact-trigger")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "p-client", "Client", "EXTERNAL", List.of("fact-p")),
                new NormalizedDesignFlow.Participant(
                    "p-orders-api", "Orders API", "EXTERNAL", List.of("fact-p"))),
            List.of(
                new NormalizedDesignFlow.Step(
                    "step-1",
                    "service-call",
                    "p-client",
                    "p-orders-api",
                    "create order",
                    "",
                    List.of("fact-step"))),
            List.of(),
            List.of(),
            List.of(
                new NormalizedDesignFlow.DataMapping(
                    "map-1",
                    NormalizedDesignFlow.MappingStage.INITIALIZATION,
                    "step-trigger",
                    "step-1",
                    NormalizedDesignFlow.MappingMode.PASS_THROUGH,
                    List.of(),
                    List.of("fact-map"))),
            List.of(),
            List.of());

    String rendered = new MinimalIdsRenderer().render(flow);

    String expectedHeader =
        "# Integration Design Specification\n\n"
            + "## Integration Process\n\n"
            + "### Integration flow for CIP Chain - Orders\n\n"
            + "```mermaid\n"
            + "sequenceDiagram\n"
            + "    autonumber\n";
    assertTrue(rendered.startsWith(expectedHeader), rendered);

    String golden =
        new String(
            Objects.requireNonNull(
                    getClass()
                        .getResourceAsStream("/product-pipelines/design/derived-flow-golden.md"))
                .readAllBytes(),
            StandardCharsets.UTF_8);
    assertEquals(golden, rendered);
  }
}
