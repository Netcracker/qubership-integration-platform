package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementSelectedPropertyCoverageTest {
  @Test
  void aSelectedRoutePropertyCannotProveItsUnchangedCapabilityAndBriefPath() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    var conversations = new ConversationService();
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var schemas = DeterministicElementSchemaService.createForUnitTests(json);
    var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json);
    tools.elementSchemas = schemas;
    tools.conversations = conversations;
    String chat = "selected-route-" + UUID.randomUUID();
    begin(conversations, store, chat, "Expose POST /old. The alternative route is /new. Proceed without IDS.");
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readValue(tools.captureRequirementDraft(draft(null, "/old")), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      var choices = new PolicyChoiceSelection(artifacts, sessions, conversations);
      var offered = choices.pending(chat, 7).orElseThrow();
      var command = new ChatDecisionCommand();
      command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
      command.setArtifactHash(offered.hash());
      command.setRevision(7);
      command.setAction(ChatEvent.USE_RECOMMENDED_ACTION);
      String marker = ChatDecisionService.transcriptMarker(command);
      var decisionInput = conversations.archive().beginInput(chat, marker, json.valueToTree(command));
      conversations.archive().resolveInput(chat, decisionInput, marker, json.valueToTree(command));
      store.beginTurn(chat, marker);
      assertTrue(choices.confirm(chat, command, 7).isPresent());
      assertEquals("/new", store.trustedPolicyChoices(chat).getFirst().selection().selectedValue());
      var accepted = store.latestRevision(chat).orElseThrow().reference();

      var candidate = draft(Decision.USE_RECOMMENDED, "/new");
      var brief = RequirementBriefProjector.project(new RequirementBrief("Expose an endpoint", List.of(), List.of(),
          List.of(), List.of(), "", null, "", RequirementCaptureProjection.facts(candidate))
          .withFlow(RequirementCaptureProjection.flow(candidate)));
      var graph = new ChainPlanGraph("1.0", new ChainSection("Route.Check", ""),
          List.of(new ChainPlanNode("entry", "http-trigger", "Entry", null, null, properties("/new"))), List.of());
      assertEquals("/old", candidate.capabilities().getFirst().path());
      assertEquals("/old", brief.entryPoints().getFirst().path());
      assertEquals("/new", graph.nodes().getFirst().properties().stream().filter(property -> property.key().equals("contextPath"))
          .findFirst().orElseThrow().value());
      var projected = RequirementCaptureProjection.toDraft(candidate, store.get(chat).orElseThrow(), List.of(), "diagnostic", "diagnostic");
      assertTrue(new RequirementBriefCoverageValidator().validate(projected, brief).isEmpty());
      assertTrue(CompilerQualityValidator.validateApprovedValues(graph, brief).isEmpty());
      assertTrue(new CompilerSecurityValidator().validate(graph, brief).valid());

      var refused = json.readValue(tools.updateRequirementDraft(update(
          List.of(policy(Decision.USE_RECOMMENDED), route("/new")))), RequirementCaptureResult.class);
      assertFalse(refused.accepted());
      assertEquals(accepted, store.latestRevision(chat).orElseThrow().reference());
      assertEquals(decisionInput.turnId(), conversations.archive().pendingInputs(chat).getFirst().id());
      var attempt = artifacts.latest(store.activeCompilationId(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      var report = RequirementRevisionAdmission.evaluate(chat, attempt.reference(), store, artifacts, conversations, schemas,
          new RequirementRevisionAdmission.NativeInputs(brief, graph));
      assertEquals(RequirementRevisionAdmission.PreservationOutcome.UNPROVEN, report.outcome(), report.toString());
      assertTrue(report.changedPaths().contains("/facts/route/elementIntent/requestedProperties/1/value"));
      assertTrue(report.findings().stream().anyMatch(finding -> finding.checker().equals("consumer-coverage")
          && finding.outcome() == RequirementRevisionAdmission.PreservationOutcome.UNPROVEN));
      assertEquals("/old", store.get(chat).orElseThrow().authoredDraft().facts().stream()
          .filter(fact -> fact.sourceFactId().equals("route")).findFirst().orElseThrow().elementIntent().requestedProperties().get(1).value());
    }
  }

  private static DraftInput draft(Decision decision, String path) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /old", "Receive HTTP requests", null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Expose an endpoint."), route(path), policy(decision)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/old", null, null, null)),
        decision == null ? List.of(new QuestionInput("route-choice", List.of("entry"), "Keep /old or use /new?")) : List.of(),
        new DraftSettings(false, null));
  }

  private static FactInput route(String path) {
    return new FactInput("route", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Expose the requested route.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, properties(path), List.of()));
  }

  private static FactInput policy(Decision decision) {
    return new FactInput("route-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested /old; alternative /new.", null,
        new RequirementPolicyConflict("route-policy", "contextPath", "/old", "/new", "route-choice", decision));
  }

  private static List<PlanProperty> properties(String path) {
    return List.of(new PlanProperty("externalRoute", "false"), new PlanProperty("contextPath", path));
  }

  private static DraftUpdate update(List<FactInput> facts) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), facts,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("route-choice"), null);
  }

  private static void begin(ConversationService conversations, RequirementDraftStore store, String chat, String text) {
    var source = conversations.archive().beginInput(chat, text, null);
    conversations.archive().resolveInput(chat, source, text);
    store.beginTurn(chat, text);
  }
}
