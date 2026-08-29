package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
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

  @Test
  void aKeepInsertionAsksForTheSubgraphItAddsAndNamesNoContainer() {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("insert-audit-script");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_EDIT_INTENT,
            "chain-edit-intent",
            new SkillArtifactPayload.ChainEditIntentPayload(
                new ChainEditIntent(
                    ChainEditAction.ADD_ELEMENTS,
                    List.of("b978e8ff-c89e-462b-b512-25d46dae09e5"),
                    "Add a script after the order call",
                    null,
                    "script",
                    null,
                    List.of(),
                    List.of(),
                    ChainEditDisposition.KEEP))));

    String rendered = ChainEditSkillContext.render(workspace);

    assertTrue(rendered.contains("b978e8ff-c89e-462b-b512-25d46dae09e5"), rendered);
    assertTrue(rendered.contains("insertion address"), rendered);
    assertTrue(rendered.contains("Capture subgraph, not graph"), rendered);
    assertTrue(rendered.contains("Name no container and no branches"), rendered);
    assertTrue(rendered.contains("Neither address element"), rendered);
    assertFalse(rendered.contains("moveExisting"), rendered);
  }

  @Test
  void resolvedCatalogIdentityIsNotCopiedIntoThePrompt() {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("rebind-service-call");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_EDIT_INTENT,
            "chain-edit-intent",
            new SkillArtifactPayload.ChainEditIntentPayload(
                new ChainEditIntent(
                    ChainEditAction.REBIND_SERVICE_CALL,
                    List.of("node-1"),
                    "Rebind the service call",
                    "Create order",
                    List.of()))));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.SERVICE_CALL_BINDINGS,
            "service-call-bindings",
            new SkillArtifactPayload.ServiceCallBindingsPayload(
                List.of(
                    new ResolvedServiceCallBinding(
                        "node-1",
                        "call-1",
                        "EXTERNAL",
                        "system-1",
                        "group-1",
                        "specification-1",
                        "operation-1",
                        "http",
                        "POST",
                        "/orders",
                        "Create order",
                        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                        "",
                        "catalog:/v1/operations/operation-1",
                        "")))));

    String rendered = ChainEditSkillContext.render(workspace);

    assertNotNull(rendered);
    assertFalse(rendered.contains("integrationSystemId"), rendered);
    assertFalse(rendered.contains("integrationSpecificationGroupId"), rendered);
    assertFalse(rendered.contains("integrationSpecificationId"), rendered);
    assertFalse(rendered.contains("integrationOperationId"), rendered);
    assertFalse(rendered.contains("integrationOperationProtocolType"), rendered);
    assertFalse(rendered.contains("integrationOperationMethod"), rendered);
    assertFalse(rendered.contains("integrationOperationPath"), rendered);
  }
}
