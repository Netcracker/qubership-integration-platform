package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.*;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class RequirementAmendmentRuntimeTest {
  @Test
  void v2FactChangeReopensTheDraftAndRestoresTheExactCorrectionAfterRestart() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Clock clock = Clock.systemUTC();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    var runs = new ProductPipelineRunStore(blobs, mapper, clock);
    ProductPipelineProfile profile;
    try (var input = getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profile = ProductPipelineProfileParser.parse(input);
    }
    AtomicReference<StageExecutionContext> resumed = new AtomicReference<>();
    StageCapability discovery = new StageCapability() {
      public String capabilityId() { return "requirement-discovery"; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        resumed.set(context);
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "review corrected draft")));
      }
    };
    var capabilities = new StageCapabilityRegistry(List.of(discovery));
    var narrative = new FailureNarrative(FakeFailureNarrativeAgent.owner("", "")
        .answeringUnder("REQUIREMENT_CHANGE", ""), 12, null);
    var support = ProductPipelineRunSupport.builder(runs, artifacts, capabilities, clock)
        .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile)))
        .failureNarrative(narrative).build();
    String runId = "amendment-v2", chatId = "amendment-chat";
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(),
        profile.profileVersion(), "profile-sha", "baseline", "baseline-sha",
        List.of(new DependencyClosureEntry("requirement-discovery", "1", "discovery-sha")),
        "closure-sha", new KnowledgePackageRef("knowledge", "1", "1.0.0", "checksum", "CERTIFIED", "certificate"),
        "24.4", List.of(new ArtifactTypeRef("user-input", 1)), null);
    var command = new StartOrResumeCommand(chatId, runId, profile, manifest);
    support.bootstrap(command, "flow");
    var draft = RequirementFactFixtures.greetingsApprovedDraft();
    var provenance = new ArtifactProvenance(runId, "requirement-discovery", profile.profileId(),
        profile.profileVersion(), "profile-sha", "requirement-discovery", "1", "closure-sha");
    Reference draftRef = artifacts.append(new AppendCommand(runId, Kind.REQUIREMENT_DRAFT, "2",
        "requirement-discovery", "1", draft, List.of(), null, provenance)).reference();
    Reference hint = artifacts.append(new AppendCommand(runId, Kind.CATALOG_BINDING_HINT, "1",
        "requirement-discovery", "1", Map.of("hint", "companion"), List.of(draftRef), null, provenance)).reference();
    Reference bypass = artifacts.append(new AppendCommand(runId, Kind.IDS_BYPASS, "1",
        "requirement-discovery", "1", Map.of("bypass", true), List.of(draftRef), null, provenance)).reference();
    var brief = new RequirementBrief("Greetings", List.of("GET /greetings"), List.of(), List.of(),
        List.of(), "Return Hello world!", draftRef.artifactId(), draft.planningText(), draft.facts())
        .withFlow(draft.flow());
    Reference briefRef = artifacts.append(new AppendCommand(runId, Kind.REQUIREMENT_BRIEF, "1",
        "requirement-analysis", "1", brief, List.of(draftRef), null,
        new ArtifactProvenance(runId, "requirement-analysis", profile.profileId(), profile.profileVersion(),
            "profile-sha", "requirement-analysis", "1", "closure-sha"))).reference();
    var doc = runs.load(runId).orElseThrow();
    List<StageSnapshot> stages = new ArrayList<>();
    for (var stage : doc.run().stages()) {
      if (stage.stageId().equals("requirement-discovery")) {
        stages.add(new StageSnapshot(stage.stageId(), StageStatus.SUCCEEDED, List.of(draftRef, hint, bypass), null));
      } else if (stage.stageId().equals("requirement-analysis")) {
        stages.add(new StageSnapshot(stage.stageId(), StageStatus.WAITING_FOR_APPROVAL,
            List.of(), null, List.of(briefRef), briefRef, 1));
      } else { stages.add(stage); }
    }
    long revision = doc.run().runRevision();
    runs.commit(revision, new LogicalCommit(runId, revision, RunStatus.WAITING_FOR_APPROVAL,
        "requirement-analysis", stages, new StageAttempt("review", "requirement-analysis", revision + 1,
            StageStatus.WAITING_FOR_APPROVAL, clock.instant(), clock.instant(), List.of(briefRef), null),
        new RunTransition(revision, revision + 1, RunStatus.RUNNING, RunStatus.WAITING_FOR_APPROVAL,
            "requirement-analysis", clock.instant(), "Approve the brief")));
    var conversations = new ConversationService();
    String correction = "Change the greeting to Good morning and remove the body logging requirement.";
    var source = conversations.archive().beginInput(chatId, correction, null);
    conversations.archive().resolveInput(chatId, source, correction);
    support.withConversations(conversations).recordInput(new AcceptInputCommand(runId, correction))
        .collect().asList().await().indefinitely();
    var reopened = runs.load(runId).orElseThrow();
    assertEquals("requirement-discovery", reopened.run().currentStageId());
    assertTrue(reopened.run().stages().stream().filter(stage -> stage.stageId().equals("requirement-analysis"))
        .allMatch(stage -> stage.approvableReference() == null && stage.outputRefs().isEmpty()));
    UserInput associated = artifacts.payload(artifacts.latest(runId, Kind.USER_INPUT).orElseThrow(), UserInput.class);
    assertEquals("requirement-discovery", associated.targetStageId());
    assertEquals(draftRef, associated.draftReference());
    assertEquals(List.of(source.turnId()), associated.sourceTurnIds());
    assertThrows(IllegalStateException.class, () -> support.recordApprove(new ApproveCommand(runId,
        briefRef, reopened.run().runRevision())).collect().asList().await().indefinitely());
    var restored = ProductPipelineRunSupport.builder(runs, artifacts, capabilities, clock)
        .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile))).build();
    restored.restoreForExternalWorkflow(command).collect().asList().await().indefinitely();
    restored.stageExecutor().execute(runId, "requirement-discovery").await().indefinitely();
    assertEquals(correction, resumed.get().attributeAsString("userText"));
    assertEquals(draftRef, resumed.get().attributes().get("requirementCorrectionDraftReference"));
  }
}
