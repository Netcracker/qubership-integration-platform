package org.qubership.integration.platform.ai.plan.mapping;

import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

public final class ScriptAuthorityTestFixtures {
  private ScriptAuthorityTestFixtures() {}

  public static RequirementFact generatedBehavior(String nodeId, String behavior) {
    var binding = new RequirementBodyBinding(RequirementBodyBinding.Authority.GENERATED_CANDIDATE,
        nodeId, "script", RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
        "fixture-generation", "author-turn", null, null, null);
    var intent = new RequirementElementIntent(nodeId, "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "firstTask", null, null, null, null, List.of(), List.of(), binding);
    return new RequirementFact("behavior-" + nodeId, RequirementFactPolarity.POSITIVE, RequirementFactKind.BEHAVIOR,
        "", behavior, "", "", "", "", "", "", List.of("firstTask"), null, intent);
  }

  public static RequirementFact suppliedFact(String nodeId, String body) {
    return bodyFact(nodeId, body, RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY, 0, body.length());
  }

  public static RequirementFact bodyFact(String nodeId, String body, RequirementBodyBinding.Authority authority,
      int start, int end) {
    var binding = new RequirementBodyBinding(authority, nodeId, "script",
        RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT, "fixture-generation", "author-turn", start, end,
        RequirementBodyBinding.Encoding.RAW);
    var intent = new RequirementElementIntent(nodeId, "script", RequirementElementIntent.Placement.LOCAL_NODE,
        null, null, null, null, null, List.of(new PlanProperty("script", body)), List.of(), binding);
    return new RequirementFact("supplied-" + nodeId, RequirementFactPolarity.POSITIVE, RequirementFactKind.BEHAVIOR,
        "", "Use the supplied script body", "", "", "", "", "", "", List.of(), null, intent);
  }
  public static List<RequirementFact> suppliedFactsWithSupplementaryMetadata(String nodeId, String body) {
    var bodyFact = suppliedFact(nodeId, body);
    var bodyIntent = bodyFact.elementIntent();
    var namedIntent = new RequirementElementIntent(nodeId, "script", bodyIntent.placement(),
        "firstTask", "firstTask", null, null, nodeId, bodyIntent.requestedProperties(), List.of(), bodyIntent.bodyBinding());
    var unnamedIntent = new RequirementElementIntent(nodeId, "script", bodyIntent.placement(),
        "firstTask", null, null, null, null, bodyIntent.requestedProperties(), List.of(), bodyIntent.bodyBinding());
    return List.of(
        new RequirementFact("initializeScript", RequirementFactPolarity.POSITIVE, RequirementFactKind.BEHAVIOR,
            "", "Place the supplied initializer", "", "", "", "", "", "", List.of(), null, namedIntent),
        new RequirementFact("scriptBody", RequirementFactPolarity.POSITIVE, RequirementFactKind.BEHAVIOR,
            "", "Use the supplied body", "", "", "", "", "", "", List.of(), null, unnamedIntent));
  }

  public static org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision
      attachSuppliedFacts(
          org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision revision,
          org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief) {
    var nodes = revision.nodes().stream().map(node -> {
      if (!(node instanceof org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.Operation operation)) return node;
      var ids = new java.util.ArrayList<>(operation.provenance().sourceFactIds());
      brief.facts().stream().filter(fact -> fact.elementIntent() != null
          && operation.nodeId().equals(fact.elementIntent().elementRef()))
          .map(RequirementFact::sourceFactId).filter(id -> !ids.contains(id)).forEach(ids::add);
      return (org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode)
          new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode.Operation(
              operation.nodeId(), operation.elementType(),
              new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance(ids));
    }).toList();
    return new org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision(
        revision.schemaVersion(), revision.revisionId(), revision.chainIdentity(), revision.compilerContractVersion(),
        revision.entryPoints(), nodes, revision.regions(), revision.executionEdges(), revision.containment(),
        revision.mappingIntents(), revision.constraints(), revision.assumptions(), revision.citations());
  }

}
