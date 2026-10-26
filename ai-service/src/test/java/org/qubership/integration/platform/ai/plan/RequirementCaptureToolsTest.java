package org.qubership.integration.platform.ai.plan;

import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogSystemFinder;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult.NextAction;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureToolsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshInapplicablePolicyDoesNotProtectMetadataWhileRetainingAllOtherRequirements(boolean reload)
      throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "fresh-policy-" + UUID.randomUUID();
    String text = policyApplicabilitySource("false");
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
    var original = policyApplicabilityDraft("false", true, true, source.turnId());
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    try (var ignored = ToolSession.open(chat)) {
      var refused = mapper.readValue(h.tools().captureRequirementDraft(original), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_OWNER".equals(issue.code())
          && "prepare".equals(issue.entityId())), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "INVALID_POLICY_CONFLICT".equals(issue.code())
          && "/draft/facts/access-policy/policyConflict".equals(issue.path())), refused.toString());
      assertTrue(h.store().get(chat).isEmpty());
      var rejectedRef = h.store().rejectedCaptureRevision(chat).orElseThrow();
      var immutableAttempt = mapper.valueToTree(artifacts.payload(rejectedRef,
          RequirementDraftStore.RequirementCaptureAttempt.class));
      assertEquals(mapper.valueToTree(original), immutableAttempt.path("arguments"));
      assertEquals(mapper.valueToTree(refused.issues()), immutableAttempt.path("issues"));
      assertEquals(source.turnId(), immutableAttempt.path("sourceTurnId").asText());
      var retained = h.store().rejectedCaptureFacts(chat);
      var policy = retained.stream().filter(fact -> fact.sourceFactId().equals("access-policy")).findFirst().orElseThrow();
      assertEquals(withPolicy(original.facts().get(2), null), policy);
      assertEquals(original.facts().get(1), retained.stream()
          .filter(fact -> fact.sourceFactId().equals("route-anchor")).findFirst().orElseThrow());
      assertEquals(original.facts().get(4), retained.stream()
          .filter(fact -> fact.sourceFactId().equals("audit")).findFirst().orElseThrow());
      var protection = mapper.valueToTree(h.store().repairConstraints(chat));
      if (reload) {
        h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
        assertEquals(protection, mapper.valueToTree(h.store().repairConstraints(chat)));
        assertEquals(immutableAttempt, mapper.valueToTree(artifacts.payload(rejectedRef,
            RequirementDraftStore.RequirementCaptureAttempt.class)));
      }
      var corrected = policyApplicabilityDraft("false", false, false, source.turnId());
      var accepted = mapper.readValue(h.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.toString());
      assertEquals(corrected, h.store().get(chat).orElseThrow().authoredDraft());
      var finished = mapper.readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertTrue(finished.accepted(), finished.toString());
      var restored = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
      assertEquals(corrected, restored.store().get(chat).orElseThrow().authoredDraft());
      assertEquals(immutableAttempt, mapper.valueToTree(artifacts.payload(rejectedRef,
          RequirementDraftStore.RequirementCaptureAttempt.class)));
      assertEquals(source.turnId(), ConversationArchive.latestInputId(archive.read(chat)));
      assertEquals(text, archive.read(chat).activeTurns().getFirst().message().content());
      assertTrue(new PolicyChoiceSelection(artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations).pending(chat, 8).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", " true "})
  void explicitSameOwnerExternalRouteRetainsTheNormalTypedPolicyCardAfterReload(String exposure) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "applicable-policy-" + UUID.randomUUID();
    String text = policyApplicabilitySource(exposure);
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
    var input = policyApplicabilityDraft(exposure, false, true, source.turnId());
    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      var finished = mapper.readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY),
          RequirementCaptureResult.class);
      assertTrue(finished.accepted(), finished.toString());
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var choices = new PolicyChoiceSelection(artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations);
      var offered = choices.pending(chat, 7).orElseThrow();
      assertEquals(h.store().latestRevision(chat).orElseThrow().reference(), offered.draftReference());
      assertEquals(input.facts().get(2), offered.fact());
      assertEquals(input.openQuestions().getFirst(), offered.question());
      var card = choices.decision(offered, 7);
      assertEquals(List.of("keep-requested", "use-recommended"), card.actions());
      assertEquals(Map.of("keep-requested", "Keep NONE", "use-recommended", "Use recommended RBAC"), card.actionLabels());
      var restored = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
      assertEquals(input, restored.store().get(chat).orElseThrow().authoredDraft());
      assertEquals(offered, new PolicyChoiceSelection(artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations).pending(chat, 7).orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "conflicting", "invalid", "unsupported", "duplicate",
      "negative", "local", "null-value"})
  void policyApplicabilityRequiresItsOwnValidNativeCondition(String defect) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "unproved-policy-" + UUID.randomUUID();
    String text = policyApplicabilitySource("true");
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
    var base = policyApplicabilityDraft("true", false, true, source.turnId());
    var facts = new ArrayList<>(base.facts());
    var anchor = facts.get(1);
    var intent = anchor.elementIntent();
    var properties = new ArrayList<>(intent.requestedProperties());
    if (List.of("missing", "foreign", "invalid", "unsupported", "null-value").contains(defect)) {
      properties.removeIf(property -> property.key().equals("externalRoute"));
    }
    if (defect.equals("invalid")) properties.add(new PlanProperty("externalRoute", "not-a-boolean"));
    if (defect.equals("unsupported")) properties.add(new PlanProperty("publicRoute", "true"));
    if (defect.equals("null-value")) properties.add(new PlanProperty("externalRoute", null));
    if (defect.equals("duplicate")) properties.add(new PlanProperty("externalRoute", "true"));
    var changed = new RequirementElementIntent(defect.equals("local") ? "nested-route" : intent.elementRef(),
        intent.elementType(), defect.equals("local") ? RequirementElementIntent.Placement.LOCAL_NODE : intent.placement(),
        intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(),
        intent.parentElementRef(), intent.requestedName(), properties, intent.exactValues());
    facts.set(1, new FactInput(anchor.sourceFactId(), anchor.interactionIds(), anchor.kind(),
        defect.equals("negative") ? Polarity.NEGATIVE : anchor.polarity(), anchor.text(), null, null, changed));
    var flow = base.flow();
    var capabilities = base.capabilities();
    if (defect.equals("conflicting")) {
      facts.add(new FactInput("contradictory-exposure", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "The same route is internal.", null, null, new RequirementElementIntent("entry", "http-trigger",
              RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
              List.of(new PlanProperty("externalRoute", "false")), List.of())));
    }
    if (defect.equals("foreign")) {
      flow = new FlowInput(List.of(base.flow().interactions().getFirst(),
          new InteractionInput("other-entry", Direction.INBOUND, "Other caller", "POST /other", null, null, null)),
          base.flow().transitions());
      capabilities = List.of(base.capabilities().getFirst(),
          new CapabilityInput("other-entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/other", null, null, null));
      facts.add(new FactInput("other-route", List.of("other-entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Another route has a separate exposure setting.", null, null,
          new RequirementElementIntent("other-entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
              "other-entry", null, null, null, List.of(new PlanProperty("externalRoute", "true")), List.of())));
    }
    var input = new DraftInput(flow, facts, capabilities, base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(chat)) {
      var refused = mapper.readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "INVALID_POLICY_CONFLICT".equals(issue.code())
          && "/draft/facts/access-policy/policyConflict".equals(issue.path())), refused.toString());
      assertTrue(h.store().get(chat).isEmpty());
      assertEquals(withPolicy(base.facts().get(2), null), h.store().rejectedCaptureFacts(chat).stream()
          .filter(fact -> fact.sourceFactId().equals("access-policy")).findFirst().orElseThrow());
      assertEquals(source.turnId(), ConversationArchive.latestInputId(archive.read(chat)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"facts-null", "null-fact", "flow-null", "properties-null"})
  void malformedPolicyContextFailsClosedWithoutThrowing(String defect) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), policyApplicabilitySource("true"));
    var base = policyApplicabilityDraft("true", false, true, "source");
    List<FactInput> facts = new ArrayList<>(base.facts());
    if (defect.equals("null-fact")) facts.set(1, null);
    if (defect.equals("properties-null")) {
      var fact = facts.get(1);
      var intent = fact.elementIntent();
      facts.set(1, withIntent(fact, new RequirementElementIntent(intent.elementRef(), intent.elementType(),
          intent.placement(), intent.ownerInteractionId(), null, null, null, intent.requestedName(), null, intent.exactValues())));
    }
    var input = new DraftInput(defect.equals("flow-null") ? null : base.flow(),
        defect.equals("facts-null") ? null : facts, base.capabilities(), base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var refused = assertDoesNotThrow(() -> h.mapper().readValue(h.tools().captureRequirementDraft(input),
          RequirementCaptureResult.class));
      assertFalse(refused.accepted(), refused.toString());
      assertFalse(refused.issues().isEmpty());
      assertTrue(h.store().get(h.conversationId()).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"literal", "name", "property", "text", "target", "order", "fact"})
  void correctingFreshInvalidPolicyCannotDiscardOtherProtectedRequirements(String loss) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), policyApplicabilitySource("false"));
    var original = policyApplicabilityDraft("false", true, true, "source");
    try (var ignored = ToolSession.open(h.conversationId())) {
      assertFalse(h.mapper().readValue(h.tools().captureRequirementDraft(original), RequirementCaptureResult.class).accepted());
      var corrected = policyApplicabilityDraft("false", false, false, "source");
      var facts = new ArrayList<>(corrected.facts());
      var audit = facts.get(4);
      var intent = audit.elementIntent();
      var properties = new ArrayList<>(intent.requestedProperties());
      if (loss.equals("property")) properties.removeIf(property -> property.key().equals("logLevel"));
      var changedIntent = new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
          intent.ownerInteractionId(), intent.successorElementRef(), loss.equals("order") ? "prepare-step" : intent.predecessorElementRef(),
          intent.parentElementRef(), loss.equals("name") ? null : intent.requestedName(), properties,
          loss.equals("literal") ? List.of() : intent.exactValues());
      facts.set(4, new FactInput(audit.sourceFactId(), loss.equals("target") ? List.of() : audit.interactionIds(),
          audit.kind(), audit.polarity(), loss.equals("text") ? audit.text() + " Revised." : audit.text(), null, null, changedIntent));
      if (loss.equals("fact")) facts.remove(4);
      var input = new DraftInput(corrected.flow(), facts, corrected.capabilities(), corrected.openQuestions(), corrected.settings());
      var refused = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "audit".equals(issue.entityId())), refused.toString());
      assertEquals(audit, h.store().rejectedCaptureFacts(h.conversationId()).stream()
          .filter(fact -> fact.sourceFactId().equals("audit")).findFirst().orElseThrow());
      assertTrue(h.store().get(h.conversationId()).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historicalProtectedPolicyMetadataIsNotReleasedByFreshApplicabilityDiagnostics(boolean reload) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "historical-policy-" + UUID.randomUUID();
    String text = policyApplicabilitySource("false");
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
    var legacy = policyApplicabilityDraft("false", false, true, source.turnId());
    // This negative starts with metadata persisted by the earlier metadata-only validation contract.
    h.store().rememberRejectedCaptureFacts(chat, legacy.facts());
    var protectedBefore = mapper.valueToTree(h.store().rejectedCaptureFacts(chat));
    if (reload) h = policyApplicabilityHarness(blobs, mapper, conversations, chat, text);
    try (var ignored = ToolSession.open(chat)) {
      var removal = policyApplicabilityDraft("false", false, false, source.turnId());
      var refused = mapper.readValue(h.tools().captureRequirementDraft(removal), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
          && "access-policy".equals(issue.entityId())), refused.toString());
      assertEquals(protectedBefore, mapper.valueToTree(h.store().rejectedCaptureFacts(chat)));
      assertTrue(h.store().get(chat).isEmpty());
      assertEquals(source.turnId(), ConversationArchive.latestInputId(archive.read(chat)));
    }
  }

  @Test
  void oneExternalOwnerCannotAuthorizeAMultiOwnerPolicyConflict() throws Exception {
    var h = harness();
    h.store().beginTurn(h.conversationId(), policyApplicabilitySource("true"));
    var base = policyApplicabilityDraft("true", false, true, "source");
    var facts = new ArrayList<>(base.facts());
    var policy = facts.get(2);
    facts.set(2, new FactInput(policy.sourceFactId(), List.of("entry", "other-entry"), policy.kind(),
        policy.polarity(), policy.text(), null, policy.policyConflict(), null));
    var input = new DraftInput(new FlowInput(List.of(base.flow().interactions().getFirst(),
        new InteractionInput("other-entry", Direction.INBOUND, "Other caller", "POST /other", null, null, null)), List.of()),
        facts, List.of(base.capabilities().getFirst(), new CapabilityInput("other-entry", "http-trigger",
            HttpMode.CUSTOM, HttpMethod.POST, "/other", null, null, null)),
        List.of(new RequirementCaptureInput.QuestionInput("security-choice", List.of("entry", "other-entry"), "Keep NONE or choose RBAC?")),
        base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var refused = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "INVALID_POLICY_CONFLICT".equals(issue.code())
          && issue.path().endsWith("/policyConflict")), refused.toString());
      assertTrue(h.store().get(h.conversationId()).isEmpty());
    }
  }

  private static Harness policyApplicabilityHarness(InMemoryArtifactBlobStore blobs, ObjectMapper mapper,
      ConversationService conversations, String chat, String text) {
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(mapper);
    h.store().beginTurn(chat, text);
    return h;
  }

  private static String policyApplicabilitySource(String exposure) {
    return "Create chain Policy validation. Use an HTTP trigger named Receive checks with externalRoute=" + exposure
        + " and accessControlType=NONE. Receive POST /checks. Use the local Script Prepare response to leave the body unchanged."
        + " After entry, Audit completion logs kept-value at logLevel Info. No IDS.";
  }

  private static DraftInput policyApplicabilityDraft(String exposure, boolean invalidOwner, boolean policyPresent,
      String sourceId) {
    var policy = new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC",
        "security-choice", null);
    var properties = new ArrayList<>(List.of(new PlanProperty("accessControlType", "NONE")));
    if (exposure != null) properties.add(new PlanProperty("externalRoute", exposure));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST /checks", "Receive the request.", null, null)), List.of()), List.of(
        new FactInput("chain-name", List.of(), FactKind.PARAMETER, Polarity.POSITIVE, "Chain name: Policy validation.",
            null, null, new RequirementElementIntent("chain", "chain", RequirementElementIntent.Placement.CHAIN,
                null, null, null, null, "Policy validation", List.of(), List.of())),
        new FactInput("route-anchor", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
            "Receive checks uses externalRoute=" + exposure + " and accessControlType=NONE.", null, null,
            new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                "entry", null, null, null, "Receive checks", properties, List.of("NONE"))),
        new FactInput("access-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            "Keep accessControlType=NONE.", null, policyPresent ? policy : null, null),
        new FactInput("prepare", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Prepare response leaves the body unchanged.", null, null,
            new RequirementElementIntent("prepare-step", "script", RequirementElementIntent.Placement.LOCAL_NODE,
                invalidOwner ? "foreign-owner" : "entry", null, null, null, "Prepare response", List.of(), List.of())),
        new FactInput("audit", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Audit completion logs kept-value at logLevel Info.", null, null,
            new RequirementElementIntent("audit-step", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
                "entry", null, "entry", null, "Audit completion",
                List.of(new PlanProperty("message", "kept-value"), new PlanProperty("logLevel", "Info")), List.of("kept-value")))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/checks", null, null, null)),
        policyPresent ? List.of(new RequirementCaptureInput.QuestionInput("security-choice", List.of("entry"),
            "Keep NONE or choose RBAC?", new AuthorQuestionEvidence("entry", "accessControlType",
                AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of(sourceId)))) : List.of(), new DraftSettings(false, null));
  }

  private static FactInput withPolicy(FactInput fact, RequirementPolicyConflict policy) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
        fact.fieldMapping(), policy, fact.elementIntent());
  }

  private static FactInput withIntent(FactInput fact, RequirementElementIntent intent) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
        fact.fieldMapping(), fact.policyConflict(), intent);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRejectedMappingMayBecomeLocalBehaviorWithoutLosingItsAuthorFact(boolean intermediateMissingMapping)
      throws Exception {
    var h = harness();
    String chat = "rejected-mapping-kind";
    var fact = new FactInput("reply", List.of("entry", "remote"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Return the remote response to the caller.",
        new RequirementCaptureInput.FieldMappingInput("remote", "status", "entry", "status", null));
    var flow = new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null),
        new InteractionInput("remote", Direction.OUTBOUND, "Remote", "POST /submit", null, null, null)),
        List.of(new RequirementCaptureInput.TransitionInput("entry", "remote")));
    var original = new DraftInput(flow, List.of(fact), List.of(), List.of(), new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, "Receive POST /work, submit it, and return the remote response to the caller. No IDS.");
      var refused = h.mapper().readValue(h.tools().captureRequirementDraft(original), RequirementCaptureResult.class);
      assertFalse(refused.accepted());
      assertTrue(refused.issues().stream().anyMatch(issue -> "MAPPING_TRANSITION_REQUIRED".equals(issue.code())));
      if (intermediateMissingMapping) {
        var incomplete = new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(),
            fact.polarity(), fact.text());
        var stillRefused = h.mapper().readValue(h.tools().captureRequirementDraft(new DraftInput(flow,
            List.of(incomplete), original.capabilities(), original.openQuestions(), original.settings())),
            RequirementCaptureResult.class);
        assertFalse(stillRefused.accepted());
        assertTrue(stillRefused.issues().stream().anyMatch(issue -> "MAPPING_FIELDS_REQUIRED".equals(issue.code())));
      }
      var corrected = new FactInput(fact.sourceFactId(), fact.interactionIds(), FactKind.BEHAVIOR,
          fact.polarity(), fact.text());
      var accepted = h.mapper().readValue(h.tools().captureRequirementDraft(new DraftInput(flow,
          List.of(corrected), original.capabilities(), original.openQuestions(), original.settings())),
          RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.toString());
      assertEquals(fact.text(), accepted.draft().facts().getFirst().text());
      assertEquals(FactKind.BEHAVIOR, accepted.draft().facts().getFirst().kind());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void partialDraftCannotAskBeforeCompletingNativeMetadata(boolean suppliedRole) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "partial-anchor-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit it."
        + (suppliedRole ? " Allow exactly the role task-operator." : " Ask which role may access the route.");
    var turn = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, turn, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.store().beginTurn(chat, text);
    var base = readyHttpDraft();
    var question = new RequirementCaptureInput.QuestionInput("route-role", List.of("entry"), "Which role?",
        new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence("entry", "roles",
            org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of(turn.turnId())));
    var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(),
        new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            suppliedRole ? "Allow exactly the role task-operator." : "Ask which role may access the route.")),
        base.capabilities(), List.of(question), base.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertTrue(mapper.readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class).accepted());
      var refused = mapper.readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY),
          RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> "MISSING_ANCHOR_INTENT".equals(issue.code())),
          refused.toString());
      var entry = nativeAnchorFact("entry", "http-trigger");
      var intent = entry.elementIntent();
      if (suppliedRole) {
        entry = new FactInput(entry.sourceFactId(), entry.interactionIds(), entry.kind(), entry.polarity(),
            "Allow exactly the role task-operator.", null, null,
            new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
                intent.ownerInteractionId(), null, null, null,
                List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", "[\"task-operator\"]")),
                List.of("task-operator")));
      }
      var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(entry, nativeAnchorFact("submit", "http-sender")), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), suppliedRole ? List.of(question.questionId()) : List.of(), null);
      var corrected = mapper.readValue(h.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertTrue(corrected.accepted(), corrected.toString());
      var finished = mapper.readValue(h.tools().finishRequirementDiscoveryTurn(suppliedRole
          ? RequirementDiscoveryDirective.CONTINUE : RequirementDiscoveryDirective.STAY), RequirementCaptureResult.class);
      assertTrue(finished.accepted(), finished.toString());
      assertEquals(suppliedRole, h.store().get(chat).orElseThrow().openQuestions().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void referencesToRejectedIdentitiesRemainRepairable(boolean predecessor) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(),
        "Accept POST /tasks. Use a Script named Prepare stage and a Log Record named Audit stage with message audit-event.");
    var base = readyHttpDraft();
    RequirementCaptureResult rejected = null;
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (String target : List.of("invalid target", "prepare-step")) {
        var prepare = new FactInput("prepare-behavior", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Prepare stage leaves the body unchanged.", null, null, new RequirementElementIntent(target, "script",
                RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, null, null, "Prepare stage", List.of(), List.of()));
        var audit = new FactInput("audit-behavior", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Audit stage logs audit-event.", null, null, new RequirementElementIntent("audit-step", "log-record",
                RequirementElementIntent.Placement.LOCAL_NODE, "entry", predecessor ? null : target,
                predecessor ? target : null, null, "Audit stage",
                List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("message", "audit-event")),
                List.of("audit-event")));
        var facts = new ArrayList<>(base.facts());
        facts.addAll(List.of(prepare, audit));
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(withNativeAnchors(new DraftInput(
            base.flow(), facts, base.capabilities(), base.openQuestions(), base.settings()))), RequirementCaptureResult.class);
        if (target.equals("invalid target")) {
          assertFalse(result.accepted());
          rejected = result;
        } else {
          assertTrue(result.accepted(), result.toString());
          assertTrue(h.store().get(h.conversationId()).orElseThrow().authoredDraft().facts().containsAll(facts));
          assertTrue(h.mapper().readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
              RequirementCaptureResult.class).accepted());
        }
      }
      assertNotNull(rejected);
      String field = predecessor ? "predecessorElementRef" : "successorElementRef";
      assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals("UNKNOWN_ELEMENT_REFERENCE")
          && issue.path().equals("/draft/facts/audit-behavior/elementIntent/" + field)), rejected.toString());
    }
  }

  @Test
  void rejectedPropertyCarriesItsRuntimeSchemaAndCorrectedJsonTextCanContinue() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(),
        "Accept POST /tasks with RBAC role qip-auditor, then submit one task to https://example.com/tasks.");
    var base = readyHttpDraft();
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (String roles : List.of("{}", "[\"qip-auditor\"]")) {
        var intent = new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("accessControlType", "RBAC"),
                new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", roles)), List.of());
        var fact = new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            "Use RBAC role qip-auditor.", null, null, intent);
        var facts = new ArrayList<>(base.facts());
        facts.add(fact);
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(withNativeAnchors(new DraftInput(
            base.flow(), facts, base.capabilities(), base.openQuestions(), base.settings()))), RequirementCaptureResult.class);
        if (roles.equals("{}")) {
          assertFalse(result.accepted());
          var issue = result.issues().stream().filter(i -> i.code().equals("INVALID_REQUESTED_PROPERTY"))
              .findFirst().orElseThrow();
          assertTrue(issue.message().contains(h.tools().elementSchemas.describeElementProperty("http-trigger", "roles")),
              issue.message());
        } else {
          assertTrue(result.accepted(), result.toString());
          assertTrue(h.mapper().readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
              RequirementCaptureResult.class).accepted());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid local ref", "9invalid"})
  void rejectedLocalIdentityDoesNotBecomeAProtectedValue(String invalidRef) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(),
        "Accept POST /tasks, log audit-event with a Log Record named Audit stage, then submit one task.");
    var base = readyHttpDraft();
    RequirementCaptureResult rejected = null;
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (String ref : List.of(invalidRef, "audit-step")) {
        var intent = new RequirementElementIntent(ref, "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, "entry", null, "Audit stage",
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("message", "audit-event")),
            List.of("audit-event"));
        var fact = new FactInput("audit-requirement", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Log audit-event in Audit stage.", null, null, intent);
        var facts = new ArrayList<>(base.facts());
        facts.add(fact);
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(new DraftInput(base.flow(), facts,
            base.capabilities(), base.openQuestions(), base.settings())), RequirementCaptureResult.class);
        if (ref.equals(invalidRef)) {
          assertFalse(result.accepted());
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_VALUE")
              && issue.path().equals("/draft/facts/audit-requirement/elementIntent/elementRef")
              && issue.entityId() == null), result.toString());
          rejected = result;
        } else {
          assertTrue(result.accepted(), result.toString());
          assertEquals(facts, h.store().get(h.conversationId()).orElseThrow().authoredDraft().facts());
        }
      }
      assertNotNull(rejected);
      var protectedFact = rejected.repairConstraints().facts().stream()
          .filter(fact -> fact.sourceFactId().equals("audit-requirement")).findFirst().orElseThrow();
      assertEquals("Log audit-event in Audit stage.", protectedFact.text());
      assertTrue(protectedFact.elementIntent() == null);
    }
  }

  @Test
  void scriptBehaviorBindsToItsOwnedArtifactBeforeTheScriptIsGenerated() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Accept POST /tasks and use a Script to respond with accepted-body.");
    var base = readyHttpDraft();
    var intent = new RequirementElementIntent("reply", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "entry", null, List.of(), List.of("accepted-body"));
    var fact = new FactInput("reply", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "The Script responds with accepted-body.", null, null, intent);
    try (var ignored = ToolSession.open(h.conversationId())) {
      var result = h.mapper().readValue(h.tools().captureRequirementDraft(new DraftInput(base.flow(),
          List.of(base.facts().getFirst(), fact), base.capabilities(), base.openQuestions(), base.settings())),
          RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
      assertEquals(intent, h.store().get(h.conversationId()).orElseThrow().authoredDraft().facts().getLast().elementIntent());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"audit-value", "POST"})
  void requiredLiteralNeedsAnOwnedRuntimeBinding(String literal) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Accept POST /tasks and log exactly " + literal + ".");
    var base = readyHttpDraft();
    var intent = new RequirementElementIntent("audit", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "entry", null, List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("logLevel", "Info")), List.of(literal));
    var fact = new FactInput("audit", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Log exactly " + literal + ".", null, null, intent);
    var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
        base.capabilities(), base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var result = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(result.accepted(), result.toString());
      assertTrue(result.issues().stream().anyMatch(i -> "UNBOUND_REQUIRED_LITERAL".equals(i.code())), result.toString());
      assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("message")
          && i.message().contains("describeElementProperty")), result.toString());
      for (boolean keepLevel : List.of(false, true)) {
        var props = new ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
        props.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("message", literal));
        if (keepLevel) props.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("logLevel", "Info"));
        var corrected = new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
            intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(), null, props, intent.exactValues());
        var candidate = new DraftInput(base.flow(), List.of(base.facts().getFirst(), new FactInput(fact.sourceFactId(),
            fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(), null, null, corrected)),
            base.capabilities(), base.openQuestions(), base.settings());
        var outcome = h.mapper().readValue(h.tools().captureRequirementDraft(candidate), RequirementCaptureResult.class);
        assertEquals(keepLevel, outcome.accepted(), outcome.toString());
        if (!keepLevel) assertTrue(outcome.issues().stream().anyMatch(i -> "REPAIR_FACT_LOSS".equals(i.code())));
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aSavedAnswerMustResolveItsQuestionOrExplainTheRemainingAmbiguity(boolean ambiguous) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "answer-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit it. Ask for the target URI.";
    var first = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, first, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.store().beginTurn(chat, text);
    var base = readyHttpDraft();
    var missing = new CapabilityInput("submit", "http-sender", null, HttpMethod.POST, null, null, null, null);
    var question = new RequirementCaptureInput.QuestionInput("target-uri", List.of("submit"), "Which URI?",
        new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence("submit", "path",
            org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of(first.turnId())));
    var input = withNativeAnchors(new DraftInput(base.flow(), base.facts(),
        List.of(base.capabilities().getFirst(), missing), List.of(question), base.settings()));
    try (var ignored = ToolSession.open(chat)) {
      assertTrue(mapper.readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class).accepted());
      assertTrue(mapper.readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY),
          RequirementCaptureResult.class).accepted());
      String reply = ambiguous ? "I have not chosen the URI yet." : "Use https://example.com/tasks";
      var answer = archive.beginInput(chat, reply, null);
      archive.resolveInput(chat, answer, reply);
      h.store().beginTurn(chat, reply);
      h.store().associateClarification(chat);
      assertEquals("UNAPPLIED_AUTHOR_ANSWER", h.store().validateAuthorQuestions(chat).getFirst().code());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, reply);
      assertEquals("UNAPPLIED_AUTHOR_ANSWER", restored.store().validateAuthorQuestions(chat).getFirst().code());
      var reviewed = new RequirementCaptureInput.QuestionInput(question.questionId(), question.interactionIds(),
          "Which exact target URI should be used?", new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence(
              "submit", "path", org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.AMBIGUOUS_ANSWER,
              List.of(first.turnId(), answer.turnId())));
      var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), ambiguous ? List.of() : List.of(base.capabilities().getLast()), List.of(), List.of(),
          ambiguous ? List.of(reviewed) : List.of(), ambiguous ? List.of() : List.of(question.questionId()), null);
      var result = mapper.readValue(restored.tools().updateRequirementDraft(update), RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
      assertTrue(restored.store().validateAuthorQuestions(chat).isEmpty());
      assertEquals(ambiguous, !restored.store().get(chat).orElseThrow().openQuestions().isEmpty());
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,true", "false,false", "true,false"})
  void nativeChainCallClarificationUsesTheCatalogPickerWithoutChangingAuthorQuestions(
      boolean selected, boolean otherQuestions)
      throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var bindings = mock(RequirementDraftTool.class);
    when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var chains = mock(org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder.class);
    var first = new org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto();
    first.id = "first-trigger";
    first.chainName = "Chain trigger + Header modification";
    var second = new org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto();
    second.id = "second-trigger";
    second.chainName = first.chainName;
    when(chains.listChainTriggers()).thenReturn(List.of(first, second));
    var feedback = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper));
    var tools = new RequirementCaptureTools(store, bindings, mapper,
        new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback), feedback,
        new RequirementTargetResolver(chains,
            mock(org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder.class)));
    var methodQuestion = new RequirementCaptureInput.QuestionInput("method", List.of("entry"),
        "Which HTTP method should the custom HTTP trigger accept?");
    var targetQuestion = new RequirementCaptureInput.QuestionInput("target", List.of("call"),
        "Please select the specific chain-trigger element ID for the Chain trigger + Header modification chain.");
    var rolesQuestion = new RequirementCaptureInput.QuestionInput("roles", List.of("entry"),
        "Which roles may access the HTTP trigger?");
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Client", "HTTP /call", null, null, null),
        new InteractionInput("call", Direction.OUTBOUND, first.chainName, "Invoke", null, null, null)),
        List.of(new TransitionInput("entry", "call"))),
        List.of(new FactInput("goal", List.of("call"), FactKind.GOAL, Polarity.POSITIVE, "Call the header chain.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM,
                otherQuestions ? null : HttpMethod.GET, "/call", null, null, null),
            new CapabilityInput("call", "chain-call-2", null, null, null, null, null,
                selected ? second.id : null)),
        otherQuestions ? List.of(methodQuestion, targetQuestion, rolesQuestion) : List.of(targetQuestion),
        new DraftSettings(null, null));
    String chat = "chain-picker-" + UUID.randomUUID();
    store.beginTurn(chat, "Call the Chain trigger + Header modification chain through an HTTP GET trigger.");
    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      var saved = store.get(chat).orElseThrow();
      var expectedQuestions = new ArrayList<String>();
      if (otherQuestions) expectedQuestions.add(methodQuestion.text());
      if (!selected) expectedQuestions.add(
          "Chain-call call has no unique chain-trigger yet. Choose the catalog chain to call.");
      if (otherQuestions) expectedQuestions.add(rolesQuestion.text());
      assertEquals(expectedQuestions, saved.openQuestions());
      assertEquals(selected ? Map.of("call", second.id) : Map.of(),
          RequirementCaptureProjection.resolvedTargets(saved));
      assertEquals(input, saved.authoredDraft());
      var refreshed = mapper.readValue(tools.readRequirementDraft(), RequirementCaptureResult.class);
      assertEquals(input, refreshed.draft());
      assertEquals(saved.openQuestions(), store.get(chat).orElseThrow().openQuestions());
      assertEquals(!selected, refreshed.pendingWork().stream()
          .anyMatch(work -> work.code().equals("TARGET_LOOKUP_REQUIRED")));
      assertEquals(selected && !otherQuestions, saved.readyForPlan());
      if (selected && !otherQuestions) assertTrue(refreshed.pendingWork().isEmpty(), refreshed.toString());
    }
  }

  @Test
  void namedOutboundOperationBindsBeforeUploadedSpecificationImport() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var resolutions = new ConversationApiResolutions();
    var catalog = mock(CatalogRestClient.class);
    var om = new CatalogRestClient.SystemDto("om", "om-order-lifecycle-manager-WFMS", "INTERNAL", "kafka");
    when(catalog.filterSystems(any())).thenAnswer(invocation -> {
      List<org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter> filters = invocation.getArgument(0);
      return filters.stream().anyMatch(filter -> "lifecycle".equals(filter.value())) ? List.of(om) : List.of();
    });
    when(catalog.getApiSpecifications("om")).thenReturn(List.of(
        new CatalogRestClient.SpecificationDto("om-spec", "1.33", "om-group", "om", "1.33")));
    when(catalog.getOperations(eq("om-spec"), eq(0), anyInt(), isNull())).thenReturn(List.of(
        new CatalogRestClient.OperationDto("om-onTaskStart", "onTaskStart", "publish", "task.start", "om-spec"),
        new CatalogRestClient.OperationDto("om-onTaskResult", "onTaskResult", "subscribe", "order.command", "om-spec")));
    var cache = new ConversationCatalogCache(new CatalogOperationsReadCache(catalog));
    var reads = new CatalogSystemReadTool(catalog, new CatalogOperationsLookupService(cache), null);
    var lookup = new CatalogOperationLookup(new CatalogSystemFinder(catalog), reads);
    var uploaded = mock(UploadedSpecOperations.class);
    when(uploaded.pendingInteractionIds(anyString(), any())).thenReturn(java.util.Set.of("create-task"));
    var fingerprints = new ToolCallFingerprintStore(mapper);
    var feedback = new CaptureAttemptFeedbackStore(fingerprints);
    var tools = new RequirementCaptureTools(store, RequirementDraftTool.withLookup(store, resolutions, lookup), mapper,
        new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null, null, uploaded, null);
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("task-start", Direction.INBOUND, "om-order-lifecycle-manager-WFMS", "onTaskStart", null, null, null),
        new InteractionInput("create-task", Direction.OUTBOUND, "Salesforce WFM", "createTask", null, null, null),
        new InteractionInput("task-result", Direction.OUTBOUND, "om-order-lifecycle-manager-WFMS", "onTaskResult", null, null, null)),
        List.of(new TransitionInput("task-start", "create-task"), new TransitionInput("create-task", "task-result"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create a task and return its result.")),
        List.of(new CapabilityInput("task-start", "async-api-trigger", null, null, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
    try (var ignored = ToolSession.open("om-upload")) {
      var captured = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      assertEquals(List.of("UPLOADED_SPEC_IMPORT_REQUIRED"),
          captured.pendingWork().stream().map(RequirementCaptureProjection.PendingWork::code).toList());
      assertEquals("om-onTaskResult", store.get("om-upload").orElseThrow()
          .catalogBinding("task-result").orElseThrow().integrationOperationId());
      var continued = mapper.readValue(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertTrue(continued.accepted(), continued.toString());
      assertEquals(NextAction.RUN_IMPORT, continued.nextAction());
      assertEquals(input, continued.draft());
    }
  }

  @Test
  void asyncClassifierRepairPreservesFactsAndChangesOnlyTheInvalidProperty() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    DraftInput original;
    try (var fixture = getClass().getResourceAsStream("/requirements/asyncapi-invalid-classifier.json")) {
      original = h.mapper().readValue(fixture, DraftInput.class);
    }
    try (var ignored = ToolSession.open(h.conversationId())) {
      var rejected = h.mapper().readValue(h.tools().captureRequirementDraft(original), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      var issue = rejected.issues().getFirst();
      assertEquals("INVALID_REQUESTED_PROPERTY", issue.code());
      assertTrue(issue.message().contains("catalog operation"), issue.message());
      assertTrue(issue.message().contains("exactValues"), issue.message());
      assertTrue(issue.message().contains("elementIntent"), issue.message());

      var lost = new DraftInput(original.flow(), original.facts().stream()
          .filter(fact -> !"async-trigger-anchor".equals(fact.sourceFactId())).toList(),
          original.capabilities(), original.openQuestions(), original.settings());
      var loss = h.mapper().readValue(h.tools().captureRequirementDraft(lost), RequirementCaptureResult.class);
      assertFalse(loss.accepted());
      assertTrue(loss.issues().stream().anyMatch(finding -> "REPAIR_FACT_LOSS".equals(finding.code())
          && "async-trigger-anchor".equals(finding.entityId())));

      var facts = original.facts().stream().map(fact -> {
        if (!"async-trigger-anchor".equals(fact.sourceFactId())) return fact;
        var intent = fact.elementIntent();
        return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
            fact.fieldMapping(), fact.policyConflict(), new RequirementElementIntent(intent.elementRef(),
                intent.elementType(), intent.placement(), intent.ownerInteractionId(), intent.successorElementRef(),
                intent.predecessorElementRef(), intent.parentElementRef(), intent.requestedName(),
                intent.requestedProperties().stream().filter(property -> !"topicsClassifierName".equals(property.key())).toList(),
                List.of("cip-auto-tests-topic2")));
      }).toList();
      var repaired = new DraftInput(original.flow(), facts, original.capabilities(), original.openQuestions(), original.settings());
      var result = h.mapper().readValue(h.tools().captureRequirementDraft(repaired), RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
      assertEquals(repaired, result.draft());
      assertEquals(original.facts().stream().map(FactInput::text).toList(),
          result.draft().facts().stream().map(FactInput::text).toList());
    }
  }

  @Test
  void captureSchemaOffersOnlySupportedRuntimeCapabilities() {
    var root = RequirementCaptureSchemas.parameters("capability", CapabilityInput.class);
    var capability = (JsonObjectSchema) root.properties().get("capability");
    var keys = (JsonEnumSchema) capability.properties().get("capabilityKey");
    var expected = new java.util.HashSet<>(
        org.qubership.integration.platform.ai.schema.ChainElementFamilies.TRIGGERS);
    expected.addAll(org.qubership.integration.platform.ai.schema.ChainElementFamilies.SENDERS);
    expected.addAll(org.qubership.integration.platform.ai.schema.ChainElementFamilies.FILE_TRANSFER);
    expected.add("chain-call-2");
    expected.removeAll(List.of("scs-sender", "sds-trigger"));
    assertEquals(expected, new java.util.HashSet<>(keys.enumValues()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void correlationSurvivesCaptureAndMaterializationWhileIncompleteSettingsStopBeforeWrite(boolean complete)
      throws Exception {
    var h = harness();
    var schemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.tools().elementSchemas = schemas;
    h.store().beginTurn(h.conversationId(),
        "Send GET to https://example.invalid and receive correlation ID from header X-Correlation-ID.");
    var properties = new java.util.ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
    properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("receiveCorrelationId", "true"));
    if (complete) {
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("correlationIdName", "X-Correlation-ID"));
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("correlationIdPosition", "header"));
    }
    var intent = new RequirementElementIntent("submit", "http-sender", RequirementElementIntent.Placement.ANCHOR,
        "submit", null, null, null, properties, List.of());
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), List.of(new FactInput("correlation", List.of("submit"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Receive correlation ID.", null, null, intent)),
        List.of(base.capabilities().getFirst(),
            new CapabilityInput("submit", "http-sender", null, null, null, null, null, null)), List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var capture = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(capture.accepted(), capture.toString());
    }
    var graph = new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph("1.0",
        new org.qubership.integration.platform.ai.plan.model.ChainSection("Correlation", null),
        List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
            "submit", "http-sender", "Send", null, null, List.of())), List.of());
    graph = org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues.apply(
        graph, h.store().get(h.conversationId()).orElseThrow().facts());
    var catalog = mock(org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.class);
    var current = new org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto();
    current.properties = Map.of("httpMethod", "GET", "uri", "https://example.invalid", "catalogMetadata", "keep");
    when(catalog.getElement("chain", "element")).thenReturn(current);
    var materializer = new org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer(
        catalog, schemas, h.mapper());
    var result = materializer.apply(graph,
        new org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap(
            "chain", Map.of("submit", "element"), Map.of(), Map.of()));
    assertEquals(complete ? 1 : 0, result.patchedCount(), result.toString());
    if (complete) {
      ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
      verify(catalog).updateElement(eq("chain"), eq("element"), patch.capture());
      Map<?, ?> actual = (Map<?, ?>) patch.getValue().get("properties");
      assertEquals("X-Correlation-ID", actual.get("correlationIdName"));
      assertEquals("header", actual.get("correlationIdPosition"));
      assertEquals("GET", actual.get("httpMethod"));
      assertEquals("https://example.invalid", actual.get("uri"));
      assertEquals("keep", actual.get("catalogMetadata"));
    } else {
      verify(catalog, never()).updateElement(anyString(), anyString(), any());
      assertTrue(result.firstValidationError().contains("correlationId"), result.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"elementId", "block", "failIfNoConsumers", "timeout"})
  void chainCallCaptureKeepsResolverIdentitySeparateFromRuntimeProperties(String key) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Invoke the selected chain with timeout 30000.");
    var base = readyHttpDraft();
    var intent = new RequirementElementIntent("submit", "chain-call-2", RequirementElementIntent.Placement.ANCHOR,
        "submit", null, null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key,
            key.equals("timeout") ? "30000" : key.equals("failIfNoConsumers") ? "false" : "true")), List.of());
    var input = new DraftInput(base.flow(), List.of(new FactInput("call", List.of("submit"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Invoke the selected chain.", null, null, intent)),
        List.of(base.capabilities().getFirst(),
            new CapabilityInput("submit", "chain-call-2", null, null, null, null, null, null)),
        List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var rejected = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      if (!key.equals("elementId")) {
        assertTrue(rejected.accepted(), rejected.toString());
        return;
      }
      assertFalse(rejected.accepted());
      var issue = rejected.issues().stream().filter(value -> value.code().equals("INVALID_REQUESTED_PROPERTY"))
          .findFirst().orElseThrow();
      assertFalse(issue.message().contains("elementId"), issue.message());
      assertTrue(issue.message().contains("timeout"), issue.message());
      assertTrue(h.store().get(h.conversationId()).isEmpty());
    }
  }


  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidAnchorRepairUpdatesOnlyItsDependentPlacementReference(boolean successor) throws Exception {
    var h = harness();
    h.store().beginTurn(h.conversationId(), "Receive requests, publish the message, then deliver the result.");
    var base = readyHttpDraft();
    var flow = new FlowInput(List.of(base.flow().interactions().getFirst(), base.flow().interactions().getLast(),
        new InteractionInput("deliver", Direction.OUTBOUND, "Result Service", "Deliver", null, null, null)),
        List.of(new TransitionInput("entry", "submit"), new TransitionInput("submit", "deliver")));
    var capabilities = List.of(base.capabilities().getFirst());
    String owner = successor ? "entry" : "deliver";
    var properties = List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(
        successor ? "accessControlType" : "errorThrowing", successor ? "RBAC" : "true"));
    java.util.function.Function<String, FactInput> dependent = ref -> new FactInput("dependent", List.of(owner),
        FactKind.BEHAVIOR, Polarity.POSITIVE, "Keep the requested endpoint configuration.", null, null,
        new RequirementElementIntent(owner, successor ? "http-trigger" : "service-call",
            RequirementElementIntent.Placement.ANCHOR, owner, successor ? ref : null, successor ? null : ref,
            null, null, properties, List.of()));
    java.util.function.Function<String, FactInput> anchor = ref -> new FactInput("publish", List.of("submit"),
        FactKind.BEHAVIOR, Polarity.POSITIVE, "Publish the requested message.", null, null,
        new RequirementElementIntent(ref, "service-call", RequirementElementIntent.Placement.ANCHOR,
            "submit", null, null, null, List.of(), List.of()));
    try (var ignored = ToolSession.open(h.conversationId())) {
      var rejected = h.mapper().readValue(h.tools().captureRequirementDraft(new DraftInput(flow,
          List.of(dependent.apply("submit-anchor"), anchor.apply("submit-anchor")), capabilities,
          List.of(), base.settings())), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      var corrected = new DraftInput(flow, List.of(dependent.apply("submit"), anchor.apply("submit")),
          capabilities, List.of(), base.settings());
      var accepted = h.mapper().readValue(h.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.toString());
      assertEquals(corrected, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
      var changedText = new FactInput("dependent", List.of(owner), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Drop the endpoint configuration.", null, null, corrected.facts().getFirst().elementIntent());
      assertFalse(h.mapper().readValue(h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(changedText), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null)), RequirementCaptureResult.class).accepted());
    }
  }

  @Test
  void rejectedMethodGuessReturnsTheProtectedInteractionValues() throws Exception {
    var h = harness();
    h.store().beginTurn(h.conversationId(), "Receive HTTP requests at /tasks with RBAC.");
    var entry = new InteractionInput("entry", Direction.INBOUND, "Caller", "HTTP trigger at /tasks",
        "External HTTP trigger with RBAC.", null, null);
    var initial = new DraftInput(new FlowInput(List.of(entry), List.of()), List.of(),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, null, "/tasks", null, null, null)),
        List.of(), new DraftSettings(null, null));
    try (var ignored = ToolSession.open(h.conversationId())) {
      assertTrue(h.mapper().readValue(h.tools().captureRequirementDraft(initial), RequirementCaptureResult.class).accepted());
      var guessed = new InteractionInput("entry", Direction.INBOUND, "Caller", "POST HTTP trigger at /tasks",
          "External POST HTTP trigger with RBAC.", null, null);
      var result = h.mapper().readValue(h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(guessed),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), null)), RequirementCaptureResult.class);
      assertFalse(result.accepted());
      for (String field : List.of("operation", "description")) {
        String expected = field.equals("operation") ? entry.operation() : entry.description();
        var issue = result.issues().stream().filter(value -> value.path().equals("/interactions/entry/" + field))
            .findFirst().orElseThrow();
        assertEquals("AUTHOR_AMENDMENT_REQUIRED", issue.code());
        assertTrue(issue.message().contains(h.mapper().writeValueAsString(expected)), issue.message());
      }
      assertEquals(initial, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
    }
  }

  @Test
  void sameSourceMappingRepairChangesOnlyAuthorizedPaths() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "mapping-path-repair-" + UUID.randomUUID();
    String text = "Map Subject from the input to the task request.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    var base = readyHttpDraft();
    java.util.function.BiFunction<String, String, FactInput> mapping = (path, expression) -> new FactInput(
        "subject", List.of("entry", "submit"), FactKind.FIELD_MAPPING, Polarity.POSITIVE, text,
        new RequirementCaptureInput.FieldMappingInput("entry", "$.Subject", "submit", path, expression));
    var sibling = new FactInput("external-id", List.of("entry", "submit"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Map id to ExternalId.",
        new RequirementCaptureInput.FieldMappingInput("entry", "$.id", "submit", "$.ExternalId", null));
    var initial = new DraftInput(base.flow(), List.of(base.facts().getFirst(), mapping.apply("$.Task.Subject", null), sibling),
        base.capabilities(), base.openQuestions(), base.settings());
    java.util.function.Function<FactInput, DraftUpdate> update = fact -> new DraftUpdate(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(fact), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), null);
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, text);
      assertTrue(mapper.readValue(h.tools().captureRequirementDraft(initial), RequirementCaptureResult.class).accepted());
      h.store().beginTurn(chat, "Correct the catalog mapping path.");
      var corrected = mapping.apply("$.Subject", null);
      assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(corrected)),
          RequirementCaptureResult.class).accepted());
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.bindDiscovery(
          "mapping-run", chat, h.store().get(chat).orElseThrow(), Map.of("subject", java.util.Set.of("targetPath")), value -> {});
      try {
        var repaired = mapper.readValue(h.tools().updateRequirementDraft(update.apply(corrected)),
            RequirementCaptureResult.class);
        assertTrue(repaired.accepted(), repaired.toString());
        var changedSource = new FactInput(corrected.sourceFactId(), corrected.interactionIds(), corrected.kind(),
            corrected.polarity(), corrected.text(),
            new RequirementCaptureInput.FieldMappingInput("entry", "$.Other", "submit", "$.Subject", null));
        assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(changedSource)),
            RequirementCaptureResult.class).accepted());
        var changedSibling = new FactInput(sibling.sourceFactId(), sibling.interactionIds(), sibling.kind(),
            sibling.polarity(), sibling.text(), corrected.fieldMapping());
        assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(changedSibling)),
            RequirementCaptureResult.class).accepted());
        assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(mapping.apply("$.Subject", "changed"))),
            RequirementCaptureResult.class).accepted());
        var goal = new FactInput(base.facts().getFirst().sourceFactId(), List.of(), FactKind.GOAL,
            Polarity.POSITIVE, "Changed goal");
        assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(goal)),
            RequirementCaptureResult.class).accepted());
      } finally {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
      }
      assertFalse(mapper.readValue(h.tools().updateRequirementDraft(update.apply(mapping.apply("$.Other", null))),
          RequirementCaptureResult.class).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidMappingRepairRetainsProposalWithoutProtectingUnrelatedAdditions(boolean reload) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "mapping-retention-" + UUID.randomUUID();
    String mappingText = "Map Subject from the input to the task request.";
    String text = mappingText + " Map id to ExternalId. Retain request context.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    var ready = readyHttpDraft();
    java.util.function.Function<String, FactInput> mapping = path -> new FactInput(
        "subject", List.of("entry", "submit"), FactKind.FIELD_MAPPING, Polarity.POSITIVE, mappingText,
        new RequirementCaptureInput.FieldMappingInput("entry", "$.Subject", "submit", path, null));
    var sibling = new FactInput("external-id", List.of("entry", "submit"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Map id to ExternalId.",
        new RequirementCaptureInput.FieldMappingInput("entry", "$.id", "submit", "$.ExternalId", null));
    var unrelated = new FactInput("context-extra", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Retain request context.");
    var initial = new DraftInput(ready.flow(),
        List.of(ready.facts().getFirst(), mapping.apply("$.Task.Subject"), sibling),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var corrected = mapping.apply("$.Subject");
    var malformed = new DraftUpdate(List.of(), List.of(), List.of(),
        List.of(initial.flow().transitions().getFirst()), List.of(), List.of(unrelated),
        List.of(corrected), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    var focused = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(corrected), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    var expectedPending = new DraftInput(initial.flow(),
        List.of(initial.facts().getFirst(), corrected, sibling, unrelated),
        initial.capabilities(), initial.openQuestions(), initial.settings());
    var expectedAccepted = new DraftInput(initial.flow(),
        List.of(initial.facts().getFirst(), corrected, sibling),
        initial.capabilities(), initial.openQuestions(), initial.settings());
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, text);
      var captured = mapper.readValue(h.tools().captureRequirementDraft(initial), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      var selected = h.store().get(chat).orElseThrow();
      var acceptedRef = h.store().latestRevision(chat).orElseThrow().reference();
      var sourceBefore = mapper.valueToTree(archive.read(chat));
      var paths = Map.of("subject", java.util.Set.of("targetPath"));
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.bindDiscovery(
          "mapping-retention-run", chat, selected, paths, value -> {});
      try {
        var rejected = mapper.readValue(h.tools().updateRequirementDraft(malformed), RequirementCaptureResult.class);
        assertFalse(rejected.accepted(), rejected.toString());
        assertTrue(rejected.issues().stream().anyMatch(issue -> "CONFLICTING_EDIT".equals(issue.code())
            && "/changes/addTransitions/0".equals(issue.path())), rejected.toString());
        assertTrue(rejected.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
            && "/draft/facts/context-extra".equals(issue.path()) && "context-extra".equals(issue.entityId())
            && issue.message().contains("Accepted value: <absent>")
            && issue.message().contains(unrelated.text())
            && issue.message().contains("/draft/facts/subject/fieldMapping/targetPath")), rejected.toString());
        var rejectedRef = h.store().rejectedCaptureRevision(chat).orElseThrow().reference();
        var pendingBefore = mapper.readValue(h.tools().readRequirementDraft(), RequirementCaptureResult.class)
            .pendingProposal();
        assertNotNull(pendingBefore);
        assertEquals(expectedPending, pendingBefore.draft());
        assertEquals(rejectedRef, pendingBefore.attempt());
        assertEquals(acceptedRef, pendingBefore.base());
        assertEquals(source.turnId(), pendingBefore.sourceTurnId());
        assertEquals(archive.read(chat).generation(), pendingBefore.sourceGeneration());
        assertEquals("updateRequirementDraft", pendingBefore.toolName());
        assertEquals(rejected.issues(), pendingBefore.issues());
        if (reload) {
          org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
          h = persistentHarness(blobs, mapper, conversations, chat);
          h.store().beginTurn(chat, text);
          org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.bindDiscovery(
              "mapping-retention-run", chat, selected, paths, value -> {});
        }
        assertEquals(initial, h.store().get(chat).orElseThrow().authoredDraft());
        assertEquals(acceptedRef, h.store().latestRevision(chat).orElseThrow().reference());
        assertEquals(sourceBefore, mapper.valueToTree(archive.read(chat)));
        assertEquals(pendingBefore, mapper.readValue(h.tools().readRequirementDraft(), RequirementCaptureResult.class)
            .pendingProposal());
        var protectedBeforeRepair = h.store().rejectedCaptureFacts(chat);
        var repaired = mapper.readValue(h.tools().updateRequirementDraft(focused), RequirementCaptureResult.class);
        var active = h;
        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertFalse(protectedBeforeRepair.contains(unrelated),
                "An out-of-scope proposal remains readable but must not become a protected fact"),
            () -> assertTrue(repaired.accepted(), repaired.toString()),
            () -> assertEquals(expectedAccepted, active.store().get(chat).orElseThrow().authoredDraft()));
        assertEquals(source.turnId(), ConversationArchive.latestInputId(archive.read(chat)));
        assertEquals(text, h.store().currentAuthorText(chat).orElseThrow());
      } finally {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
      }
    }
  }

  @Test
  void malformedInScopeMappingRepairRetainsNativeFeedbackAndAcceptedBase() throws Exception {
    var h = harness();
    var ready = readyHttpDraft();
    java.util.function.Function<String, FactInput> mapping = path -> new FactInput(
        "subject", List.of("entry", "submit"), FactKind.FIELD_MAPPING, Polarity.POSITIVE,
        "Map Subject to the task request.",
        new RequirementCaptureInput.FieldMappingInput("entry", "$.Subject", "submit", path, null));
    var initial = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), mapping.apply("$.Task.Subject")),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    java.util.function.Function<String, DraftUpdate> update = path -> new DraftUpdate(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(mapping.apply(path)), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), null);
    var paths = Map.of("subject", java.util.Set.of("targetPath"));
    for (List<FactInput> invalidFacts : java.util.Arrays.<List<FactInput>>asList(
        null, java.util.Collections.singletonList(null))) {
      assertFalse(org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability
          .preservesMappingRepair(paths, List.of(), initial, new DraftInput(initial.flow(), invalidFacts,
              initial.capabilities(), initial.openQuestions(), initial.settings())));
    }
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.store().beginTurn(h.conversationId(), "Submit one task. Map Subject to the task request.");
      assertTrue(h.mapper().readValue(h.tools().captureRequirementDraft(initial),
          RequirementCaptureResult.class).accepted());
      var selected = h.store().get(h.conversationId()).orElseThrow();
      var acceptedRef = h.store().latestRevision(h.conversationId()).orElseThrow().reference();
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.bindDiscovery(
          "malformed-mapping-run", h.conversationId(), selected, paths, value -> {});
      try {
        var encoded = assertDoesNotThrow(() -> h.tools().updateRequirementDraft(update.apply(null)));
        var rejected = h.mapper().readValue(encoded, RequirementCaptureResult.class);
        assertFalse(rejected.accepted(), rejected.toString());
        assertTrue(rejected.issues().stream().anyMatch(issue -> "MAPPING_FIELD_REQUIRED".equals(issue.code())),
            rejected.toString());
        assertFalse(rejected.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())),
            rejected.toString());
        assertEquals(initial, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
        assertEquals(acceptedRef, h.store().latestRevision(h.conversationId()).orElseThrow().reference());
        var repaired = h.mapper().readValue(h.tools().updateRequirementDraft(update.apply("$.Subject")),
            RequirementCaptureResult.class);
        assertTrue(repaired.accepted(), repaired.toString());
      } finally {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
      }
    }
  }

  @Test
  void finishingARepeatedCaptureRetainsTheOriginalIssue() throws Exception {
    var h = harness();
    var base = readyHttpDraft();
    var invalid = new DraftInput(base.flow(), List.of(base.facts().getFirst(), base.facts().getFirst()),
        base.capabilities(), base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.store().beginTurn(h.conversationId(), "Submit one task.");
      h.tools().captureRequirementDraft(invalid);
      var noticed = h.mapper().readValue(h.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      var rejected = assertThrows(CaptureNoProgressException.class, () -> h.tools().captureRequirementDraft(invalid));
      String original = rejected.evidence().feedback().issues().stream()
          .findFirst().orElseThrow().code();
      var rejectedReference = h.store().rejectedCaptureRevision(h.conversationId()).orElseThrow().reference();
      var authorText = h.store().currentAuthorText(h.conversationId());
      var acceptedBefore = h.store().latestRevision(h.conversationId());
      for (var directive : List.of(RequirementDiscoveryDirective.CONTINUE, RequirementDiscoveryDirective.STAY)) {
        var encoded = assertDoesNotThrow(() -> h.tools().finishRequirementDiscoveryTurn(directive));
        var finished = h.mapper().readValue(encoded, RequirementCaptureResult.class);
        assertFalse(finished.accepted(), finished.toString());
        assertFalse(finished.changed());
        assertEquals(NextAction.STOP, finished.nextAction());
        assertEquals(RequirementDiscoveryDirective.STAY, h.store().turnDirective(h.conversationId()));
        assertTrue(finished.issues().stream().anyMatch(issue -> original.equals(issue.code())), finished.toString());
        assertTrue(finished.issues().stream().anyMatch(issue -> "REPEATED_REJECTION".equals(issue.code())));
        assertEquals(rejected.evidence().feedback().issues(), finished.issues().stream()
            .filter(issue -> !"REPEATED_REJECTION".equals(issue.code()))
            .map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
                issue.code(), issue.path(), issue.message(), issue.entityId())).toList());
        assertEquals(acceptedBefore, h.store().latestRevision(h.conversationId()));
        assertEquals(rejectedReference,
            h.store().rejectedCaptureRevision(h.conversationId()).orElseThrow().reference());
        assertEquals(authorText, h.store().currentAuthorText(h.conversationId()));
      }
    }
  }

  @Test
  void contextOverrideRepresentationCanBeRepairedWithoutLosingItsFact() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Forward with context and override X-Request-Id with override-request-id.");
    var base = readyHttpDraft();
    java.util.function.BiFunction<String, Boolean, DraftInput> candidate = (value, invalidOrder) -> {
      var intent = new RequirementElementIntent("submit", "http-sender",
          RequirementElementIntent.Placement.ANCHOR, "submit", invalidOrder ? "entry" : null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("propagateContext", "true"),
              new org.qubership.integration.platform.ai.plan.model.PlanProperty("overrideContextParams", value)),
          List.of("override-request-id"));
      var fact = new FactInput("context-override", List.of("submit"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Forward with context and override X-Request-Id with override-request-id.", null, null, intent);
      return new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
          base.capabilities(), base.openQuestions(), base.settings());
    };
    try (var ignored = ToolSession.open(h.conversationId())) {
      var initial = h.mapper().readValue(h.tools().captureRequirementDraft(
          candidate.apply("X-Request-Id=override-request-id", true)), RequirementCaptureResult.class);
      assertFalse(initial.accepted());
      assertTrue(initial.issues().stream().anyMatch(issue -> "INVALID_REQUESTED_PROPERTY".equals(issue.code())
          && issue.path().endsWith("/requestedProperties/overrideContextParams")), initial.toString());
      assertTrue(initial.issues().stream().anyMatch(issue -> "ELEMENT_FLOW_ORDER_MISMATCH".equals(issue.code())));
      var partial = h.mapper().readValue(h.tools().captureRequirementDraft(
          candidate.apply("{\"X-Request-Id\":\"override-request-id\"}", true)), RequirementCaptureResult.class);
      assertFalse(partial.accepted());
      assertFalse(partial.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())), partial.toString());
      var corrected = candidate.apply("{\"X-Request-Id\":\"override-request-id\"}", false);
      var repaired = h.mapper().readValue(h.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(corrected.facts(), h.store().get(h.conversationId()).orElseThrow().authoredDraft().facts());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void structuralRejectionDoesNotFreezeInvalidProperties(boolean restoreStore) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "structural-property-repair-" + UUID.randomUUID();
    String text = "Accept POST /tasks. Run Prepare request in Groovy; export the script as groovy.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var harness = persistentHarness(blobs, mapper, conversations, chat);
    harness.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(mapper);
    harness.store().beginTurn(chat, text);
    var base = readyHttpDraft();
    java.util.function.BiFunction<String, Boolean, DraftInput> candidate = (anchor, invalidProperty) -> {
      var trigger = new FactInput("entry-config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Accept POST /tasks.", null, null, new RequirementElementIntent(anchor, "http-trigger",
              RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, List.of(), List.of()));
      var properties = new java.util.ArrayList<org.qubership.integration.platform.ai.plan.model.PlanProperty>();
      properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("exportFileExtension", "groovy"));
      if (invalidProperty) {
        properties.add(new org.qubership.integration.platform.ai.plan.model.PlanProperty("language", "Groovy"));
      }
      var script = new FactInput("prepare-script", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Run Prepare request in Groovy; export the script as groovy.", null, null,
          new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.LOCAL_NODE,
              "entry", "submit", "entry", "Prepare request", properties, List.of("groovy")));
      return new DraftInput(base.flow(), List.of(base.facts().getFirst(), trigger, script),
          base.capabilities(), base.openQuestions(), base.settings());
    };
    try (var ignored = ToolSession.open(chat)) {
      var rejected = mapper.readValue(harness.tools().captureRequirementDraft(candidate.apply("wrong-entry", true)),
          RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_REFERENCE".equals(issue.code())));
      if (restoreStore) {
        var schemas = harness.tools().elementSchemas;
        harness = persistentHarness(blobs, mapper, conversations, chat);
        harness.tools().elementSchemas = schemas;
        harness.store().beginTurn(chat, text);
      }
      var propertyFailure = mapper.readValue(harness.tools().captureRequirementDraft(candidate.apply("entry", true)),
          RequirementCaptureResult.class);
      assertTrue(propertyFailure.issues().stream()
          .anyMatch(issue -> "INVALID_REQUESTED_PROPERTY".equals(issue.code())));
      var repaired = candidate.apply("entry", false);
      var lost = new DraftInput(repaired.flow(), repaired.facts().subList(0, 2),
          repaired.capabilities(), repaired.openQuestions(), repaired.settings());
      assertTrue(RequirementCaptureEditor.validateFactPreservation(
          harness.store().rejectedCaptureFacts(chat), lost, false).stream()
          .anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code()) && "prepare-script".equals(issue.entityId())));
      var script = repaired.facts().getLast();
      var intent = script.elementIntent();
      var changed = new FactInput(script.sourceFactId(), script.interactionIds(), script.kind(), script.polarity(),
          script.text(), null, null, new RequirementElementIntent(intent.elementRef(), intent.elementType(),
              intent.placement(), intent.ownerInteractionId(), intent.successorElementRef(),
              intent.predecessorElementRef(), intent.requestedName(), List.of(), intent.exactValues()));
      assertTrue(RequirementCaptureEditor.validateFactPreservation(harness.store().rejectedCaptureFacts(chat),
          new DraftInput(repaired.flow(), List.of(repaired.facts().getFirst(), repaired.facts().get(1), changed),
              repaired.capabilities(), repaired.openQuestions(), repaired.settings()), false).stream()
          .anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())));
      var accepted = mapper.readValue(harness.tools().captureRequirementDraft(repaired), RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.issues().toString());
      assertEquals(repaired.facts(), harness.store().get(chat).orElseThrow().authoredDraft().facts());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"456", "flow"})
  void namedPropertyCorrectionsKeepTheirCreditsAcrossStoreReconstruction(String propertyKey) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "named-property-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(mapper);
    first.store().beginTurn(chat, text);
    var base = readyHttpDraft();
    java.util.function.Function<String, DraftInput> candidate = key -> {
      var intent = new RequirementElementIntent("entry", "http-trigger",
          RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key, "POST")), List.of());
      var fact = new FactInput("http-config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Accept POST /tasks.", null, null, intent);
      return new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
          base.capabilities(), base.openQuestions(), base.settings());
    };
    try (var ignored = ToolSession.open(chat)) {
      var initial = mapper.readValue(first.tools().captureRequirementDraft(candidate.apply("123")),
          RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, initial.nextAction());
      assertTrue(initial.issues().stream().allMatch(issue -> "INVALID_REQUESTED_PROPERTY".equals(issue.code())));
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.tools().elementSchemas = first.tools().elementSchemas;
      restored.store().beginTurn(chat, "Repair the generated property.");
      assertEquals(NextAction.REPAIR_CAPTURE, mapper.readValue(
          restored.tools().captureRequirementDraft(candidate.apply(propertyKey)), RequirementCaptureResult.class).nextAction());
      var again = persistentHarness(blobs, mapper, conversations, chat);
      again.tools().elementSchemas = first.tools().elementSchemas;
      again.store().beginTurn(chat, "Repair the generated property.");
      var noticed = mapper.readValue(again.tools().captureRequirementDraft(candidate.apply(propertyKey)),
          RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      var stopped = persistentHarness(blobs, mapper, conversations, chat);
      stopped.tools().elementSchemas = first.tools().elementSchemas;
      stopped.store().beginTurn(chat, "Repair the generated property.");
      assertThrows(CaptureNoProgressException.class,
          () -> stopped.tools().captureRequirementDraft(candidate.apply(propertyKey)));
    }
  }

  @Test
  void partialNativeCorrectionKeepsTheRemainingFindingRepairable() throws Exception {
    var h = harness();
    var ready = readyHttpDraft();
    var factA = new FactInput("factA", List.of("missingA"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Keep the original request context.");
    var factB = new FactInput("factB", List.of("missingB"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Prepare the outgoing task.");
    var original = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), factA, factB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var correctedA = new FactInput(factA.sourceFactId(), List.of("entry"), factA.kind(), factA.polarity(), factA.text());
    var partial = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), correctedA, factB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.store().beginTurn(h.conversationId(), "Submit one task. Keep the context and prepare the outgoing task.");
      var initial = h.mapper().readValue(h.tools().captureRequirementDraft(original), RequirementCaptureResult.class);
      assertEquals(List.of("factA", "factB"), initial.issues().stream().map(issue -> issue.entityId()).toList());
      var corrected = h.mapper().readValue(h.tools().captureRequirementDraft(partial), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, corrected.nextAction());
      assertEquals(List.of("factB"), corrected.issues().stream().map(issue -> issue.entityId()).toList());
      var unfinished = h.mapper().readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY),
          RequirementCaptureResult.class);
      assertFalse(unfinished.issues().stream().anyMatch(issue -> "REPEATED_REJECTION".equals(issue.code())));
      assertFalse(unfinished.nextAction() == NextAction.STOP);
      var noticed = h.mapper().readValue(h.tools().captureRequirementDraft(partial), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      assertTrue(noticed.issues().stream().anyMatch(issue -> "factB".equals(issue.entityId())));
      assertThrows(CaptureNoProgressException.class, () -> h.tools().captureRequirementDraft(partial));
    }
  }

  @Test
  void identicalRejectedUpdateLeavesTheProducerWithStoredNativeEvidence() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "no-progress-evidence-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task. Keep its request context.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("context", List.of("missing"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Keep its request context.")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), null);
    try (var ignored = ToolSession.open(chat)) {
      assertTrue(mapper.readValue(h.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      var accepted = h.store().latestRevision(chat).orElseThrow().reference();
      var first = mapper.readValue(h.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, first.nextAction());
      var noticed = mapper.readValue(h.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      var beforeSource = archive.read(chat);
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var credits = new ToolCallFingerprintStore(mapper, artifacts, new CompilationSessions(blobs, mapper, Clock.systemUTC()));
      var beforeCredits = credits.softFingerprints(chat);

      var stopped = assertThrows(CaptureNoProgressException.class, () -> h.tools().updateRequirementDraft(changes));
      var evidence = stopped.evidence();
      assertNotNull(evidence);
      assertEquals(accepted, evidence.acceptedBase());
      assertEquals(h.store().rejectedCaptureRevision(chat).orElseThrow().reference(), evidence.rejectedCandidate());
      assertNotNull(evidence.feedback().evidenceReference());
      assertTrue(artifacts.get(h.store().activeCompilationId(chat), evidence.feedback().evidenceReference()).isPresent());
      assertTrue(evidence.feedback().noProgress());
      assertFalse(evidence.feedback().outerAllowed());
      assertTrue(evidence.feedback().issues().stream().anyMatch(issue -> "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code())
          && "context".equals(issue.entityId())));
      var repeated = artifacts.payload(
          artifacts.get(h.store().activeCompilationId(chat), evidence.rejectedCandidate()).orElseThrow(),
          RequirementDraftStore.RequirementCaptureAttempt.class);
      repeated.issues().forEach(issue -> assertEquals(
          new RequirementCaptureResult.IssueDetails(null, List.of(), List.of()), issue.details()));
      var expectedIssues = repeated.issues().stream().map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
          issue.code(), issue.path(), issue.message(), issue.entityId())).toList();
      assertTrue(expectedIssues.stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())
          && issue.message().endsWith("Submit a different correction.")));
      assertEquals(expectedIssues, evidence.feedback().issues());
      var restoredFeedback = new CaptureAttemptFeedbackStore(credits, artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC()));
      assertEquals(evidence.feedback(), restoredFeedback.lastRequirementRejection(chat).orElseThrow().feedback());
      assertEquals(ready, h.store().get(chat).orElseThrow().authoredDraft());
      assertEquals(accepted, h.store().latestRevision(chat).orElseThrow().reference());
      assertEquals(beforeSource, archive.read(chat));
      assertEquals(beforeCredits, credits.softFingerprints(chat));
      var outcome = org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome.invocationFailure(stopped);
      assertEquals(org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.CONTRACT_FAILURE,
          outcome.outcomeClass());
      assertEquals(evidence, outcome.recoveryCause().captureFailure());
      var strictCause = new ObjectMapper().readValue(
          org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
              .recoveryCauseJson(outcome.recoveryCause()),
          org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.class);
      assertEquals(outcome.recoveryCause(), strictCause);
      assertEquals(expectedIssues, strictCause.captureFailure().feedback().issues());
      assertTrue(outcome.recoveryCause().findings().stream().anyMatch(issue ->
          "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code()) && "context".equals(issue.originRecordId())
              && issue.fieldPointer().equals(CaptureToolOutcomeGateway.logicalRequirementPath(
                  evidence.feedback().issues().getFirst().path()))));
    }
  }

  @Test
  void differentFactReferenceErrorsKeepIndependentCreditsAfterReconstruction() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "fact-reference-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var factA = new FactInput("factA", List.of("missingA"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Keep the request context.");
    var factB = new FactInput("factB", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Prepare the request body.");
    var initial = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), factA, factB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var correctedA = new FactInput("factA", List.of("entry"), factA.kind(), factA.polarity(), factA.text());
    var invalidB = new FactInput("factB", List.of("missingB"), factB.kind(), factB.polarity(), factB.text());
    var second = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), correctedA, invalidB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      var resultA = mapper.readValue(first.tools().captureRequirementDraft(initial), RequirementCaptureResult.class);
      assertEquals(List.of("factA"), resultA.issues().stream().map(issue -> issue.entityId()).toList());
      assertEquals(NextAction.REPAIR_CAPTURE, resultA.nextAction());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var resultB = mapper.readValue(restored.tools().captureRequirementDraft(second), RequirementCaptureResult.class);
      assertEquals(List.of("factB"), resultB.issues().stream()
          .filter(issue -> "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code()))
          .map(issue -> issue.entityId()).toList());
      assertEquals(NextAction.REPAIR_CAPTURE, resultB.nextAction());
      var reordered = new DraftInput(ready.flow(), List.of(invalidB, correctedA, ready.facts().getFirst()),
          ready.capabilities(), ready.openQuestions(), ready.settings());
      assertEquals(NextAction.REPAIR_CAPTURE,
          mapper.readValue(restored.tools().captureRequirementDraft(reordered), RequirementCaptureResult.class).nextAction());
    }
  }

  @Test
  void unchangedCaptureCannotGainCreditByRestartingButANewAuthorInputCan() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "credit-restart-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertEquals(NextAction.REPAIR_CAPTURE,
          mapper.readValue(first.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).nextAction());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      assertTrue(restored.store().lastCaptureRejection(chat).orElseThrow().contains("INVALID_VALUE"));
      var noticed = mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      assertThrows(CaptureNoProgressException.class, () -> restored.tools().captureRequirementDraft(invalid));
      restored.store().recordCaptureRejection(chat, "Repeated invalid sourceFactId.");

      var next = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, next, text);
      restored.store().beginTurn(chat, text);
      assertTrue(restored.store().lastCaptureRejection(chat).isEmpty());
      assertEquals(NextAction.REPAIR_CAPTURE,
          mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).nextAction());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      assertEquals(5, artifacts.history(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void creditWriteFailureRetainsCandidateAndHonorsTheStoredOutcome(boolean commitBeforeFailure) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = org.mockito.Mockito.spy(new InMemoryArtifactBlobStore());
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "credit-write-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(call -> {
      if ("CAPTURE_CREDITS".equals(mapper.readTree((byte[]) call.getArgument(1)).path("kind").asText())
          && failOnce.getAndSet(false)) {
        if (commitBeforeFailure) {
          call.callRealMethod();
        }
        throw new IllegalStateException("Capture credit acknowledgment unavailable");
      }
      return call.callRealMethod();
    }).when(blobs).put(anyString(), any(byte[].class));
    try (var ignored = ToolSession.open(chat)) {
      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
          () -> first.tools().captureRequirementDraft(invalid));
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var candidate = artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
      assertEquals(mapper.valueToTree(invalid), candidate.get("arguments"));
      assertEquals("INVALID_VALUE", candidate.path("issues").get(0).path("code").asText());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      if (commitBeforeFailure) {
        var noticed = mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
        assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
        assertThrows(CaptureNoProgressException.class, () -> restored.tools().captureRequirementDraft(invalid));
      } else {
        var repeated = mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
        assertEquals(NextAction.REPAIR_CAPTURE, repeated.nextAction());
      }
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      assertEquals(1, new ToolCallFingerprintStore(mapper, artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC())).softCreditsUsed(chat));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedCaptureRetainsItsCompleteCandidateFindingsAndMutationScope(boolean verifyScope) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "candidate-restart-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      var rejected = mapper.readValue(first.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      String scope = first.store().captureMutationScope(chat);
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      if (verifyScope) {
        assertEquals(scope, restored.store().captureMutationScope(chat));
        return;
      }
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var saved = artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT);
      assertTrue(saved.isPresent(), "The complete rejected candidate and findings must be retained.");
      var attempt = saved.orElseThrow().payload();
      assertEquals(mapper.valueToTree(invalid), attempt.get("arguments"));
      assertEquals(mapper.valueToTree(rejected.issues()), attempt.get("issues"));
      assertEquals("captureRequirementDraft", attempt.path("toolName").asText());
      assertEquals(source.turnId(), attempt.path("sourceTurnId").asText());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      assertEquals(scope, artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload().path("mutationScope").asText());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedInitializationProtectsFactsAfterStoreReconstruction(boolean clearPriorAttempt) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "rejected-restart-" + UUID.randomUUID();
    String text = "Create an external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text()), visibility), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertFalse(mapper.readValue(first.tools().captureRequirementDraft(invalid),
          RequirementCaptureResult.class).accepted());
      if (clearPriorAttempt) {
        first.store().clearTurnFlags(chat);
        first.store().beginTurn(chat, text);
        assertFalse(mapper.readValue(first.tools().captureRequirementDraft(invalid),
            RequirementCaptureResult.class).accepted());
      }
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var loss = mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class);
      assertFalse(loss.accepted(), loss.toString());
      assertEquals("REPAIR_FACT_LOSS", loss.issues().getFirst().code());
      var corrected = new DraftInput(ready.flow(), List.of(goal, visibility), ready.capabilities(),
          ready.openQuestions(), ready.settings());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(corrected),
          RequirementCaptureResult.class).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedRenamingCannotExpandRepairConstraints(boolean structuralFailure) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "repair-constraints-" + UUID.randomUUID();
    String text = "Submit one task. Keep the external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "Keep the external route with RBAC.");
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text()), visibility), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertFalse(mapper.readValue(h.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).accepted());
      var facts = new ArrayList<>(List.of(structuralFailure ? invalid.facts().getFirst() : goal,
          new FactInput("renamed-visibility", visibility.interactionIds(),
          visibility.kind(), visibility.polarity(), visibility.text())));
      var changed = new DraftInput(ready.flow(), facts, ready.capabilities(), ready.openQuestions(), ready.settings());
      assertFalse(mapper.readValue(h.tools().captureRequirementDraft(changed), RequirementCaptureResult.class).accepted());
      assertEquals(2, h.store().rejectedCaptureFacts(chat).size());
      assertTrue(h.store().rejectedCaptureFacts(chat).contains(visibility));
      assertFalse(h.store().rejectedCaptureFacts(chat).stream()
          .anyMatch(fact -> "renamed-visibility".equals(fact.sourceFactId())),
          "A rejected rewrite must not make an alternative identity mandatory");
      h = persistentHarness(blobs, mapper, conversations, chat);
      h.store().beginTurn(chat, "Repair the generated draft from the saved source.");
      var corrected = new DraftInput(ready.flow(), List.of(goal, visibility), ready.capabilities(),
          ready.openQuestions(), ready.settings());
      var result = mapper.readValue(h.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
    }
  }

  @Test
  void freshRepairCanReadAllValidatedFieldsWithoutAnAcceptedDraft() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "repair-view-" + UUID.randomUUID();
    String text = "Submit one task. Keep the external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "Keep the external route with RBAC.");
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text()), visibility), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertFalse(mapper.readValue(h.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).accepted());
      h = persistentHarness(blobs, mapper, conversations, chat);
      h.store().beginTurn(chat, "Repair the generated draft from the saved source.");
      var expected = mapper.valueToTree(h.store().rejectedCaptureFacts(chat));
      var view = mapper.readTree(h.tools().readRequirementDraft());
      assertTrue(view.path("draft").isNull());
      assertEquals("EMPTY", view.path("readiness").asText());
      assertEquals("REPAIR_CAPTURE", view.path("nextAction").asText());
      assertEquals(expected, view.path("repairConstraints").path("facts"));
      assertEquals("/tasks", view.path("repairConstraints").path("values")
          .path("capabilities").path("entry").path("path").asText());
      var builder = new org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder(
          null, null, h.store(), conversations);
      var renderer = io.quarkus.qute.Engine.builder().addDefaults()
          .addParserHook(new io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory
              .MustacheTemplateVariableStyleParserHook()).build();
      String prompt = renderer.parse(builder.wrap(chat, "Repair the generated draft.", "en")).render();
      assertTrue(prompt.contains("<requirement-repair-constraints>"), prompt);
      assertTrue(prompt.contains(mapper.writeValueAsString(h.store().rejectedCaptureFacts(chat))), prompt);
      assertTrue(h.store().get(chat).isEmpty(), "Reading constraints must not admit a draft");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedCaptureProtectionSurvivesRestartUntilANewAuthorTurn(boolean sourceAcknowledgmentFails) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = org.mockito.Mockito.spy(
        new ConversationArchive(blobs, mapper));
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "accepted-restart-" + UUID.randomUUID();
    String text = "Create an external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    var input = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), visibility),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var removal = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of("visibility"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    try (var ignored = ToolSession.open(chat)) {
      if (sourceAcknowledgmentFails) {
        org.mockito.Mockito.doThrow(new IllegalStateException("source acknowledgment unavailable"))
            .when(archive).consume(anyString(), anyString(), any(), anyString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> first.tools().captureRequirementDraft(input));
        org.mockito.Mockito.doCallRealMethod().when(archive).consume(anyString(), anyString(), any(), anyString());
        var sameProcessLoss = mapper.readValue(first.tools().updateRequirementDraft(removal),
            RequirementCaptureResult.class);
        assertFalse(sameProcessLoss.accepted(), sameProcessLoss.toString());
        assertEquals("REPAIR_FACT_LOSS", sameProcessLoss.issues().getFirst().code());
      } else {
        assertTrue(mapper.readValue(first.tools().captureRequirementDraft(input), RequirementCaptureResult.class).accepted());
      }
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      if (sourceAcknowledgmentFails) {
        var noticed = mapper.readValue(restored.tools().updateRequirementDraft(removal), RequirementCaptureResult.class);
        assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
        var repeated = assertThrows(CaptureNoProgressException.class, () -> restored.tools().updateRequirementDraft(removal));
        assertEquals("REPAIR_FACT_LOSS", repeated.evidence().feedback().issues().getFirst().code());
      } else {
        var loss = mapper.readValue(restored.tools().updateRequirementDraft(removal), RequirementCaptureResult.class);
        assertFalse(loss.accepted(), loss.toString());
        assertEquals("REPAIR_FACT_LOSS", loss.issues().getFirst().code());
      }
      assertEquals(input.facts(), restored.store().get(chat).orElseThrow().authoredDraft().facts());

      var next = archive.beginInput(chat, "Remove the external route requirement.", null);
      archive.resolveInput(chat, next, "Remove the external route requirement.");
      restored.store().beginTurn(chat, "Remove the external route requirement.");
      var authorRemoval = new DraftUpdate(removal.addInteractions(), removal.updateInteractions(),
          removal.removeInteractionIds(), removal.addTransitions(), removal.removeTransitions(), removal.addFacts(),
          removal.updateFacts(), removal.removeFactIds(), removal.setCapabilities(), removal.removeCapabilityInteractionIds(),
          removal.addQuestions(), removal.updateQuestions(), removal.removeQuestionIds(), null,
          List.of(new RequirementCaptureInput.AmendmentInput("/facts/visibility", next.turnId(),
              "Remove the external route requirement.")));
      assertTrue(mapper.readValue(restored.tools().updateRequirementDraft(authorRemoval), RequirementCaptureResult.class).accepted());
    }
  }

  private static Harness persistentHarness(
      InMemoryArtifactBlobStore blobs,
      ObjectMapper mapper,
      ConversationService conversations,
      String chat) {
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var fingerprints = new ToolCallFingerprintStore(mapper, artifacts, sessions);
    var feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
    return new Harness(store, new RequirementCaptureTools(store, adapter, mapper,
        new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null), mapper, chat);
  }

  @Test
  void legacySchemaCannotAuthorizeAnUnsupportedCompilerType() throws Exception {
    assertTrue(new org.qubership.integration.platform.ai.schema.SchemaResourceLoader()
        .existsElementSchema("split-async"));
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    String chat = "compiler-type-" + UUID.randomUUID();
    store.beginTurn(chat, "Create one asynchronous split.");
    try (var ignored = ToolSession.open(chat)) {
      for (String type : List.of("split-async", "if", "else", "async-split-element-2", "catch-2",
          "scs-sender", "sds-trigger", "split-async-2")) {
        var intent = new RequirementElementIntent("split", type, RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, "entry", null, List.of(), List.of());
        var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
            "Caller", "GET /check", null, null, null)), List.of()),
            List.of(new FactInput("split-config", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
                "Create one asynchronous split.", null, null, intent)),
            List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/check",
                null, null, null)), List.of(), new DraftSettings(null, null));
        var result = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
        assertEquals(type.equals("split-async-2"), result.accepted(), result.issues().toString());
        if (!result.accepted()) {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_COMPILER_ELEMENT_TYPE")));
          assertTrue(store.get(chat).isEmpty());
        } else {
          assertEquals(intent, store.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent());
        }
      }
    }
  }

  @Test
  void differentInvalidPropertiesCanBeRepairedInTheSameAuthorTurn() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Accept POST /tasks and submit the task.");
    var base = readyHttpDraft();
    var method = new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpMethodRestrict", "POST");
    var attempts = List.of(
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("method", "POST")),
        List.of(method, new org.qubership.integration.platform.ai.plan.model.PlanProperty("route", "/tasks")),
        List.of(method));
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (int attempt = 0; attempt < attempts.size(); attempt++) {
        var intent = new RequirementElementIntent("entry", "http-trigger",
            RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
            attempts.get(attempt), List.of());
        var fact = new FactInput("http-config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
            "Accept POST /tasks.", null, null, intent);
        var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact,
            nativeAnchorFact("submit", "http-sender")),
            base.capabilities(), base.openQuestions(), base.settings());
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
        assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("REPEATED_REJECTION")),
            result.toString());
        assertEquals(attempt == attempts.size() - 1, result.accepted(), result.toString());
        if (result.accepted()) {
          assertEquals(input, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
          var finished = h.mapper().readValue(
              h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
              RequirementCaptureResult.class);
          assertTrue(finished.accepted(), finished.toString());
        } else {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_REQUESTED_PROPERTY")),
              result.toString());
          assertTrue(h.store().get(h.conversationId()).isEmpty());
        }
      }
    }
  }

  @Test
  void missingNativeCapabilityStopsCaptureBeforeCatalogBindings() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var bindings = mock(RequirementDraftTool.class);
    when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var tools = new RequirementCaptureTools(store, bindings, mapper);
    String chat = UUID.randomUUID().toString();
    store.beginTurn(chat, "Invoke the named chain after the HTTP trigger.");
    var base = readyHttpDraft();
    var intent = new RequirementElementIntent("submit", "chain-call-2", RequirementElementIntent.Placement.ANCHOR,
        "submit", null, null, null, List.of(), List.of());
    var fact = new FactInput("chain-call", List.of("submit"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Invoke the named chain.", null, null, intent);
    var input = new DraftInput(base.flow(), List.of(fact), List.of(base.capabilities().getFirst()), List.of(), base.settings());
    try (var ignored = ToolSession.open(chat)) {
      var rejected = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals("NATIVE_CAPABILITY_REQUIRED")));
      verify(bindings, never()).canonicalBindings(any(), any(), anyString());
      assertTrue(store.get(chat).isEmpty());
      var corrected = new DraftInput(input.flow(), input.facts(), List.of(input.capabilities().getFirst(),
          new CapabilityInput("submit", "chain-call-2", null, null, null, null, null, null)), List.of(), base.settings());
      assertTrue(mapper.readValue(tools.captureRequirementDraft(corrected), RequirementCaptureResult.class).accepted());
      assertEquals(intent, store.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent());
      verify(bindings).canonicalBindings(eq(corrected), isNull(), eq(chat));
    }
  }

  @Test
  void invalidConsumerKeyCanBeRepairedWithoutLosingTheRequestedGroup() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Consume tasks using Kafka consumer group CIP.");
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (String key : List.of("consumerGroupId", "integrationOperationAsyncProperties")) {
        var intent = new RequirementElementIntent("entry", "async-api-trigger",
            RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key,
                key.equals("consumerGroupId") ? "CIP" : "{\"groupId\":\"CIP\"}")), List.of());
        var draft = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
            "Kafka", "onTaskStart", null, null, null)), List.of()),
            List.of(new FactInput("consumer", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
                "Consume tasks using Kafka consumer group CIP.", null, null, intent)),
            List.of(new CapabilityInput("entry", "async-api-trigger", null, null, null,
                null, null, null)), List.of(), new DraftSettings(null, null));
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(draft), RequirementCaptureResult.class);
        if (key.equals("consumerGroupId")) {
          assertFalse(result.accepted());
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_REQUESTED_PROPERTY")
              && issue.message().contains("integrationOperationAsyncProperties")), result.issues().toString());
        } else {
          assertTrue(result.accepted(), result.issues().toString());
          assertEquals(intent, h.store().get(h.conversationId()).orElseThrow()
              .authoredDraft().facts().getFirst().elementIntent());
        }
      }
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"http-trigger,contextPath,/tasks", "kafka-sender-2,connectionSourceType,maas"})
  void additionalSettingsUseANewFactWithoutMutatingTheAcceptedAnchor(String type, String key, String value)
      throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Accept /tasks with RBAC and send to Kafka using MaaS.");
    String owner = type.equals("http-trigger") ? "entry" : "submit";
    var base = readyHttpDraft();
    var caps = List.of(base.capabilities().getFirst(), new CapabilityInput("submit", "kafka-sender-2",
        null, null, null, null, null, null));
    var property = new org.qubership.integration.platform.ai.plan.model.PlanProperty(
        type.equals("http-trigger") ? "accessControlType" : "sslProtocol", type.equals("http-trigger") ? "RBAC" : "TLS");
    var intent = new RequirementElementIntent(owner, type, RequirementElementIntent.Placement.ANCHOR,
        owner, null, null, null, List.of(property), List.of());
    var original = new FactInput("anchor", List.of(owner), FactKind.PARAMETER, Polarity.POSITIVE,
        "Keep the requested endpoint configuration.", null, null, intent);
    var input = new DraftInput(base.flow(), List.of(original), caps, List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      assertTrue(h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class).accepted());
      var augmented = new RequirementElementIntent(owner, type, RequirementElementIntent.Placement.ANCHOR,
          owner, null, null, null, List.of(property,
              new org.qubership.integration.platform.ai.plan.model.PlanProperty(key, value)), List.of());
      var mutated = new FactInput(original.sourceFactId(), original.interactionIds(), original.kind(), original.polarity(),
          original.text(), null, null, augmented);
      var rejected = h.mapper().readValue(h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(mutated), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), null)), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals("REPAIR_FACT_LOSS")
          && issue.message().contains("addFacts")), rejected.toString());
      var extra = new FactInput("additional-setting", List.of(owner), FactKind.PARAMETER, Polarity.POSITIVE,
          "Retain the supplied setting.", null, null,
          new RequirementElementIntent(owner, type, RequirementElementIntent.Placement.ANCHOR, owner, null, null, null,
              List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key, value)), List.of()));
      var repaired = h.mapper().readValue(h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(extra), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), null)), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(List.of(original, extra), repaired.draft().facts());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidExactValuesAreRepairedWithoutRewritingFactsOrDroppingEmptyHeaders(boolean emptyHeader) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Consume topic2 with group CIP and reconnect delay 30000. Add test-keeping with empty value.");
    var base = readyHttpDraft();
    String type = emptyHeader ? "header-modification" : "async-api-trigger";
    var props = emptyHeader
        ? List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("headerModificationToAdd", "{\"test-keeping\":\"\"}"))
        : List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("integrationOperationAsyncProperties", "{\"groupId\":\"CIP\"}"),
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("reconnectDelay", "30000"));
    var caps = emptyHeader ? base.capabilities() : List.of(new CapabilityInput("entry", "async-api-trigger",
        null, null, null, null, null, null), base.capabilities().getLast());
    String text = emptyHeader ? "Add test-keeping with empty value." : "Consume topic2.";
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (boolean corrected : List.of(false, true)) {
        var intent = new RequirementElementIntent(emptyHeader ? "headers" : "entry", type,
            emptyHeader ? RequirementElementIntent.Placement.LOCAL_NODE : RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, props,
            corrected ? List.of(emptyHeader ? "test-keeping" : "topic2")
                : List.of(emptyHeader ? "test-keeping" : "topic2", emptyHeader ? "" : "CIP"));
        var fact = new FactInput("config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
            text, null, null, intent);
        var input = new DraftInput(base.flow(), List.of(fact), caps, List.of(), base.settings());
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
        assertEquals(corrected, result.accepted(), result.toString());
        if (!corrected) {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals(emptyHeader
              ? "ELEMENT_LITERAL_SOURCE_MISMATCH" : "ELEMENT_LITERAL_FACT_MISMATCH")
              && issue.message().contains(emptyHeader ? "requestedProperties" : "Do not rewrite")), result.toString());
        } else {
          assertEquals(text, result.draft().facts().getFirst().text());
          assertEquals(props, result.draft().facts().getFirst().elementIntent().requestedProperties());
        }
      }
    }
  }

  @Test
  void alteredLiteralCannotBeCapturedAsAnExactAuthorValue() throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    String chat = "literal-source-" + UUID.randomUUID();
    store.beginTurn(chat, "Respond with {\"error\":\"test-header incorrect value\"}.");
    var wrong = new RequirementElementIntent("set-error", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, null, null, List.of(), List.of("{\"error\":\"test-header incorrect values\"}"));
    var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "GET /check", null, null, null)), List.of()), List.of(new FactInput("error-body", List.of("entry"),
            FactKind.BEHAVIOR, Polarity.POSITIVE, "Respond with {\"error\":\"test-header incorrect values\"}", null, null, wrong)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/check",
            null, null, null)), List.of(), new DraftSettings(null, null));
    try (var ignored = ToolSession.open(chat)) {
      var result = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(result.accepted());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ELEMENT_LITERAL_SOURCE_MISMATCH")));
      assertTrue(store.get(chat).isEmpty());
      String literal = "{\"error\":\"test-header incorrect value\"}";
      var correct = new RequirementElementIntent(wrong.elementRef(), wrong.elementType(), wrong.placement(),
          wrong.ownerInteractionId(), null, null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "return '" + literal + "'")),
          List.of(literal), new RequirementBodyBinding(RequirementBodyBinding.Authority.GENERATED_CANDIDATE,
              wrong.elementRef(), "script", RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
              null, null, null, null, null));
      var accepted = mapper.readValue(tools.captureRequirementDraft(new DraftInput(input.flow(),
          List.of(new FactInput("error-body", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
              "Respond with " + literal, null, null, correct)), input.capabilities(), input.openQuestions(),
          input.settings())), RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.issues().toString());
      assertEquals(correct, store.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent());
    }
  }

  @Test
  void asksWhichFolderWhenTheNameMatchesSeveral() throws Exception {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    CatalogFolders folders = mock(CatalogFolders.class);
    when(folders.tree())
        .thenReturn(List.of(
            new CatalogFolders.Folder("f1", "A", null, "A"),
            new CatalogFolders.Folder("f2", "Salesforce", "f1", "A/Salesforce"),
            new CatalogFolders.Folder("f3", "Salesforce", null, "Salesforce")));
    CaptureAttemptFeedbackStore feedback =
        new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindingAdapter, mapper,
        new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback), feedback, null,
        folders);
    List<FactInput> facts = List.of(new FactInput("goal", List.of(), FactKind.GOAL,
        Polarity.POSITIVE, "Create a task."));

    try (ToolSession.Handle ignored = ToolSession.open("capture-folder-" + UUID.randomUUID())) {
      RequirementCaptureResult ambiguous = mapper.readValue(
          tools.captureRequirementDraft(new DraftInput(new FlowInput(List.of(), List.of()), facts,
              List.of(), List.of(), new DraftSettings(null, null, "salesforce"))),
          RequirementCaptureResult.class);
      assertFalse(ambiguous.accepted());
      assertEquals("AMBIGUOUS_TARGET_FOLDER", ambiguous.issues().getFirst().code());
      assertTrue(ambiguous.issues().getFirst().message().contains("A/Salesforce, Salesforce"));

      RequirementCaptureResult exact = mapper.readValue(
          tools.captureRequirementDraft(new DraftInput(new FlowInput(List.of(), List.of()), facts,
              List.of(), List.of(), new DraftSettings(null, null, "A/Salesforce"))),
          RequirementCaptureResult.class);
      assertTrue(exact.accepted());
    }
  }

  @Test
  void savesPartialDraftAndAppliesOneFocusedUpdate() throws Exception {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindingAdapter, mapper);
    String conversationId = "capture-test-" + UUID.randomUUID();
    DraftInput first = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
            "Create a task.")), List.of(), List.of(), new DraftSettings(false, null));

    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      RequirementCaptureResult captured = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(captured.accepted());
      assertTrue(captured.changed());
      assertEquals(RequirementCaptureResult.Readiness.PARTIAL, captured.readiness());
      assertNotNull(captured.revision());
      assertEquals(first, store.get(conversationId).orElseThrow().authoredDraft());

      RequirementCaptureResult duplicate = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(duplicate.accepted());
      assertFalse(duplicate.changed());
      assertEquals(captured.revision(), duplicate.revision());

      store.beginTurn(conversationId, "Create one task for each input.");
      FactInput changedFact = new FactInput("goal", List.of(), FactKind.GOAL,
          Polarity.POSITIVE, "Create one task for each input.");
      DraftUpdate change = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(changedFact), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null,
          List.of(new RequirementCaptureInput.AmendmentInput("/facts/goal/text", "current",
              "Create one task for each input.")));
      RequirementCaptureResult updated = mapper.readValue(
          tools.updateRequirementDraft(change), RequirementCaptureResult.class);
      assertTrue(updated.accepted());
      assertTrue(updated.changed());
      assertEquals(changedFact, updated.draft().facts().getFirst());
      assertEquals(changedFact, store.get(conversationId).orElseThrow().authoredDraft()
          .facts().getFirst());
      assertEquals(updated.revision(), mapper.readValue(
          tools.readRequirementDraft(), RequirementCaptureResult.class).revision());
    }
  }

  @Test
  void continueIsRefusedWhileInteractionFieldsAreBlank() throws Exception {
    Harness harness = harness();
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      harness.tools().captureRequirementDraft(partialKafkaSenderDraft());

      RequirementCaptureResult finished = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);

      assertFalse(finished.accepted());
      assertEquals(NextAction.COMPLETE_DRAFT, finished.nextAction());
      assertEquals(
          RequirementDiscoveryDirective.STAY, harness.store().turnDirective(harness.conversationId()));
      String message = finished.issues().getFirst().message();
      assertTrue(message.contains("http-trigger is missing operation"), message);
      assertTrue(message.contains("script-set-body is missing direction and participant"), message);
      assertTrue(message.contains("kafka-sender is missing operation"), message);
      assertTrue(message.contains("Use updateRequirementDraft"), message);
      assertTrue(message.contains("Do not tell the user the draft is ready"), message);
      assertNotNull(harness.store().get(harness.conversationId()).orElseThrow().authoredDraft());

      RequirementCaptureResult repeated = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      String repeatedMessage = repeated.issues().getFirst().message();
      assertFalse(repeated.accepted());
      assertEquals(NextAction.COMPLETE_DRAFT, repeated.nextAction());
      assertEquals(1, repeated.issues().size(), repeated.issues().toString());
      assertTrue(repeatedMessage.contains("http-trigger is missing operation"), repeatedMessage);
      assertFalse(repeatedMessage.toLowerCase().contains("repeated rejection"), repeatedMessage);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class,
      names = {"STAY", "CONTINUE"})
  void finishRefreshesTheCatalogBindingResolvedAfterCapture(RequirementDiscoveryDirective directive) throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    var base = readyHttpDraft();
    var input = withNativeAnchors(new DraftInput(base.flow(), base.facts(), List.of(base.capabilities().getFirst()),
        base.openQuestions(), base.settings()));
    var binding = new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint(
        "3", "submit", "submit", "Task API submit", "sys", "group", "spec", "operation",
        "http", "POST", "/tasks", "2026.1",
        java.time.Instant.parse("2026-10-16T04:37:48.187484941Z"), "catalog-read:operation");
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of(), List.of(binding));
    String chat = "finish-resolved-" + UUID.randomUUID();
    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertEquals(RequirementCaptureResult.Readiness.PARTIAL, captured.readiness());
      assertEquals(NextAction.LOOKUP, captured.nextAction());

      var finished = mapper.readValue(tools.finishRequirementDiscoveryTurn(directive), RequirementCaptureResult.class);

      assertTrue(finished.accepted(), finished.toString());
      assertEquals(RequirementCaptureResult.Readiness.READY, finished.readiness());
      assertTrue(finished.pendingWork().isEmpty());
      assertEquals(List.of(binding), store.get(chat).orElseThrow().catalogBindings());
      assertEquals(input, store.get(chat).orElseThrow().authoredDraft());
      assertEquals(directive, store.turnDirective(chat));
    }
  }

  @Test
  void continueIsAcceptedWhenTheSavedDraftIsReady() throws Exception {
    Harness harness = harness();
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      RequirementCaptureResult captured = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(withNativeAnchors(readyHttpDraft())), RequirementCaptureResult.class);
      assertEquals(RequirementCaptureResult.Readiness.READY, captured.readiness(), captured.toString());

      RequirementCaptureResult finished = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);

      assertTrue(finished.accepted(), finished.toString());
      assertTrue(finished.issues().isEmpty(), finished.issues().toString());
      assertEquals(
          RequirementDiscoveryDirective.CONTINUE,
          harness.store().turnDirective(harness.conversationId()));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void uploadedSpecificationsAdvanceUnboundCallsToImport(int count) throws Exception {
    Harness h = uploadedHarness();
    h.tools().conversations.registerAllowedAttachmentKeys(h.conversationId(),
        count == 1 ? List.of("sessions/test/tasks.json")
            : List.of("sessions/test/tasks.json", "sessions/test/audit.yaml"));
    var base = readyHttpDraft();
    var interactions = new java.util.ArrayList<>(base.flow().interactions());
    var transitions = new java.util.ArrayList<>(base.flow().transitions());
    if (count == 2) {
      interactions.add(new InteractionInput("audit", Direction.OUTBOUND, "Audit API", "readAudit", null, null, null));
      transitions.add(new TransitionInput("submit", "audit"));
    }
    var input = new DraftInput(new FlowInput(interactions, transitions), base.facts(),
        List.of(base.capabilities().getFirst()), List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var captured = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      assertEquals(NextAction.RUN_IMPORT, captured.nextAction());
      assertEquals(count, captured.pendingWork().size());
      assertTrue(captured.pendingWork().stream().allMatch(work -> "UPLOADED_SPEC_IMPORT_REQUIRED".equals(work.code())));
      var finished = h.mapper().readValue(
          h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE), RequirementCaptureResult.class);
      assertTrue(finished.accepted(), finished.toString());
      assertEquals(NextAction.RUN_IMPORT, finished.nextAction());
      assertEquals(RequirementDiscoveryDirective.CONTINUE, h.store().turnDirective(h.conversationId()));
      assertEquals(input, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
      assertTrue(h.store().get(h.conversationId()).orElseThrow().catalogBindings().isEmpty());
    }
  }

  @Test
  void uploadedSpecificationDoesNotBypassAnUnansweredBusinessQuestion() throws Exception {
    Harness h = uploadedHarness();
    h.tools().conversations.registerAllowedAttachmentKeys(h.conversationId(), List.of("sessions/test/tasks.json"));
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), base.facts(), List.of(base.capabilities().getFirst()),
        List.of(new RequirementCaptureInput.QuestionInput("choice", List.of("submit"), "Which task type should be used?")), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.tools().captureRequirementDraft(input);
      var finished = h.mapper().readValue(
          h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE), RequirementCaptureResult.class);
      assertFalse(finished.accepted(), finished.toString());
      assertEquals(NextAction.ASK_USER, finished.nextAction());
      assertEquals(RequirementDiscoveryDirective.STAY, h.store().turnDirective(h.conversationId()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"requirements.md", "requirements.txt", "reference.pdf"})
  void nonSpecificationAttachmentsDoNotAuthorizeImport(String filename) throws Exception {
    Harness h = harness();
    h.tools().conversations = new ConversationService();
    h.tools().conversations.registerAllowedAttachmentKeys(h.conversationId(), List.of("sessions/test/" + filename));
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), base.facts(), List.of(base.capabilities().getFirst()), List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      var captured = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
      assertEquals(NextAction.LOOKUP, captured.nextAction());
      var finished = h.mapper().readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertFalse(finished.accepted());
      assertEquals(RequirementDiscoveryDirective.STAY, h.store().turnDirective(h.conversationId()));
    }
  }

  @Test
  void uploadedSpecificationDoesNotBypassAnIncompleteNativeEndpoint() throws Exception {
    Harness h = uploadedHarness();
    h.tools().conversations.registerAllowedAttachmentKeys(h.conversationId(), List.of("sessions/test/tasks.json"));
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), base.facts(), List.of(new CapabilityInput(
        "entry", "http-trigger", HttpMode.CUSTOM, null, "/tasks", null, null, null)), List.of(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.tools().captureRequirementDraft(input);
      var finished = h.mapper().readValue(h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertFalse(finished.accepted());
      assertEquals(NextAction.COMPLETE_DRAFT, finished.nextAction());
      assertTrue(finished.pendingWork().stream().anyMatch(work -> "CAPABILITY_FIELD_REQUIRED".equals(work.code())));
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"property,false", "property,true", "owner,false", "owner,true"})
  void rejectedFocusedUpdateProtectsNewRoleDuringPropertyRepair(String defect, boolean restoreStore) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "focused-role-" + UUID.randomUUID();
    var initialSource = archive.beginInput(chat, "Create the endpoint without IDS.", null);
    archive.resolveInput(chat, initialSource, "Create the endpoint without IDS.");
    var h = persistentHarness(blobs, mapper, conversations, chat);
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    try (var ignored = ToolSession.open(h.conversationId())) {
      h.tools().captureRequirementDraft(readyHttpDraft());
      String text = "Use RBAC with qip-viewer and override-request-id.";
      var source = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, source, text);
      h.store().beginTurn(chat, text);
      var role = new FactInput("role", List.of("entry"), FactKind.VISIBILITY, Polarity.POSITIVE,
          "Use RBAC with roles [\"qip-viewer\"].");
      java.util.function.Function<Boolean, FactInput> context = repaired -> new FactInput("context",
          List.of("submit"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Override X-Request-Id with override-request-id.", null, null,
          new RequirementElementIntent("submit", "http-sender", RequirementElementIntent.Placement.ANCHOR,
              !repaired && defect.equals("owner") ? "wrong-submit" : "submit", null, null, null,
              List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                  "overrideContextParams", !repaired && defect.equals("property")
                      ? "X-Request-Id=override-request-id" : "{\"X-Request-Id\":\"override-request-id\"}")), List.of()));
      java.util.function.Function<List<FactInput>, DraftUpdate> add = facts -> new DraftUpdate(
          List.of(), List.of(), List.of(), List.of(), List.of(), facts, List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var rejected = h.mapper().readValue(h.tools().updateRequirementDraft(
          add.apply(List.of(role, context.apply(false)))),
          RequirementCaptureResult.class);
      assertFalse(rejected.accepted(), rejected.toString());
      assertTrue(rejected.issues().stream().anyMatch(issue -> (defect.equals("property")
          ? "INVALID_REQUESTED_PROPERTY" : "INVALID_ELEMENT_REFERENCE").equals(issue.code())));
      assertEquals(readyHttpDraft(), h.store().get(h.conversationId()).orElseThrow().authoredDraft());
      if (restoreStore) {
        var schemas = h.tools().elementSchemas;
        h = persistentHarness(blobs, mapper, conversations, chat);
        h.tools().elementSchemas = schemas;
        h.store().beginTurn(chat, text);
      }
      var repairedContext = context.apply(true);
      var lost = h.mapper().readValue(h.tools().updateRequirementDraft(add.apply(List.of(repairedContext))),
          RequirementCaptureResult.class);
      assertFalse(lost.accepted(), "Repair must not silently lose the newly supplied role");
      assertTrue(lost.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
          && "role".equals(issue.entityId())));
      var repaired = h.mapper().readValue(h.tools().updateRequirementDraft(
          add.apply(List.of(role, repairedContext))), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertTrue(repaired.draft().facts().contains(role));
    }
  }

  @Test
  void completionRejectsFactLossWithoutChangingTheRevision() throws Exception {
    for (String loss : List.of("delete", "text", "scope")) {
      boolean delete = loss.equals("delete");
      Harness harness = harness();
      harness.store().beginTurn(harness.conversationId(), "Create an external route with RBAC.");
      DraftInput ready = readyHttpDraft();
      FactInput visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
          Polarity.POSITIVE, "External HTTP route with RBAC.");
      DraftInput partial = new DraftInput(
          new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Client",
              null, null, null, null), ready.flow().interactions().getLast()),
              ready.flow().transitions()),
          List.of(ready.facts().getFirst(), visibility), ready.capabilities(), List.of(), ready.settings());
      try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
        RequirementCaptureResult captured = harness.mapper().readValue(
            harness.tools().captureRequirementDraft(partial), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
        FactInput shortened = new FactInput("visibility",
            loss.equals("scope") ? List.of("submit") : List.of("entry"), FactKind.VISIBILITY,
            Polarity.POSITIVE,
            loss.equals("text") ? "HTTP route with RBAC." : "External HTTP route with RBAC.");
        DraftUpdate change = new DraftUpdate(List.of(), List.of(ready.flow().interactions().getFirst()),
            List.of(), List.of(), List.of(), List.of(), delete ? List.of() : List.of(shortened),
            delete ? List.of("visibility") : List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), null);
        RequirementCaptureResult rejected = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(change), RequirementCaptureResult.class);
        assertFalse(rejected.accepted(), rejected.toString());
        assertEquals("REPAIR_FACT_LOSS", rejected.issues().getFirst().code());
        assertEquals(captured.revision(), rejected.revision());
        assertEquals(partial, harness.store().get(harness.conversationId()).orElseThrow().authoredDraft());

        DraftUpdate correction = new DraftUpdate(List.of(),
            List.of(ready.flow().interactions().getFirst()), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        RequirementCaptureResult repaired = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(correction), RequirementCaptureResult.class);
        assertTrue(repaired.accepted(), repaired.toString());
        assertEquals(RequirementCaptureResult.Readiness.READY, repaired.readiness());
        assertEquals(partial.facts(), repaired.draft().facts());

        harness.store().beginTurn(harness.conversationId(), "Remove the external route requirement.");
        DraftUpdate authorRemoval = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of("visibility"), List.of(), List.of(), List.of(),
            List.of(), List.of(), null,
            List.of(new RequirementCaptureInput.AmendmentInput("/facts/visibility", "current",
                "Remove the external route requirement.")));
        RequirementCaptureResult removed = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(authorRemoval), RequirementCaptureResult.class);
        assertTrue(removed.accepted(), removed.toString());
        assertEquals(List.of(ready.facts().getFirst()), removed.draft().facts());
      }
    }
  }

  @Test
  void completionMovesLocalBehaviorWhenRemovingAnIncorrectInteraction() throws Exception {
    Harness harness = harness();
    harness.store().beginTurn(harness.conversationId(), "Set body, then send the message to Kafka.");
    DraftInput initial = partialKafkaSenderDraft();
    FactInput behavior = new FactInput("set-body", List.of("script-set-body"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Set body script sets body to {\"message\":\"test\"} before sending to Kafka.");
    DraftInput partial = new DraftInput(initial.flow(), List.of(initial.facts().getFirst(), behavior),
        initial.capabilities(), List.of(), initial.settings());
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      harness.tools().captureRequirementDraft(partial);
      FactInput moved = new FactInput(behavior.sourceFactId(), List.of("kafka-sender"), behavior.kind(),
          behavior.polarity(), behavior.text());
      DraftUpdate changes = new DraftUpdate(List.of(), List.of(
          new InteractionInput("http-trigger", Direction.INBOUND, "Caller",
              "POST /auto-tests/kafka-sender", null, null, null),
          new InteractionInput("kafka-sender", Direction.OUTBOUND, "Kafka", "Send message",
              null, null, null)), List.of("script-set-body"),
          List.of(new TransitionInput("http-trigger", "kafka-sender")),
          initial.flow().transitions(), List.of(), List.of(moved), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      RequirementCaptureResult repaired = harness.mapper().readValue(
          harness.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(RequirementCaptureResult.Readiness.READY, repaired.readiness());
      assertEquals(List.of(initial.facts().getFirst(), moved), repaired.draft().facts());
    }
  }

  @Test
  void rejectedInitializationPreservesKnownFactsWhileAllowingAnInvalidIdToChange() throws Exception {
    Harness harness = harness();
    harness.store().beginTurn(harness.conversationId(), "Create an external route with RBAC.");
    DraftInput ready = readyHttpDraft();
    FactInput goal = ready.facts().getFirst();
    FactInput invalidId = new FactInput("1", goal.interactionIds(), goal.kind(), goal.polarity(), goal.text());
    FactInput visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    DraftInput invalid = new DraftInput(ready.flow(), List.of(invalidId, visibility),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      RequirementCaptureResult first = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertFalse(first.accepted());
      assertTrue(harness.store().get(harness.conversationId()).isEmpty());

      RequirementCaptureResult lost = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(ready), RequirementCaptureResult.class);
      assertFalse(lost.accepted(), lost.toString());
      assertEquals("REPAIR_FACT_LOSS", lost.issues().getFirst().code());
      assertTrue(harness.store().get(harness.conversationId()).isEmpty());

      DraftInput corrected = new DraftInput(ready.flow(), List.of(goal, visibility),
          ready.capabilities(), ready.openQuestions(), ready.settings());
      RequirementCaptureResult repaired = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(corrected.facts(), repaired.draft().facts());
    }
  }

  @Test
  void authorLanguageDoesNotChangeCaptureValidation() throws Exception {
    DraftInput ready = readyHttpDraft();
    FactInput behavior = new FactInput("forward-body", List.of("submit"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Forward the request body unchanged.");
    DraftInput draft = new DraftInput(ready.flow(), List.of(behavior), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    for (String authorText : List.of(
        "Purpose: Forward the request body unchanged.",
        "Objectif: Transmettre le corps de la requete sans modification.",
        "Objetivo: Reenviar el cuerpo de la solicitud sin cambios.",
        "Forward the request body unchanged.")) {
      Harness harness = harness();
      harness.store().beginTurn(harness.conversationId(), authorText);
      try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
        RequirementCaptureResult captured = harness.mapper().readValue(
            harness.tools().captureRequirementDraft(draft), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
        assertEquals(RequirementCaptureResult.Readiness.READY, captured.readiness());
        assertEquals(draft, captured.draft());
      }
    }
  }

  @Test
  void roleExamplesCannotReplaceTheAuthorRoleChoice() throws Exception {
    for (String role : List.of("qip-viewer", "ROLE_ND_APPLICATION_SUPPORT")) {
      var h = harness();
      h.store().beginTurn(h.conversationId(), "Use RBAC with the explicit role qip-viewer for the HTTP trigger.");
      var base = readyHttpDraft();
      var intent = new RequirementElementIntent("entry", "http-trigger",
          RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("accessControlType", "RBAC"),
              new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", "[\"" + role + "\"]")),
          List.of());
      var fact = new FactInput("security", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Use RBAC with role " + role + ".", null, null, intent);
      var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
          base.capabilities(), base.openQuestions(), base.settings());
      try (var ignored = ToolSession.open(h.conversationId())) {
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
        assertEquals(role.equals("qip-viewer"), result.accepted(), result.toString());
        if (!result.accepted()) {
          assertTrue(result.issues().stream().anyMatch(issue -> "ELEMENT_LITERAL_SOURCE_MISMATCH".equals(issue.code())
              && issue.path().endsWith("/requestedProperties/roles")), result.toString());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"http-trigger", "http-sender"})
  void escapedQuotesMatchTheSameAuthorLiteral(String elementType) throws Exception {
    String author = "Configure " + elementType + " with value=\"[\\\"qip-viewer\\\"]\".";
    String supplied = "[\"qip-viewer\"]";
    String absent = "[\"qip-admin\"]";
    for (String literal : List.of(supplied, absent)) {
      var result = captureLiteral(elementType, author, literal);
      assertEquals(literal.equals(supplied), result.accepted(), result.toString());
      if (!result.accepted()) {
        assertTrue(result.issues().stream().anyMatch(issue -> "ELEMENT_LITERAL_SOURCE_MISMATCH".equals(issue.code())
            && issue.path().contains("/exactValues/")), result.toString());
      }
    }
  }

  @Test
  void roleWordAloneDoesNotSupplyAJsonArrayLiteral() throws Exception {
    var result = captureLiteral("http-trigger", "Use RBAC with the explicit role qip-viewer.",
        "[\"qip-viewer\"]");
    assertFalse(result.accepted(), result.toString());
    assertTrue(result.issues().stream().anyMatch(issue -> "ELEMENT_LITERAL_SOURCE_MISMATCH".equals(issue.code())
        && issue.path().contains("/exactValues/")), result.toString());
  }

  private RequirementCaptureResult captureLiteral(String elementType, String author, String literal) throws Exception {
    var h = harness();
    h.store().beginTurn(h.conversationId(), author);
    String owner = "http-sender".equals(elementType) ? "submit" : "entry";
    var intent = new RequirementElementIntent(owner, elementType,
        RequirementElementIntent.Placement.ANCHOR, owner, null, null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("roles", literal)),
        List.of(literal));
    var fact = new FactInput("security", List.of(owner), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Use roles " + literal + ".", null, null, intent);
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
        base.capabilities(), base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      return h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
    }
  }

  private static Harness uploadedHarness() {
    var conversations = new ConversationService();
    var s3 = mock(org.qubership.integration.platform.ai.storage.S3Service.class);
    when(s3.readObjectBytes(anyString())).thenAnswer(call -> {
      boolean audit = call.<String>getArgument(0).contains("audit");
      return ("{\"info\":{\"title\":\"" + (audit ? "Audit API" : "Task API")
          + "\"},\"paths\":{\"/tasks\":{\"post\":{\"operationId\":\""
          + (audit ? "readAudit" : "submit") + "\"}}}}")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    });
    return harness(conversations, s3);
  }

  private static Harness harness() {
    return harness(null, null);
  }

  private static Harness harness(ConversationService conversations,
      org.qubership.integration.platform.ai.storage.S3Service s3) {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(
        new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore());
    var tools = conversations == null ? new RequirementCaptureTools(store, bindingAdapter, mapper)
        : new RequirementCaptureTools(store, bindingAdapter, mapper,
            new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
                feedback.fingerprintStore(), feedback), feedback, null, null,
            new UploadedSpecOperations(conversations, s3), conversations);
    return new Harness(store, tools, mapper, "capture-test-" + UUID.randomUUID());
  }

  private record Harness(
      RequirementDraftStore store,
      RequirementCaptureTools tools,
      ObjectMapper mapper,
      String conversationId) {}

  private static DraftInput partialKafkaSenderDraft() {
    return new DraftInput(
        new FlowInput(
            List.of(
                new InteractionInput(
                    "http-trigger", Direction.INBOUND, "Caller", null, null, null, null),
                new InteractionInput(
                    "script-set-body", null, null, "Set body", null, null, null),
                new InteractionInput(
                    "kafka-sender", Direction.OUTBOUND, "Kafka", null, null, null, null)),
            List.of(
                new TransitionInput("http-trigger", "script-set-body"),
                new TransitionInput("script-set-body", "kafka-sender"))),
        List.of(new FactInput(
            "goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Send a Kafka message.")),
        List.of(
            new CapabilityInput(
                "http-trigger", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
                "/auto-tests/kafka-sender", null, null, null),
            new CapabilityInput(
                "kafka-sender", "kafka-sender-2", null, null, null,
                "cip-auto-tests-topic1", null, null)),
        List.of(),
        new DraftSettings(null, null));
  }

  @Test
  void catalogParticipantAndOperationNeedNoRuntimeProperty() throws Exception {
    var base = readyHttpDraft();
    var interactions = new ArrayList<>(base.flow().interactions());
    interactions.add(new InteractionInput("billing", Direction.OUTBOUND, "billing-system", "list-accounts",
        null, null, null));
    var flow = new FlowInput(interactions, base.flow().transitions());
    assertFalse(unbound(literalDraft(flow, base, "billing", "billing-system")));
    assertFalse(unbound(literalDraft(flow, base, "billing", "list-accounts")));
    assertFalse(unbound(literalDraft(flow, base, "submit", "Task API")));
    assertTrue(unbound(literalDraft(flow, base, "billing", "Client")));
    var longerName = literalDraft(flow, base, "billing", "billing-system-west");
    assertTrue(unbound(longerName), longerName.toString());
    assertTrue(longerName.issues().stream().anyMatch(issue -> issue.message().contains("interaction participant")
        && !issue.message().contains("integrationSystemId")), longerName.toString());
  }

  private static RequirementCaptureResult literalDraft(FlowInput flow, DraftInput base,
      String interactionId, String literal) throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(),
        "Call billing-system operation list-accounts. Log Task API, Client, and billing-system-west.");
    var intent = new RequirementElementIntent("audit", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
        interactionId, null, null, null,
        List.of(new PlanProperty("logLevel", "Info")), List.of(literal));
    var fact = new FactInput("audit", List.of(interactionId), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Log exactly " + literal + ".", null, null, intent);
    var input = new DraftInput(flow, List.of(base.facts().getFirst(), fact),
        base.capabilities(), base.openQuestions(), base.settings());
    try (var ignored = ToolSession.open(h.conversationId())) {
      return h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
    }
  }

  private static boolean unbound(RequirementCaptureResult result) {
    return result.issues().stream().anyMatch(issue -> "UNBOUND_REQUIRED_LITERAL".equals(issue.code()));
  }

  private static DraftInput readyHttpDraft() {
    return new DraftInput(
        new FlowInput(
            List.of(
                new InteractionInput(
                    "entry", Direction.INBOUND, "Client", "POST /tasks", null, null, null),
                new InteractionInput(
                    "submit", Direction.OUTBOUND, "Task API", "submit", null, null, null)),
            List.of(new TransitionInput("entry", "submit"))),
        List.of(new FactInput(
            "goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit one task.")),
        List.of(
            new CapabilityInput(
                "entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput(
                "submit", "http-sender", null, HttpMethod.POST, "https://example.com/tasks",
                null, null, null)),
        List.of(),
        new DraftSettings(null, null));
  }
}
