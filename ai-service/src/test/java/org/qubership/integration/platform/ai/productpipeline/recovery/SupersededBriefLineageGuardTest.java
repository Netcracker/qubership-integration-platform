package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class SupersededBriefLineageGuardTest {

  private static final String RUN_ID = "run-superseded-guard";

  private ProductPipelineArtifactStore artifactStore;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    Clock clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);
    artifactStore =
        new ProductPipelineArtifactStore(
            new CompilationArtifacts(new InMemoryArtifactBlobStore(), mapper, clock));
  }

  @Test
  void rejectsCompileInputDerivedFromSupersededBrief() {
    Revision brief = appendBrief("brief-1", "old goal");
    Reference briefRef = brief.reference();
    Revision plan =
        artifactStore.append(
            appendCommand(
                Kind.IMPLEMENTATION_PLAN, new ImplementationPlan("plan"), List.of(briefRef)));

    Map<String, Object> attributes =
        Map.of(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR, briefRef.contentHash());

    assertTrue(
        SupersededBriefLineageGuard.isSupersededCompileInput(
            artifactStore, RUN_ID, attributes, plan));
  }

  @Test
  void allowsCompileInputFromCurrentBriefLineage() {
    Revision brief = appendBrief("brief-2", "current goal");
    Reference briefRef = brief.reference();
    Revision plan =
        artifactStore.append(
            appendCommand(
                Kind.IMPLEMENTATION_PLAN, new ImplementationPlan("plan"), List.of(briefRef)));

    Map<String, Object> attributes =
        Map.of(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR, "other-brief-hash");

    assertFalse(
        SupersededBriefLineageGuard.isSupersededCompileInput(
            artifactStore, RUN_ID, attributes, plan));
  }

  @Test
  void readsSupersededHashFromRunAttributes() {
    Map<String, Object> attributes =
        Map.of(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR, "brief-hash-old");
    assertTrue(
        SupersededBriefLineageGuard.isSupersededBriefHash(
            SupersededBriefLineageGuard.supersededBriefHash(attributes), "brief-hash-old"));
  }

  @Test
  void rejectsImplementationPlanWhenItsHashWasRecordedOnBriefRepairApproval() {
    Revision plan =
        artifactStore.append(
            appendCommand(Kind.IMPLEMENTATION_PLAN, new ImplementationPlan("plan"), List.of()));

    Map<String, Object> attributes =
        Map.of(ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR, List.of(plan.contentHash()));

    assertTrue(
        SupersededBriefLineageGuard.isSupersededCompileInput(
            artifactStore, RUN_ID, attributes, plan));
  }

  @Test
  void rejectsPriorGraphWhenItsHashWasRecordedOnBriefRepairApproval() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    Revision graphRevision = artifactStore.append(appendCommand(Kind.CHAIN_PLAN_GRAPH, graph, List.of()));

    Map<String, Object> attributes =
        Map.of(
            ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR,
            List.of(graphRevision.contentHash()));

    assertTrue(
        SupersededBriefLineageGuard.isSupersededCompileInput(
            artifactStore, RUN_ID, attributes, graphRevision));
  }

  private Revision appendBrief(String artifactId, String goal) {
    return artifactStore.append(
        appendCommand(
            Kind.REQUIREMENT_BRIEF,
            new RequirementBrief(goal, List.of(), List.of(), List.of(), List.of(), goal),
            List.of()));
  }

  private AppendCommand appendCommand(
      Kind kind, Object payload, List<Reference> inputs) {
    return new AppendCommand(
        RUN_ID,
        kind,
        "1",
        "test",
        "1",
        payload,
        inputs,
        null,
        new ArtifactProvenance(
            RUN_ID, "stage", "create-chain", "1", "profile-sha", "capability", "1", "closure"));
  }
}
