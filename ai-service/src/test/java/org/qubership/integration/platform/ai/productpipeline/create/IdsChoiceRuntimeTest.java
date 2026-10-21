package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.productpipeline.artifact.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.store.*;

class IdsChoiceRuntimeTest {

  @ParameterizedTest
  @CsvSource({"yes,ids-path-choice", "no,ids-path-choice", "yes,other-question", "no,other-question"})
  void nativeAdmissionRecordsIdsOnlyAtTheSavedGate(String answer, String gate) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var clock = Clock.systemUTC();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    var runs = new ProductPipelineRunStore(blobs, mapper, clock);
    ProductPipelineProfile profile;
    try (var input = getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profile = ProductPipelineProfileParser.parse(input);
    }
    String run = "ids-native", chat = "ids-native-chat";
    var store = new RequirementDraftStore();
    var base = RequirementFactFixtures.greetingsApprovedDraft().withIdsRequested(null);
    var partial = new RequirementDraft(false, base.assembledText(), DraftDecision.NEEDS_INPUT,
        List.of("Which endpoint?"), base.sourceSkillId(), base.sourceSkillVersion(), base.sourceSkillHash(),
        null, false, base.facts(), false, null, null, base.flow(), base.catalogBindings(), null);
    store.put(chat, partial);
    var calls = new AtomicInteger();
    var discovery = new RequirementDiscoveryCapability(null, store, (conversation, text) -> {
      calls.incrementAndGet();
      return Multi.createFrom().empty();
    });
    var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts,
        new StageCapabilityRegistry(List.of(discovery)), clock).build();
    var manifest = new RunManifest(run, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile-sha", "baseline", "baseline-sha", List.of(new DependencyClosureEntry(
            "requirement-discovery", "1", "discovery-sha")), "closure-sha",
        new KnowledgePackageRef("knowledge", "1", "1.0.0", "checksum", "CERTIFIED", "certificate"),
        "24.4", List.of(new ArtifactTypeRef("user-input", 1)), null);
    support.bootstrap(new StartOrResumeCommand(chat, run, profile, manifest), "flow");
    var initial = runs.load(run).orElseThrow();
    long revision = initial.run().runRevision();
    runs.commit(revision, new LogicalCommit(run, revision, RunStatus.WAITING_FOR_INPUT,
        "requirement-discovery", initial.run().stages(), new StageAttempt("ids-question", "requirement-discovery",
            revision + 1, StageStatus.WAITING_FOR_INPUT, clock.instant(), clock.instant(), List.of(), null),
        new RunTransition(revision, revision + 1,
        RunStatus.RUNNING, RunStatus.WAITING_FOR_INPUT, "requirement-discovery", clock.instant(),
        PipelineGates.tag(gate, "Select the answer"))));
    support.recordInput(new AcceptInputCommand(run, answer)).collect().asList().await().indefinitely();
    new CreateChainTestOrchestrator(support, runs).executeStage(run, "requirement-discovery")
        .collect().asList().await().indefinitely();
    boolean idsGate = PipelineGates.IDS_PATH_CHOICE.equals(gate);
    assertEquals(idsGate ? answer.equals("yes") : null, store.get(chat).orElseThrow().idsRequested());
    assertEquals(idsGate ? 0 : 1, calls.get());
    assertEquals(partial.facts(), store.get(chat).orElseThrow().facts());
    assertEquals(partial.openQuestions(), store.get(chat).orElseThrow().openQuestions());
    assertEquals(RunStatus.WAITING_FOR_INPUT, runs.load(run).orElseThrow().run().status());
    assertEquals(answer, artifacts.payload(artifacts.latest(run, CompilationArtifacts.Kind.USER_INPUT).orElseThrow(),
        UserInput.class).text());
  }
}
