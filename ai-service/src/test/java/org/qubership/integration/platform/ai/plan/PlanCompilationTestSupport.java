package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.llm.routing.ConversationPhaseResolver;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

/** Shared in-memory compilation stores for plan lifecycle tests. */
public final class PlanCompilationTestSupport {

  private PlanCompilationTestSupport() {}

  public record Runtime(
      InMemoryArtifactBlobStore blobStore,
      ObjectMapper objectMapper,
      CompilationArtifacts artifacts,
      CompilationSessions sessions,
      RequirementDraftStore requirementDraftStore,
      ChainPlanStore chainPlanStore) {

    public ConversationPhaseResolver phaseResolver() {
      return new ConversationPhaseResolver(requirementDraftStore);
    }
  }

  public static Runtime memory() {
    return memory(new InMemoryArtifactBlobStore());
  }

  public static Runtime memory(InMemoryArtifactBlobStore blobStore) {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.systemUTC());
    CompilationSessions sessions =
        new CompilationSessions(blobStore, mapper, Clock.systemUTC());
    RequirementDraftStore requirementDraftStore =
        new RequirementDraftStore(artifacts, sessions);
    ChainPlanStore chainPlanStore = new ChainPlanStore(artifacts, sessions);
    return new Runtime(
        blobStore, mapper, artifacts, sessions, requirementDraftStore, chainPlanStore);
  }

  public static Revision storeApprovedRequirement(
      Runtime runtime, String conversationId, String requirementText) {
    RequirementDraftStore draftStore = runtime.requirementDraftStore();
    draftStore.put(conversationId, new RequirementDraft(true, requirementText));
    Revision draftRevision = draftStore.latestRevision(conversationId).orElseThrow();
    draftStore.approve(conversationId, draftRevision.reference(), "test-user", null);
    return draftRevision;
  }

  public static Revision storeDesignBypass(Runtime runtime, String conversationId) {
    return storeApprovedRequirement(runtime, conversationId, "requirement");
  }

  public static Revision storeGraph(Runtime runtime, String conversationId, ChainPlanGraph graph) {
    return runtime
        .chainPlanStore()
        .put(conversationId, graph, "test", "1.0")
        .orElseThrow();
  }

  public static Revision storeApprovedDesign(
      Runtime runtime, String conversationId, String requirementText, String designText) {
    return storeApprovedRequirement(runtime, conversationId, requirementText);
  }

  public static Revision storeApprovedPlan(
      Runtime runtime, String conversationId, String planText) {
    if (runtime.chainPlanStore().latestRevision(conversationId).isEmpty()) {
      return storeGraph(runtime, conversationId, sampleGraph("sample-chain"));
    }
    return runtime.chainPlanStore().latestRevision(conversationId).orElseThrow();
  }

  public static Revision storeCurrentBundle(
      Runtime runtime, String conversationId, ChainPlanGraph graph) {
    return storeGraph(runtime, conversationId, graph);
  }

  public static ChainPlanGraph sampleGraph(String chainName) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection(chainName, "Sample"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
