package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GraphPatchCaptureTest {

  @Test
  void acceptsKnowledgeReferenceIdsFromLlmToolInput() throws Exception {
    String json =
        """
        {
          "patchId": "http-trigger-custom-uri",
          "ownerCapabilityId": "cip-http-trigger-endpoint-generator",
          "nodePatches": [],
          "edgePatches": [],
          "propertyPatches": [],
          "chainPatches": [],
          "usedKnowledgeRefs": ["CIP:LEL-000142"],
          "rationale": "Configure the HTTP trigger."
        }
        """;

    GraphPatchCapture capture = new ObjectMapper().readValue(json, GraphPatchCapture.class);

    assertEquals("CIP:LEL-000142", capture.usedKnowledgeRefs().getFirst());
  }
}
