package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Seam B: a message typed at an approval card is answered when it asks about the candidate and
 * still refines when it asks for a different one. Model wording is the double's, so nothing here
 * asserts prose.
 */
class ProductPipelineApprovalQuestionTest {

  private static final Instant FIXED = Instant.parse("2026-08-21T12:00:00Z");
  private static final String RUN_ID = "run-approval-question-1";
  private static final String STAGE_ID = "requirement-analysis";
  private static final String QUESTION = "does this brief cover the pending pets endpoint?";
  private static final String ANSWER = "The brief covers pending pets and nothing about billing.";

  private final AtomicInteger analysisCalls = new AtomicInteger();

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CreateChainTestOrchestrator runtime;
  private FakeFailureNarrativeAgent agent;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
  }

  @Test
  void aQuestionAtAnApprovalCardIsAnsweredAndStartsNoRefine() {
    runToApproval(answeringAgent());
    long revisionBefore = run().run().runRevision();

    List<PipelineSignal> signals = type(QUESTION);

    assertEquals(ANSWER, onlyMessage(signals));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals(1, analysisCalls.get(), "a question must not re-execute the stage");
    assertEquals(1, agent.approvalQuestionCalls.get());
    assertEquals(
        revisionBefore,
        run().run().runRevision(),
        "answering must not move the revision the open card carries");
  }

  @Test
  void aQuestionLeavesTheCandidateBindingWhereItWas() {
    PipelineSignal.WaitingForApproval card = runToApproval(answeringAgent());
    StageSnapshot before = stage(run());

    List<PipelineSignal> signals = type(QUESTION);

    StageSnapshot after = stage(run());
    assertEquals(before.approvableReference(), after.approvableReference());
    assertEquals(before.candidateReferences(), after.candidateReferences());
    assertEquals(before.candidateRevision(), after.candidateRevision());
    assertEquals(StageStatus.WAITING_FOR_APPROVAL, after.status());
    assertEquals(card.candidate(), approvalCard(signals).candidate());
  }

  @Test
  void anInstructionAtAnApprovalCardStillRefinesTheCandidate() {
    PipelineSignal.WaitingForApproval first = runToApproval(answeringAgent());

    List<PipelineSignal> signals = type("add the billing endpoint too");

    PipelineSignal.WaitingForApproval second = approvalCard(signals);
    assertNotEquals(first.candidate(), second.candidate());
    assertEquals(2, analysisCalls.get());
    assertEquals(Integer.valueOf(2), stage(run()).candidateRevision());
    assertEquals(1, agent.approvalQuestionCalls.get(), "the classifier still reads the message");
    // The refine emits its own candidate-for-review message; what must not appear is an answer.
    assertTrue(
        signals.stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .noneMatch(message -> ANSWER.equals(message.text())),
        "an instruction must not put an answer in the transcript");
  }

  @Test
  void theSameQuestionAgainstTheSameCandidateIsAnsweredTwiceWithOneModelCall() {
    runToApproval(answeringAgent());

    String first = onlyMessage(type(QUESTION));
    String second = onlyMessage(type(QUESTION));

    assertEquals(first, second);
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  @Test
  void theSameQuestionAfterTheCandidateChangedIsAskedAgain() {
    runToApproval(answeringAgent());

    type(QUESTION);
    type("add the billing endpoint too");
    agent.approvalQuestionCalls.set(0);
    List<PipelineSignal> signals = type(QUESTION);

    assertEquals(ANSWER, onlyMessage(signals));
    assertEquals(1, agent.approvalQuestionCalls.get(), "a new candidate is a new cache key");
  }

  @Test
  void theCandidateEvidenceCarriesTheContentHashUnderApproval() {
    PipelineSignal.WaitingForApproval card = runToApproval(answeringAgent());

    type(QUESTION);

    assertTrue(
        agent.lastApprovalCandidate.get().contains(card.candidate().contentHash()),
        agent.lastApprovalCandidate.get());
  }

  @Test
  void aSpentExplanationBudgetDoesNotRefineAQuestion() {
    runToApproval(answeringAgent(), 0);
    long revisionBefore = run().run().runRevision();

    List<PipelineSignal> signals = type(QUESTION);

    assertEquals(ANSWER, onlyMessage(signals));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals(1, analysisCalls.get(), "a spent explanation budget must not start a refine");
    assertEquals(revisionBefore, run().run().runRevision());
  }

  @Test
  void anUnanswerableApprovalQuestionDoesNotRefine() {
    runToApproval(
        FakeFailureNarrativeAgent.slow("Too late.", Duration.ofSeconds(30))
            .answeringOnly(QUESTION, ANSWER),
        Integer.MAX_VALUE,
        Duration.ofMillis(50));
    long revisionBefore = run().run().runRevision();

    List<PipelineSignal> signals = type(QUESTION);

    assertEquals(FailureNarrative.NO_EXPLANATION_AVAILABLE, onlyMessage(signals));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals(1, analysisCalls.get(), "an unanswerable question must not start a refine");
    assertEquals(revisionBefore, run().run().runRevision());
  }

  /** Drives the run to the approval card at {@link #STAGE_ID} and returns the card. */
  private PipelineSignal.WaitingForApproval runToApproval(FakeFailureNarrativeAgent narrativeAgent) {
    return runToApproval(narrativeAgent, Integer.MAX_VALUE, null);
  }

  private PipelineSignal.WaitingForApproval runToApproval(
      FakeFailureNarrativeAgent narrativeAgent, int maxCalls) {
    return runToApproval(narrativeAgent, maxCalls, null);
  }

  private PipelineSignal.WaitingForApproval runToApproval(
      FakeFailureNarrativeAgent narrativeAgent, int maxCalls, Duration timeout) {
    agent = narrativeAgent;
    profile = singleGatedStageProfile();
    ProductPipelineRunSupport support =
        new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(List.of(analysisCapability())),
            null,
            null,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            null,
            null,
            null,
            new FailureNarrative(agent, maxCalls, timeout));
    runtime = new CreateChainTestOrchestrator(support, runStore);
    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-approval-question", RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    PipelineSignal.WaitingForApproval card = approvalCard(type("build pending pets"));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    agent.approvalQuestionCalls.set(0);
    return card;
  }

  private FakeFailureNarrativeAgent answeringAgent() {
    return FakeFailureNarrativeAgent.owner("", "").answeringOnly(QUESTION, ANSWER);
  }

  private List<PipelineSignal> type(String text) {
    return runtime
        .acceptInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private static PipelineSignal.WaitingForApproval approvalCard(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.WaitingForApproval.class::isInstance)
        .map(PipelineSignal.WaitingForApproval.class::cast)
        .reduce((first, second) -> second)
        .orElseThrow(() -> new AssertionError("expected the approval card, got " + signals));
  }

  private static String onlyMessage(List<PipelineSignal> signals) {
    return signals.stream()
        .filter(PipelineSignal.Message.class::isInstance)
        .map(PipelineSignal.Message.class::cast)
        .map(PipelineSignal.Message::text)
        .reduce(
            (first, second) -> {
              throw new AssertionError("expected one answer message, got more");
            })
        .orElseThrow(() -> new AssertionError("expected an answer message"));
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private static StageSnapshot stage(ProductPipelineRunDocument doc) {
    return doc.run().stages().stream()
        .filter(snapshot -> STAGE_ID.equals(snapshot.stageId()))
        .findFirst()
        .orElseThrow();
  }

  /** Emits a fresh brief per call, so a refine is visible as a different candidate. */
  private StageCapability analysisCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return STAGE_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        String userText = context.attributeAsString("userText");
        if (userText == null || userText.isBlank()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need user text")));
        }
        int call = analysisCalls.incrementAndGet();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.REQUIREMENT_BRIEF,
                                Map.of("goal", "brief-" + call + ": " + userText),
                                List.of())),
                        "brief " + call,
                        null)));
      }
    };
  }

  private static ProductPipelineProfile singleGatedStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    return new ProductPipelineProfile(
        1,
        "test-approval-question",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                STAGE_ID,
                STAGE_ID,
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief, List.of(brief)),
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy(STAGE_ID, "PLAN_APPROVED"),
        List.of(STAGE_ID));
  }

  private RunManifest manifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry(STAGE_ID, "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }
}
