package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementDraftStoreChainCallSupplyTest {

  @Test
  void chainCallRemovalSupplyConsultsTargetResolverForSlashFreeIds() {
    RequirementTargetResolver resolver = mock(RequirementTargetResolver.class);
    when(resolver.listedChainTrigger("listed-trigger")).thenReturn(true);
    when(resolver.listedChainTrigger("invented-trigger")).thenReturn(false);

    var blobs = new InMemoryArtifactBlobStore();
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions, null, resolver);
    String chat = "chain-call-supply-" + UUID.randomUUID();

    var question = chainQuestion();
    var pinnedInput = RequirementFactFixtures.withNativeAnchors(chainCallDraft(question, null));
    var pinned = RequirementCaptureProjection.toDraft(pinnedInput, null, List.of(), "1", "hash");
    store.put(chat, pinned);
    var baseRef = store.latestRevision(chat).orElseThrow().reference();
    var context = clarificationContext(pinned, question, baseRef);

    var invented = store.admitCaptureDecision(chat, baseRef,
        candidateWithoutQuestion(pinned, "invented-trigger"), null, Map.of(), context, null);
    assertTrue(chainCallRemovalBlocked(invented), invented.issues().toString());

    var listed = store.admitCaptureDecision(chat, baseRef,
        candidateWithoutQuestion(pinned, "listed-trigger"), null, Map.of(), context, null);
    assertFalse(chainCallRemovalBlocked(listed), listed.issues().toString());

    verify(resolver, atLeastOnce()).listedChainTrigger("invented-trigger");
    verify(resolver, atLeastOnce()).listedChainTrigger("listed-trigger");
  }

  private static boolean chainCallRemovalBlocked(RequirementDraftStore.CaptureAdmission admission) {
    return admission.issues().stream().anyMatch(issue -> "chain-target".equals(issue.entityId())
        && issue.message().contains("verified chain trigger"));
  }

  private static RequirementDraftStore.ClarificationAdmissionContext clarificationContext(
      RequirementDraft pinned, QuestionInput question, CompilationArtifacts.Reference draftReference) {
    var coordinator = new RequirementClarificationCoordinator();
    var offer = new ClarificationTransition.Offer("offer-1", draftReference, question.questionId(), 1L, "gen-1");
    return new RequirementDraftStore.ClarificationAdmissionContext(offer, "turn-1", pinned, question,
        coordinator.targetProjection(pinned, question), null, false, "listed-trigger", Set.of());
  }

  private static RequirementDraft candidateWithoutQuestion(RequirementDraft pinned, String targetReference) {
    var authored = pinned.authoredDraft();
    var capabilities = authored.capabilities().stream()
        .map(capability -> "child".equals(capability.interactionId())
            ? new CapabilityInput(capability.interactionId(), capability.capabilityKey(), capability.httpMode(),
                capability.httpMethod(), capability.path(), capability.topic(), capability.mcpServerId(),
                targetReference)
            : capability)
        .toList();
    var candidateInput = new DraftInput(authored.flow(), authored.facts(), capabilities, List.of(), authored.settings());
    return RequirementCaptureProjection.toDraft(candidateInput, pinned, pinned.catalogBindings(),
        pinned.sourceSkillVersion(), pinned.sourceSkillHash());
  }

  private static QuestionInput chainQuestion() {
    return new QuestionInput("chain-target", List.of("child"),
        "Which chain-trigger element id should this chain call use?",
        new AuthorQuestionEvidence("child", "targetReference", AuthorQuestionEvidence.Kind.CATALOG_CHOICE,
            List.of("turn-1")));
  }

  private static DraftInput chainCallDraft(QuestionInput question, String targetReference) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
        new InteractionInput("child", Direction.OUTBOUND, "Existing chain", "Run", null, null, null)),
        List.of(new TransitionInput("entry", "child"))),
        List.of(new FactInput("goal", List.of("child"), FactKind.GOAL, Polarity.POSITIVE,
            "Call the existing chain.")),
        List.of(new CapabilityInput("entry", "http-trigger", null, HttpMethod.GET, "/context", null, null, null),
            new CapabilityInput("child", "chain-call-2", null, null, null, null, null, targetReference)),
        List.of(question), new DraftSettings(false, null));
  }
}
