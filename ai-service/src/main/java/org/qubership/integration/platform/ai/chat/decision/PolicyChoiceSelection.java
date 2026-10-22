package org.qubership.integration.platform.ai.chat.decision;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;

/** Persists the option selected on an exact policy card, independently of model capture. */
@ApplicationScoped
public class PolicyChoiceSelection {
  public static final String ARTIFACT_TYPE = "requirement-policy-choice";
  private static final String OFFER_PRODUCER = "requirement-policy-choice-offer";
  private static final String PRODUCER = "requirement-policy-choice";

  public record Pending(Reference draftReference, Reference questionDraftReference, String sourceGeneration,
      String sourceTurnId, FactInput fact, QuestionInput question, long cardRevision, String hash) {}

  public record Selection(Pending offered, Decision decision, String selectedValue, String sourceGeneration,
      String sourceTurnId, Reference sourceInput, long cardRevision) {}

  public record TrustedSelection(Reference reference, Selection selection) {}

  private record Context(Revision revision, RequirementDraft draft, ConversationArchive.Document source) {}

  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final ConversationService conversations;

  @Inject
  public PolicyChoiceSelection(CompilationArtifacts artifacts, CompilationSessions sessions, ConversationService conversations) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.conversations = conversations;
  }

  public Optional<Pending> pending(String conversationId, long runRevision) {
    return context(conversationId).flatMap(context -> {
      var selections = trusted(conversationId);
      java.util.function.Predicate<Pending> awaitingCapture = offered -> selections.stream().anyMatch(value ->
          value.selection().offered().draftReference().equals(offered.draftReference())
              && value.selection().offered().fact().equals(offered.fact())
              && Objects.equals(value.selection().sourceTurnId(), ConversationArchive.latestInputId(context.source())));
      var offers = artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).stream()
          .filter(revision -> OFFER_PRODUCER.equals(revision.producerId()))
          .map(revision -> artifacts.payload(revision, Pending.class))
          .filter(offered -> offered.draftReference().equals(context.revision().reference())
              && offered.sourceGeneration().equals(context.source().generation()) && validOffer(conversationId, context, offered))
          .toList();
      var current = offers.stream().filter(offered -> offered.cardRevision() == runRevision
          && !selected(conversationId, offered) && !awaitingCapture.test(offered)).reduce((first, last) -> last);
      if (current.isPresent()) return current;
      var replacement = offers.stream().filter(offered -> offered.cardRevision() == -1).reduce((first, last) -> last);
      if (replacement.isPresent()) {
        var previous = replacement.get();
        var offered = offer(conversationId, previous.draftReference(), previous.questionDraftReference(),
            previous.sourceGeneration(), previous.sourceTurnId(), previous.fact(), previous.question(), runRevision);
        if (!selected(conversationId, offered) && !awaitingCapture.test(offered)) return Optional.of(saveOffer(context, offered));
      }
      var anchor = context.source().activeTurns().stream().filter(PolicyChoiceSelection::userReady).reduce((first, last) -> last);
      if (anchor.isEmpty()) return Optional.empty();
      for (var fact : context.draft().authoredDraft().facts()) {
        if (fact.policyConflict() == null || fact.policyConflict().decision() != null) continue;
        var question = question(context.draft(), fact);
        if (question.isEmpty()) continue;
        var offered = offer(conversationId, context.revision().reference(), context.revision().reference(),
            context.source().generation(), anchor.get().id(), fact, question.get(), runRevision);
        if (!selected(conversationId, offered) && !awaitingCapture.test(offered)) return Optional.of(saveOffer(context, offered));
      }
      return Optional.empty();
    });
  }

  /** Resolves an existing amendment offer without creating or rebinding a card. */
  public Optional<Pending> pendingReplacement(String conversationId) {
    return context(conversationId).flatMap(context -> {
      var selections = trusted(conversationId);
      String sourceTurn = ConversationArchive.latestInputId(context.source());
      return artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).stream()
          .filter(revision -> OFFER_PRODUCER.equals(revision.producerId()))
          .map(revision -> artifacts.payload(revision, Pending.class))
          .filter(offered -> offered.cardRevision() == -1
              && offered.draftReference().equals(context.revision().reference())
              && Objects.equals(offered.sourceTurnId(), sourceTurn)
              && validOffer(conversationId, context, offered)
              && context.source().activeTurns().stream().anyMatch(turn -> turn.id().equals(sourceTurn)
                  && userReady(turn) && turn.consumedDraftReference() == null)
              && selections.stream().noneMatch(value -> value.selection().offered().draftReference().equals(offered.draftReference())
                  && value.selection().offered().fact().equals(offered.fact())
                  && Objects.equals(value.selection().sourceTurnId(), sourceTurn)))
          .reduce((first, last) -> last);
    });
  }

  public ChatEvent.Decision decision(Pending pending, long revision) {
    var policy = pending.fact().policyConflict();
    return new ChatEvent.Decision(ARTIFACT_TYPE + ":" + pending.hash(), "clarify", pending.question().text(),
        ARTIFACT_TYPE, pending.hash(), revision, null, List.of(),
        List.of(ChatEvent.KEEP_REQUESTED_ACTION, ChatEvent.USE_RECOMMENDED_ACTION), null, List.of(), List.of(),
        Map.of(ChatEvent.KEEP_REQUESTED_ACTION, "Keep " + policy.requestedValue(),
            ChatEvent.USE_RECOMMENDED_ACTION, "Use recommended " + policy.recommendedValue()));
  }

  public Optional<TrustedSelection> confirm(String conversationId, ChatDecisionCommand command, long runRevision) {
    var context = context(conversationId).orElse(null);
    if (context == null) return Optional.empty();
    return conversations.archive().withAuthority(conversationId, context.source().generation(),
        ConversationArchive.latestInputId(context.source()), () -> {
          var offered = pending(conversationId, runRevision).orElse(null);
          var decision = decisionFor(command.getAction());
          if (offered == null || decision == null || command.getRevision() != runRevision
              || !ARTIFACT_TYPE.equals(command.getArtifactType()) || !offered.hash().equals(command.getArtifactHash())) {
            return Optional.empty();
          }
          var source = conversations.archive().read(conversationId);
          var turns = source.activeTurns();
          int anchor = java.util.stream.IntStream.range(0, turns.size())
              .filter(index -> turns.get(index).id().equals(offered.sourceTurnId())).findFirst().orElse(-1);
          if (anchor < 0) return Optional.empty();
          var replies = turns.subList(anchor + 1, turns.size()).stream()
              .filter(turn -> userReady(turn) && turn.consumedDraftReference() == null).toList();
          if (replies.isEmpty() || !replies.getLast().id().equals(ConversationArchive.latestInputId(source))) {
            return Optional.empty();
          }
          if (replies.subList(0, replies.size() - 1).stream().anyMatch(turn -> !stalePolicyControl(turn, offered))) {
            return Optional.empty();
          }
          var reply = replies.getLast();
          if (!matchesSourceDecision(reply, command.getAction(), offered.hash(), runRevision)) return Optional.empty();
          var input = artifacts.append(new AppendCommand(context.revision().compilationId(), Kind.USER_INPUT, "1",
              PRODUCER, "1", new UserInput(reply.id(), ARTIFACT_TYPE, reply.message().content(), reply.message().createdAt(),
                  offered.draftReference(), List.of(reply.id())), List.of(offered.draftReference()), null));
          var selection = new Selection(offered, decision, selectedValue(offered.fact().policyConflict(), decision),
              source.generation(), reply.id(), input.reference(), runRevision);
          var revision = artifacts.append(new AppendCommand(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE,
              "1", PRODUCER, "1", selection,
              java.util.stream.Stream.of(offered.draftReference(), offered.questionDraftReference(), input.reference()).distinct().toList(), null));
          return Optional.of(new TrustedSelection(revision.reference(), selection));
        });
  }

  /** Reuses a previously presented contract only after the store validates an explicit answer amendment. */
  public void offerReplacement(String conversationId, FactInput candidate) {
    var context = context(conversationId).orElse(null);
    if (context == null || candidate.policyConflict() == null || candidate.policyConflict().decision() == null) return;
    var old = context.draft().authoredDraft().facts().stream()
        .filter(fact -> fact.sourceFactId().equals(candidate.sourceFactId())).findFirst().orElse(null);
    if (old == null || old.policyConflict() == null || old.policyConflict().decision() == null
        || old.policyConflict().decision() == candidate.policyConflict().decision()
        || !unresolved(old).equals(unresolved(candidate))) return;
    String source = ConversationArchive.latestInputId(context.source());
    if (source == null || context.source().activeTurns().stream().noneMatch(turn -> turn.id().equals(source)
        && userReady(turn) && turn.consumedDraftReference() == null)) return;
    if (trusted(conversationId).stream().anyMatch(value -> value.selection().offered().draftReference().equals(context.revision().reference())
        && value.selection().sourceTurnId().equals(source))) return;
    for (var revision : artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_DRAFT).reversed()) {
      var draft = artifacts.payload(revision, RequirementDraft.class);
      var question = question(draft, old);
      if (question.isEmpty()) continue;
      var offered = offer(conversationId, context.revision().reference(), revision.reference(), context.source().generation(),
          source, unresolved(old), question.get(), -1);
      saveOffer(context, offered);
      return;
    }
  }

  public boolean permits(String conversationId, Reference base, String generation, String turnId, FactInput candidate) {
    return trusted(conversationId).stream().map(TrustedSelection::selection).anyMatch(selection ->
        matchesSource(selection, base, generation, turnId) && matchesPolicyFact(selection, candidate));
  }

  /** Typed choices authorize only their decision and an existing matching property value. */
  public boolean permitsAmendment(String conversationId, Reference base, String generation, String turnId,
      DraftInput before, DraftInput candidate, String path) {
    if (before == null || candidate == null) return false;
    for (var trusted : trusted(conversationId)) {
      var selection = trusted.selection();
      if (!matchesSource(selection, base, generation, turnId)) continue;
      var selectedFact = candidate.facts().stream().filter(fact ->
          fact.sourceFactId().equals(selection.offered().fact().sourceFactId())).findFirst().orElse(null);
      if (!matchesPolicyFact(selection, selectedFact)) continue;
      if (("/facts/" + selectedFact.sourceFactId() + "/policyConflict/decision").equals(path)) return true;
      for (var original : before.facts()) {
        var intent = original.elementIntent();
        if (!ownsSelectedProperty(selection, intent)) continue;
        var replacement = candidate.facts().stream().filter(fact -> fact.sourceFactId().equals(original.sourceFactId()))
            .findFirst().orElse(null);
        if (replacement == null || !unresolved(original).equals(unresolved(restoreSelectedProperty(selection, original, replacement)))) continue;
        for (int index = 0; index < intent.requestedProperties().size(); index++) {
          var property = intent.requestedProperties().get(index);
          if (property.key().equals(selection.offered().fact().policyConflict().propertyKey())
              && propertyAmendmentPath(original, index).equals(path)
              && replacement.elementIntent() != null
              && selection.selectedValue().equals(replacement.elementIntent().requestedProperties().get(index).value())) return true;
        }
      }
    }
    return false;
  }

  /** Presents current choice fields; native admission still validates the complete candidate. */
  public List<String> pendingAmendmentPaths(String conversationId) {
    var context = context(conversationId).orElse(null);
    if (context == null) return List.of();
    String turnId = ConversationArchive.latestInputId(context.source());
    if (context.source().activeTurns().stream().noneMatch(turn -> turn.id().equals(turnId)
        && userReady(turn) && turn.consumedDraftReference() == null)) return List.of();
    var paths = new java.util.LinkedHashSet<String>();
    for (var trusted : trusted(conversationId)) {
      var selection = trusted.selection();
      if (!matchesSource(selection, context.revision().reference(), context.source().generation(), turnId)) continue;
      paths.add("/facts/" + selection.offered().fact().sourceFactId() + "/policyConflict/decision");
      for (var fact : context.draft().authoredDraft().facts()) {
        var intent = fact.elementIntent();
        if (!ownsSelectedProperty(selection, intent)) continue;
        for (int index = 0; index < intent.requestedProperties().size(); index++) {
          if (intent.requestedProperties().get(index).key().equals(selection.offered().fact().policyConflict().propertyKey())) {
            paths.add(propertyAmendmentPath(fact, index));
          }
        }
      }
    }
    return List.copyOf(paths);
  }

  private static String propertyAmendmentPath(FactInput fact, int index) {
    return "/facts/" + fact.sourceFactId() + "/elementIntent/requestedProperties/" + index + "/value";
  }

  public static boolean isPolicyDecisionSource(ConversationArchive.Turn turn) {
    var metadata = sourceMetadata(turn);
    return metadata != null && (policyCommand(metadata) || policyCommand(metadata.get("resolvedDecision"))
        || policyCommand(metadata.get("decision")));
  }

  private static boolean policyCommand(com.fasterxml.jackson.databind.JsonNode command) {
    return command != null && command.isObject() && (ARTIFACT_TYPE.equals(command.path("artifactType").asText(null))
        || decisionFor(command.path("action").asText(null)) != null);
  }

  private static boolean matchesSource(Selection selection, Reference base, String generation, String turnId) {
    return selection.offered().draftReference().equals(base) && Objects.equals(selection.sourceGeneration(), generation)
        && Objects.equals(selection.sourceTurnId(), turnId);
  }

  private static boolean matchesPolicyFact(Selection selection, FactInput candidate) {
    return candidate != null && candidate.policyConflict() != null && candidate.policyConflict().decision() == selection.decision()
        && unresolved(restoreSelectedProperty(selection, selection.offered().fact(), candidate)).equals(selection.offered().fact());
  }

  private static boolean ownsSelectedProperty(Selection selection, RequirementElementIntent intent) {
    var owners = selection.offered().fact().interactionIds();
    return intent != null && owners.size() == 1 && intent.placement() == RequirementElementIntent.Placement.ANCHOR
        && owners.getFirst().equals(intent.elementRef()) && owners.getFirst().equals(intent.ownerInteractionId());
  }

  private static FactInput restoreSelectedProperty(Selection selection, FactInput original, FactInput candidate) {
    var before = original.elementIntent();
    var after = candidate.elementIntent();
    if (!ownsSelectedProperty(selection, before) || !ownsSelectedProperty(selection, after)
        || before.requestedProperties().size() != after.requestedProperties().size()) return candidate;
    var properties = new ArrayList<>(after.requestedProperties());
    String key = selection.offered().fact().policyConflict().propertyKey();
    if (before.requestedProperties().stream().filter(property -> key.equals(property.key())).count() != 1) return candidate;
    for (int index = 0; index < before.requestedProperties().size(); index++) {
      var old = before.requestedProperties().get(index);
      var replacement = properties.get(index);
      if (key.equals(old.key()) && old.key().equals(replacement.key()) && selection.selectedValue().equals(replacement.value())) {
        properties.set(index, old);
      }
    }
    var intent = new RequirementElementIntent(after.elementRef(), after.elementType(), after.placement(), after.ownerInteractionId(),
        after.successorElementRef(), after.predecessorElementRef(), after.parentElementRef(), after.requestedName(), properties, after.exactValues(), after.bodyBinding());
    return new FactInput(candidate.sourceFactId(), candidate.interactionIds(), candidate.kind(), candidate.polarity(), candidate.text(),
        candidate.fieldMapping(), candidate.policyConflict(), intent);
  }

  /** Only selections written by the validated command path carry independent choice authority. */
  public List<TrustedSelection> trusted(String conversationId) {
    var context = context(conversationId).orElse(null);
    if (context == null) return List.of();
    return artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).stream()
        .filter(revision -> PRODUCER.equals(revision.producerId()))
        .map(revision -> new TrustedSelection(revision.reference(), artifacts.payload(revision, Selection.class)))
        .filter(value -> validSelection(conversationId, context, value)).toList();
  }

  private boolean validSelection(String conversationId, Context context, TrustedSelection trusted) {
    var selection = trusted.selection();
    var offered = selection.offered();
    if (selection.decision() == null || !Objects.equals(selection.sourceGeneration(), context.source().generation())
        || !Objects.equals(selection.sourceGeneration(), offered.sourceGeneration()) || !validOffer(conversationId, context, offered)
        || selection.cardRevision() != offered.cardRevision()
        || !Objects.equals(selectedValue(offered.fact().policyConflict(), selection.decision()), selection.selectedValue())) return false;
    var selectionRevision = artifacts.get(context.revision().compilationId(), trusted.reference()).orElseThrow();
    if (!selectionRevision.inputs().containsAll(java.util.stream.Stream.of(offered.draftReference(),
        offered.questionDraftReference(), selection.sourceInput()).toList())
        || artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).stream()
            .filter(revision -> OFFER_PRODUCER.equals(revision.producerId()) && revision.sequence() < selectionRevision.sequence())
            .map(revision -> artifacts.payload(revision, Pending.class)).noneMatch(offered::equals)) return false;
    var input = artifacts.get(context.revision().compilationId(), selection.sourceInput())
        .filter(revision -> revision.kind() == Kind.USER_INPUT && PRODUCER.equals(revision.producerId())).orElse(null);
    if (input == null) return false;
    var user = artifacts.payload(input, UserInput.class);
    return user.sourceTurnIds().equals(List.of(selection.sourceTurnId()))
        && Objects.equals(user.draftReference(), offered.draftReference())
        && context.source().activeTurns().stream().anyMatch(turn -> turn.id().equals(selection.sourceTurnId())
            && userReady(turn) && Objects.equals(turn.message().content(), user.text())
            && Objects.equals(turn.message().createdAt(), user.receivedAt())
            && matchesSourceDecision(turn, selection.decision() == Decision.KEEP_REQUESTED
                ? ChatEvent.KEEP_REQUESTED_ACTION : ChatEvent.USE_RECOMMENDED_ACTION, offered.hash(), selection.cardRevision()));
  }

  private boolean validOffer(String conversationId, Context context, Pending offered) {
    if (!offered.sourceGeneration().equals(context.source().generation())
        || context.source().activeTurns().stream().noneMatch(turn -> turn.id().equals(offered.sourceTurnId()) && userReady(turn))) return false;
    var base = artifacts.get(context.revision().compilationId(), offered.draftReference())
        .filter(value -> value.kind() == Kind.REQUIREMENT_DRAFT).orElse(null);
    var questionRevision = artifacts.get(context.revision().compilationId(), offered.questionDraftReference())
        .filter(value -> value.kind() == Kind.REQUIREMENT_DRAFT).orElse(null);
    if (base == null || questionRevision == null || offered.fact().policyConflict() == null
        || offered.fact().policyConflict().decision() != null) return false;
    var baseDraft = artifacts.payload(base, RequirementDraft.class);
    var questionDraft = artifacts.payload(questionRevision, RequirementDraft.class);
    return baseDraft.authoredDraft() != null && baseDraft.authoredDraft().facts().stream()
        .anyMatch(fact -> unresolved(fact).equals(offered.fact()))
        && question(questionDraft, offered.fact()).filter(offered.question()::equals).isPresent()
        && offer(conversationId, offered.draftReference(), offered.questionDraftReference(), offered.sourceGeneration(),
            offered.sourceTurnId(), offered.fact(), offered.question(), offered.cardRevision()).hash().equals(offered.hash());
  }

  private Optional<Context> context(String conversationId) {
    if (conversations == null) return Optional.empty();
    return sessions.current(conversationId).flatMap(session -> artifacts.latest(session.compilationId(), Kind.REQUIREMENT_DRAFT))
        .map(revision -> new Context(revision, artifacts.payload(revision, RequirementDraft.class), conversations.archive().read(conversationId)))
        .filter(value -> value.draft().authoredDraft() != null);
  }

  private boolean selected(String conversationId, Pending offered) {
    return trusted(conversationId).stream().anyMatch(value -> value.selection().offered().hash().equals(offered.hash()));
  }

  private Pending saveOffer(Context context, Pending offered) {
    if (artifacts.history(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).stream()
        .filter(value -> OFFER_PRODUCER.equals(value.producerId()))
        .map(value -> artifacts.payload(value, Pending.class)).noneMatch(value -> value.hash().equals(offered.hash()))) {
      artifacts.append(new AppendCommand(context.revision().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE, "1",
          OFFER_PRODUCER, "1", offered,
          java.util.stream.Stream.of(offered.draftReference(), offered.questionDraftReference()).distinct().toList(), null));
    }
    return offered;
  }

  private static Optional<QuestionInput> question(RequirementDraft draft, FactInput fact) {
    if (draft.authoredDraft() == null || fact.policyConflict() == null) return Optional.empty();
    boolean asked = draft.authoredDraft().facts().stream().anyMatch(old -> unresolved(old).equals(unresolved(fact))
        && old.policyConflict().decision() == null);
    return !asked ? Optional.empty() : draft.authoredDraft().openQuestions().stream().filter(question ->
        question.questionId().equals(fact.policyConflict().questionId()) && question.interactionIds().equals(fact.interactionIds())).findFirst();
  }

  private static FactInput unresolved(FactInput fact) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
        fact.fieldMapping(), fact.policyConflict() == null ? null : fact.policyConflict().unresolved(), fact.elementIntent());
  }

  private static Pending offer(String conversationId, Reference base, Reference questionBase, String generation,
      String source, FactInput fact, QuestionInput question, long revision) {
    String hash = CaptureProposal.digest(List.of(conversationId, base, questionBase, generation, source, unresolved(fact), question, revision));
    return new Pending(base, questionBase, generation, source, unresolved(fact), question, revision, hash);
  }

  private static boolean userReady(ConversationArchive.Turn turn) {
    return turn.message().role() == ConversationMessage.Role.USER && "READY".equals(turn.status());
  }

  private static boolean matchesSourceDecision(ConversationArchive.Turn turn, String action, String hash, long revision) {
    var decision = sourceDecision(turn);
    return decision != null && decision.isObject() && Objects.equals(action, decision.path("action").asText(null))
        && ARTIFACT_TYPE.equals(decision.path("artifactType").asText(null))
        && Objects.equals(hash, decision.path("artifactHash").asText(null))
        && decision.path("revision").isIntegralNumber() && decision.path("revision").asLong() == revision;
  }

  private static boolean stalePolicyControl(ConversationArchive.Turn turn, Pending current) {
    var decision = sourceDecision(turn);
    if (decision == null || !decision.isObject() || !ARTIFACT_TYPE.equals(decision.path("artifactType").asText(null))
        || decisionFor(decision.path("action").asText(null)) == null || !decision.path("revision").isIntegralNumber()
        || decision.path("artifactHash").asText("").isBlank()) return false;
    var command = new ChatDecisionCommand();
    command.setAction(decision.path("action").asText());
    command.setArtifactType(ARTIFACT_TYPE);
    command.setArtifactHash(decision.path("artifactHash").asText());
    command.setRevision(decision.path("revision").asLong());
    String marker = ChatDecisionService.transcriptMarker(command);
    return (turn.rawText() == null || turn.rawText().isBlank() || marker.equals(turn.rawText()))
        && marker.equals(turn.message().content())
        && (command.getRevision() != current.cardRevision() || !current.hash().equals(command.getArtifactHash()));
  }

  private static com.fasterxml.jackson.databind.JsonNode sourceDecision(ConversationArchive.Turn turn) {
    var metadata = sourceMetadata(turn);
    return metadata == null ? null : metadata.get("resolvedDecision");
  }

  private static com.fasterxml.jackson.databind.JsonNode sourceMetadata(ConversationArchive.Turn turn) {
    var metadata = turn.metadata();
    if (metadata != null && !metadata.has("resolvedDecision") && metadata.has("clarificationDraft")) {
      metadata = metadata.get("submitted");
    }
    return metadata;
  }

  private static Decision decisionFor(String action) {
    return ChatEvent.KEEP_REQUESTED_ACTION.equals(action) ? Decision.KEEP_REQUESTED
        : ChatEvent.USE_RECOMMENDED_ACTION.equals(action) ? Decision.USE_RECOMMENDED : null;
  }

  private static String selectedValue(RequirementPolicyConflict policy, Decision decision) {
    return decision == Decision.KEEP_REQUESTED ? policy.requestedValue() : policy.recommendedValue();
  }
}
