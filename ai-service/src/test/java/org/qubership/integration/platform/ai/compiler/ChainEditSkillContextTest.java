package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditDisposition;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class ChainEditSkillContextTest {

  @Test
  void aNestAsksForTheSubgraphItAddsAndNamesTheIdsThatMoveIntoIt() {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("wrap-service-call");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_EDIT_INTENT,
            "chain-edit-intent",
            new SkillArtifactPayload.ChainEditIntentPayload(
                new ChainEditIntent(
                    ChainEditAction.ADD_ELEMENTS,
                    List.of("b978e8ff-c89e-462b-b512-25d46dae09e5"),
                    "Add error handling to the service call",
                    null,
                    "try-catch-finally-2",
                    null,
                    List.of(),
                    List.of(),
                    ChainEditDisposition.NEST))));

    String rendered = ChainEditSkillContext.render(workspace);

    assertTrue(rendered.contains("b978e8ff-c89e-462b-b512-25d46dae09e5"), rendered);
    assertTrue(rendered.contains("Capture subgraph, not graph"), rendered);
    assertTrue(rendered.contains("moveExisting"), rendered);
    assertTrue(rendered.contains("A wrapped element is only an id"), rendered);
    assertFalse(rendered.contains("parentNodeId"), rendered);
  }
}
