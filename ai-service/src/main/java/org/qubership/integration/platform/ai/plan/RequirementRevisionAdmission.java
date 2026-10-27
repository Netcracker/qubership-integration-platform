package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionAdmission;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection.TrustedSelection;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Admits source-scoped changes while retaining validated content and author answers. */
final class RequirementRevisionAdmission {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RequirementRevisionAdmission() {}

  enum PreservationOutcome { VERIFIED, VIOLATED, UNPROVEN }

  record NativeInputs(RequirementBrief brief, ChainPlanGraph graph) {}

  record PreservationFinding(String path, List<String> ownerInteractionIds, String baselineOrigin,
      List<Reference> evidenceReferences, String checker, PreservationOutcome outcome, String message) {}

  record PreservationReport(PreservationOutcome outcome, Reference baseReference, Reference attemptReference,
      String sourceGeneration, String sourceTurnId, String baseHash, String protectionHash, String candidateHash,
      String sourceHash, List<String> changedPaths, List<PreservationFinding> findings,
      List<Issue> nativeFindings, List<RequirementCaptureProjection.PendingWork> pendingWork) {}

  /** Read-only diagnostics for a persisted refusal; this result grants no admission authority. */
  static PreservationReport evaluate(String conversationId, Reference attemptRef, RequirementDraftStore drafts,
      CompilationArtifacts artifacts, ConversationService conversations, DeterministicElementSchemaService schemas,
      NativeInputs inputs) {
    var findings = new ArrayList<PreservationFinding>();
    var nativeFindings = new ArrayList<Issue>();
    var pending = new ArrayList<RequirementCaptureProjection.PendingWork>();
    var current = drafts.latestRevision(conversationId).orElse(null);
    var rejected = drafts.rejectedCaptureRevision(conversationId).orElse(null);
    String compilationId = current != null ? current.compilationId()
        : rejected == null ? null : rejected.compilationId();
    var attemptRevision = compilationId == null || attemptRef == null ? null
        : artifacts.get(compilationId, attemptRef).filter(value -> value.kind() == Kind.REQUIREMENT_CAPTURE_ATTEMPT)
            .orElse(null);
    if (attemptRevision == null) {
      findings.add(finding("/attempt", List.of(), "unresolved", List.of(), "persisted-attempt",
          PreservationOutcome.UNPROVEN, "The exact capture attempt is unavailable in this conversation's compilation."));
      return report(null, attemptRef, null, null, null, null, null, null, List.of(), findings, nativeFindings, pending);
    }
    var attempt = artifacts.payload(attemptRevision, RequirementDraftStore.RequirementCaptureAttempt.class);
    var references = attempt.acceptedDraftReference() == null ? List.of(attemptRef)
        : List.of(attempt.acceptedDraftReference(), attemptRef);
    var baseRevision = attempt.acceptedDraftReference() == null ? null
        : artifacts.get(compilationId, attempt.acceptedDraftReference())
            .filter(value -> value.kind() == Kind.REQUIREMENT_DRAFT).orElse(null);
    var baseDraft = baseRevision == null ? null : artifacts.payload(baseRevision, RequirementDraft.class);
    var base = baseDraft == null ? null : baseDraft.authoredDraft();
    String baseHash = CaptureProposal.digest(base);
    var source = conversations == null ? null : conversations.archive().read(conversationId);
    String sourceHash = source == null ? null : CaptureProposal.digest(source);
    if (source == null || attempt.sourceTurnId() == null
        || !Objects.equals(source.generation(), attempt.sourceGeneration())
        || !Objects.equals(ConversationArchive.latestInputId(source), attempt.sourceTurnId())
        || source.activeTurns().stream().noneMatch(turn -> Objects.equals(turn.id(), attempt.sourceTurnId())
            && turn.message().role() == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER
            && "READY".equals(turn.status()))) {
      findings.add(finding("/source", List.of(), "conversation-archive", references, "source-identity",
          PreservationOutcome.UNPROVEN, "The attempt does not resolve to the current archived source generation and turn."));
    }
    if (!Objects.equals(current == null ? null : current.reference(), attempt.acceptedDraftReference())
        || attempt.acceptedDraftReference() != null && base == null) {
      findings.add(finding("/base", List.of(), "accepted-revision", references, "base-identity",
          PreservationOutcome.UNPROVEN, "The exact accepted base is missing or stale; later revisions cannot supply its authority."));
    }
    if (attempt.accepted() || !"requirement-capture-tools".equals(attemptRevision.producerId())
        || !List.of("captureRequirementDraft", "updateRequirementDraft").contains(attempt.toolName())) {
      findings.add(finding("/attempt", List.of(), "capture-attempt", references, "candidate-reconstruction",
          PreservationOutcome.UNPROVEN, "The native producer and a rejected capture or update are required to reconstruct the exact base."));
      return report(attempt.acceptedDraftReference(), attemptRef, attempt.sourceGeneration(), attempt.sourceTurnId(),
          baseHash, null, null, sourceHash, List.of(), findings, nativeFindings, pending);
    }
    attempt.issues().forEach(issue -> nativeFindings.add(new Issue(issue.code(), issue.path(), issue.entityId(), issue.message())));
    DraftInput candidate;
    List<Issue> validation;
    try {
      candidate = drafts.captureCandidate(attemptRevision);
      if ("captureRequirementDraft".equals(attempt.toolName()) || attempt.resolvedCandidate() != null) {
        validation = RequirementCaptureEditor.initialize(candidate).issues();
      } else {
        var edit = RequirementCaptureEditor.apply(base, MAPPER.convertValue(attempt.arguments(), DraftUpdate.class));
        validation = edit.issues();
      }
    } catch (IllegalArgumentException invalidArguments) {
      findings.add(finding("/attempt/arguments", List.of(), "capture-attempt", references, "candidate-reconstruction",
          PreservationOutcome.UNPROVEN, "The persisted arguments cannot reconstruct a typed candidate: " + invalidArguments.getMessage()));
      return report(attempt.acceptedDraftReference(), attemptRef, attempt.sourceGeneration(), attempt.sourceTurnId(),
          baseHash, null, null, sourceHash, List.of(), findings, nativeFindings, pending);
    }
    nativeFindings.addAll(validation);
    validation.forEach(issue -> findings.add(finding(issue.path(), issue.entityId() == null ? List.of() : List.of(issue.entityId()),
        "native-capture-contract", references, "RequirementCaptureEditor", PreservationOutcome.VIOLATED, issue.message())));
    if (candidate == null) {
      findings.add(finding("/candidate", List.of(), "capture-attempt", references, "candidate-reconstruction",
          PreservationOutcome.UNPROVEN, "The native editor did not produce a complete candidate."));
      return report(attempt.acceptedDraftReference(), attemptRef, attempt.sourceGeneration(), attempt.sourceTurnId(),
          baseHash, null, null, sourceHash, List.of(), findings, nativeFindings, pending);
    }
    var before = diagnosticTree(base);
    var after = diagnosticTree(candidate);
    var changed = new ArrayList<String>();
    changedPaths(before, after, "", changed);
    var selectedChanges = selectedPolicyChanges(base, candidate, attempt.acceptedDraftReference(),
        attempt.sourceGeneration(), attempt.sourceTurnId(), drafts.trustedPolicyChoices(conversationId));
    var bodyChanges = source != null && validation.isEmpty()
        && Objects.equals(current == null ? null : current.reference(), attempt.acceptedDraftReference())
        && Objects.equals(source.generation(), attempt.sourceGeneration())
        && Objects.equals(ConversationArchive.latestInputId(source), attempt.sourceTurnId())
        && attempt.authorEvidenceHash() != null
        && Objects.equals(attempt.authorEvidenceHash(), CaptureProposal.digest(AuthorSourceEvidence.packet(source)))
            ? RequirementBodySources.completionPaths(base, candidate, AuthorSourceEvidence.packet(source)) : Set.<String>of();
    var provenChanges = new LinkedHashMap<>(selectedChanges);
    changed.stream().filter(path -> bodyChanges.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/")))
        .forEach(path -> provenChanges.put(path, references));
    for (var path : changed) {
      var selection = provenChanges.get(path);
      boolean bodyCompletion = selection != null && !selectedChanges.containsKey(path);
      var originRefs = new ArrayList<>(references);
      if (selection != null) originRefs.addAll(selection);
      findings.add(finding(path, owners(path, base, candidate), before.at(path).isMissingNode()
          ? "candidate-addition" : selection == null ? "accepted-extraction"
              : bodyCompletion ? "retained-author-source" : "validated-policy-choice", originRefs,
          "author-origin", selection == null ? PreservationOutcome.UNPROVEN : PreservationOutcome.VERIFIED,
          selection == null ? "No independent typed author selection or native meaning contract discharges this changed obligation."
              + " Source occurrence, model labels, and an earlier draft do not establish its meaning."
              : bodyCompletion ? "The missing metadata is independently reconstructed from retained source for the unchanged native script, complete body, owner, and fact."
              : "The exact policy decision, offered-question closure, or existing property value implements an independently validated selection for this unchanged owner and policy contract."));
    }
    if (changed.isEmpty()) {
      findings.add(finding("/", List.of(), "accepted-extraction", references, "representation-retention",
          PreservationOutcome.VERIFIED, "The captured representation is unchanged; this does not establish source completeness."));
    }
    var protectionRevision = LegacyCaptureProtectionAdapter.protectionBefore(artifacts, attemptRevision, attempt)
        .orElse(null);
    String protectionHash = null;
    if (protectionRevision != null) {
      var protection = LegacyCaptureProtectionAdapter.payload(artifacts, protectionRevision);
      protectionHash = CaptureProposal.digest(protection);
      var protectionRefs = new ArrayList<>(references);
      protectionRefs.add(protectionRevision.reference());
      if (!Objects.equals(protection.baseIdentity(), base == null ? null : baseHash)) {
        findings.add(finding("/protection", List.of(), "rejected-capture-protection", protectionRefs, "protection-identity",
            PreservationOutcome.UNPROVEN, "Protected rejected content belongs to a different accepted base."));
      } else {
        var protectedTree = pendingValues(protection.values());
        var protectedFacts = protectedTree.putObject("facts");
        protection.facts().forEach(fact -> protectedFacts.set(fact.sourceFactId(), MAPPER.valueToTree(fact)));
        var losses = new ArrayList<String>();
        protectedChanges(protectedTree, after, "", losses);
        if (losses.isEmpty()) {
          findings.add(finding("/protection", List.of(), "rejected-capture-protection", protectionRefs, "protected-retention",
              PreservationOutcome.VERIFIED, "All recorded protected rejected values remain unchanged."));
        }
        losses.forEach(path -> findings.add(finding(path, owners(path, base, candidate), "rejected-capture-protection",
            protectionRefs, "protected-retention", provenChanges.containsKey(path)
                ? PreservationOutcome.VERIFIED : PreservationOutcome.UNPROVEN,
            provenChanges.containsKey(path) ? "Independent native evidence covers only this exact metadata or selected policy value."
                : "A protected rejected value changed or disappeared; its independent meaning and dependencies remain unproved.")));
      }
    } else if (drafts.repairConstraints(conversationId) != null) {
      findings.add(finding("/protection", List.of(), "unresolved", references, "protection-identity",
          PreservationOutcome.UNPROVEN, "Active rejected protection has no matching persisted snapshot for this attempt."));
    }
    if (validation.isEmpty()) {
      var nativePaths = new java.util.LinkedHashSet<>(selectedChanges.keySet());
      nativePaths.addAll(bodyChanges);
      var bindingBase = nativePaths.isEmpty() ? base : amended(base, candidate, nativePaths);
      var bindings = baseDraft == null ? List.<org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint>of()
          : baseDraft.catalogBindings().stream().filter(binding -> stableOwner(binding.interactionId(), bindingBase, candidate)).toList();
      var targets = new LinkedHashMap<String, String>();
      RequirementCaptureProjection.resolvedTargets(baseDraft).forEach((owner, target) -> {
        if (stableOwner(owner, bindingBase, candidate)) targets.put(owner, target);
        else findings.add(finding("/interactions/" + escape(owner), List.of(owner), "accepted-target-resolution", references,
            "binding-inputs", PreservationOutcome.UNPROVEN, "The owner changed; the accepted target cannot resolve this candidate."));
      });
      if (baseDraft != null) baseDraft.catalogBindings().stream().filter(binding -> !bindings.contains(binding)).forEach(binding ->
          findings.add(finding("/interactions/" + escape(binding.interactionId()), List.of(binding.interactionId()),
              "accepted-catalog-binding", references, "binding-inputs", PreservationOutcome.UNPROVEN,
              "The interaction, capability, or owned facts changed; fresh isolated catalog evidence is missing.")));
      pending.addAll(RequirementCaptureProjection.pending(candidate, bindings, targets));
      var projectedFacts = RequirementCaptureProjection.facts(candidate, targets);
      var order = RequirementCaptureEditor.validateElementOrder(RequirementCaptureProjection.flow(candidate), projectedFacts);
      nativeFindings.addAll(order);
      order.forEach(issue -> findings.add(finding(issue.path(), owners(issue.path(), base, candidate), "native-flow-contract",
          references, "RequirementCaptureEditor.validateElementOrder", PreservationOutcome.VIOLATED, issue.message())));
      findings.add(finding("/candidate", List.of(), "native-capture-contract", references, "RequirementCaptureProjection",
          PreservationOutcome.VERIFIED, "Pure fact, flow, and pending-work projections ran; pending work is retained separately."));
      checkSchemas(candidate, schemas, references, findings, nativeFindings);
      checkPolicyContracts(conversationId, drafts, artifacts, compilationId, candidate, references, findings);
      if (!changed.isEmpty()) {
        checkConsumers(candidate, baseDraft, bindings, targets, inputs, references, findings);
        for (var path : changed) {
          findings.add(finding(path, dependentOwners(owners(path, base, candidate), base, candidate),
              "typed-consumer-dependencies", references, "consumer-coverage", provenChanges.containsKey(path)
                  ? PreservationOutcome.VERIFIED : PreservationOutcome.UNPROVEN,
              provenChanges.containsKey(path)
                  ? "This exact source-derived body metadata or independently selected policy value follows its native contract. Native schema, brief coverage, requested-value, quality, and security checks cover the correction; other changes retain their own findings."
                  : "Independent origin and complete affected-consumer coverage are not established."
                  + " Kind, capability, binding, mapping, question, and order changes require their own native inputs;"
                  + " a disappearing readiness finding does not prove preservation."));
        }
      }
    } else {
      findings.add(finding("/candidate", List.of(), "native-capture-contract", references, "consumer-coverage",
          PreservationOutcome.UNPROVEN, "Pure projections and downstream checks cannot cover the invalid candidate."));
    }
    return report(attempt.acceptedDraftReference(), attemptRef, attempt.sourceGeneration(), attempt.sourceTurnId(),
        baseHash, protectionHash, CaptureProposal.digest(candidate), sourceHash, changed, findings, nativeFindings, pending);
  }

  private static PreservationFinding finding(String path, List<String> owners, String origin, List<Reference> refs,
      String checker, PreservationOutcome outcome, String message) {
    return new PreservationFinding(path, List.copyOf(owners), origin, List.copyOf(refs), checker, outcome, message);
  }

  private static Map<String, List<Reference>> selectedPolicyChanges(DraftInput base, DraftInput candidate,
      Reference baseReference, String sourceGeneration, String sourceTurnId, List<TrustedSelection> selections) {
    var proven = new LinkedHashMap<String, List<Reference>>();
    if (base == null || candidate == null || !RequirementCaptureEditor.validate(candidate).isEmpty()) return proven;
    for (var trusted : selections) {
      var selection = trusted.selection();
      var offered = selection.offered().fact();
      var policy = offered.policyConflict();
      if (!CompilerSecurityValidator.matchesExternalRbacPolicy(policy)
          || !Objects.equals(baseReference, selection.offered().draftReference())
          || !Objects.equals(sourceGeneration, selection.sourceGeneration())
          || !Objects.equals(sourceTurnId, selection.sourceTurnId()) || !base.facts().contains(offered)) continue;
      var answered = new FactInput(offered.sourceFactId(), offered.interactionIds(), offered.kind(), offered.polarity(),
          offered.text(), offered.fieldMapping(), new RequirementPolicyConflict(policy.policyId(), policy.propertyKey(),
              policy.requestedValue(), policy.recommendedValue(), policy.questionId(), selection.decision()), offered.elementIntent());
      var selectedFact = candidate.facts().stream().filter(fact -> fact.sourceFactId().equals(offered.sourceFactId()))
          .findFirst().orElse(null);
      if (selectedFact == null || !Objects.equals(selectedFact.policyConflict(), answered.policyConflict())) continue;
      var propertyChanges = new LinkedHashMap<String, List<Reference>>();
      var evidence = List.of(trusted.reference(), selection.offered().draftReference(),
          selection.offered().questionDraftReference(), selection.sourceInput());
      for (var fact : base.facts()) {
        var intent = fact.elementIntent();
        if (fact.polarity() != Polarity.POSITIVE || intent == null
            || intent.placement() != RequirementElementIntent.Placement.ANCHOR
            || !"http-trigger".equals(intent.elementType())
            || intent.requestedProperties().stream().noneMatch(property -> "externalRoute".equals(property.key())
                && "true".equalsIgnoreCase(property.value()))
            || !Objects.equals(intent.elementRef(), intent.ownerInteractionId())
            || !offered.interactionIds().equals(List.of(intent.elementRef()))
            || base.flow().interactions().stream().noneMatch(owner -> owner.interactionId().equals(intent.elementRef())
                && owner.direction() == org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND
                && candidate.flow().interactions().contains(owner))
            || base.capabilities().stream().noneMatch(capability -> capability.interactionId().equals(intent.elementRef())
                && "http-trigger".equals(capability.capabilityKey()) && candidate.capabilities().contains(capability))) continue;
        var replacement = candidate.facts().stream().filter(value -> value.sourceFactId().equals(fact.sourceFactId()))
            .findFirst().map(FactInput::elementIntent).orElse(null);
        if (replacement == null || !Objects.equals(intent.elementRef(), replacement.elementRef())
            || !Objects.equals(intent.elementType(), replacement.elementType())
            || intent.placement() != replacement.placement()
            || !Objects.equals(intent.ownerInteractionId(), replacement.ownerInteractionId())
            || intent.requestedProperties().size() != replacement.requestedProperties().size()) continue;
        for (int index = 0; index < intent.requestedProperties().size(); index++) {
          var before = intent.requestedProperties().get(index);
          var after = replacement.requestedProperties().get(index);
          if (Objects.equals(before.key(), policy.propertyKey()) && Objects.equals(before.key(), after.key())
              && !Objects.equals(before.value(), after.value()) && Objects.equals(after.value(), selection.selectedValue())) {
            propertyChanges.put("/facts/" + escape(fact.sourceFactId()) + "/elementIntent/requestedProperties/" + index + "/value", evidence);
          }
        }
      }
      if (propertyChanges.isEmpty()) continue;
      String decisionPath = "/facts/" + escape(offered.sourceFactId()) + "/policyConflict/decision";
      var policyPaths = new java.util.HashSet<>(propertyChanges.keySet());
      policyPaths.add(decisionPath);
      var normalized = amended(base, candidate, policyPaths);
      if (!normalized.facts().stream().filter(fact -> fact.sourceFactId().equals(offered.sourceFactId()))
          .findFirst().filter(selectedFact::equals).isPresent()) continue;
      proven.putAll(propertyChanges);
      proven.put(decisionPath, evidence);
      var question = selection.offered().question();
      var remainingQuestions = base.openQuestions().stream().filter(value -> !value.questionId().equals(question.questionId())).toList();
      if (base.openQuestions().contains(question) && remainingQuestions.equals(candidate.openQuestions())) {
        var closed = new DraftInput(base.flow(), base.facts(), base.capabilities(), remainingQuestions, base.settings());
        var closurePaths = new ArrayList<String>();
        changedPaths(diagnosticTree(base), diagnosticTree(closed), "", closurePaths);
        closurePaths.forEach(path -> proven.put(path, evidence));
      }
    }
    return Map.copyOf(proven);
  }

  private static PreservationReport report(Reference base, Reference attempt, String generation, String turn,
      String baseHash, String protectionHash, String candidateHash, String sourceHash, List<String> changed,
      List<PreservationFinding> findings, List<Issue> nativeFindings, List<RequirementCaptureProjection.PendingWork> pending) {
    var outcome = findings.stream().anyMatch(value -> value.outcome() == PreservationOutcome.VIOLATED)
        ? PreservationOutcome.VIOLATED : findings.isEmpty()
            || findings.stream().anyMatch(value -> value.outcome() == PreservationOutcome.UNPROVEN)
                ? PreservationOutcome.UNPROVEN : PreservationOutcome.VERIFIED;
    return new PreservationReport(outcome, base, attempt, generation, turn, baseHash, protectionHash, candidateHash,
        sourceHash, List.copyOf(changed), List.copyOf(findings), nativeFindings.stream().distinct().toList(), List.copyOf(pending));
  }

  private static ObjectNode diagnosticTree(DraftInput input) {
    if (input == null) return MAPPER.createObjectNode();
    boolean addressable = input.facts() != null && input.capabilities() != null && input.openQuestions() != null
        && input.flow() != null && input.flow().interactions() != null && input.flow().transitions() != null
        && uniqueIds(input.facts().stream().map(fact -> fact == null ? null : fact.sourceFactId()).toList())
        && uniqueIds(input.capabilities().stream().map(value -> value == null ? null : value.interactionId()).toList())
        && uniqueIds(input.flow().interactions().stream().map(value -> value == null ? null : value.interactionId()).toList())
        && uniqueIds(input.openQuestions().stream().map(value -> value == null ? null : value.questionId()).toList());
    if (!addressable) {
      var raw = MAPPER.createObjectNode();
      raw.set("unaddressableDraft", MAPPER.valueToTree(input));
      return raw;
    }
    var value = tree(input);
    var questions = value.putObject("openQuestions");
    input.openQuestions().forEach(question -> questions.set(question.questionId(), MAPPER.valueToTree(question)));
    var order = value.putObject("recordOrder");
    order.set("facts", MAPPER.valueToTree(input.facts().stream().map(FactInput::sourceFactId).toList()));
    order.set("capabilities", MAPPER.valueToTree(input.capabilities().stream().map(CapabilityInput::interactionId).toList()));
    order.set("interactions", MAPPER.valueToTree(input.flow().interactions().stream().map(InteractionInput::interactionId).toList()));
    order.set("openQuestions", MAPPER.valueToTree(input.openQuestions().stream().map(QuestionInput::questionId).toList()));
    order.set("transitions", MAPPER.valueToTree(input.flow().transitions()));
    return value;
  }

  private static boolean stableOwner(String owner, DraftInput base, DraftInput candidate) {
    if (base == null) return false;
    return Objects.equals(base.flow().interactions().stream().filter(value -> owner.equals(value.interactionId())).findFirst(),
        candidate.flow().interactions().stream().filter(value -> owner.equals(value.interactionId())).findFirst())
        && Objects.equals(base.capabilities().stream().filter(value -> owner.equals(value.interactionId())).findFirst(),
            candidate.capabilities().stream().filter(value -> owner.equals(value.interactionId())).findFirst())
        && base.facts().stream().filter(fact -> owns(fact, owner)).toList()
            .equals(candidate.facts().stream().filter(fact -> owns(fact, owner)).toList());
  }

  private static boolean owns(FactInput fact, String owner) {
    var owners = new java.util.LinkedHashSet<String>();
    addOwners(owners, fact);
    return owners.contains(owner);
  }

  private static boolean uniqueIds(List<String> ids) {
    return ids.stream().noneMatch(Objects::isNull) && new java.util.HashSet<>(ids).size() == ids.size();
  }

  private static void changedPaths(JsonNode before, JsonNode after, String path, List<String> changes) {
    if (before.equals(after)) return;
    if (before.isObject() || after.isObject()) {
      var keys = new java.util.TreeSet<String>();
      before.fieldNames().forEachRemaining(keys::add);
      after.fieldNames().forEachRemaining(keys::add);
      if (!keys.isEmpty()) {
        keys.forEach(key -> changedPaths(before.path(key), after.path(key), path + "/" + escape(key), changes));
        return;
      }
    } else if (before.isArray() || after.isArray()) {
      int size = Math.max(before.size(), after.size());
      if (size > 0) {
        for (int i = 0; i < size; i++) changedPaths(before.path(i), after.path(i), path + "/" + i, changes);
        return;
      }
    }
    changes.add(path.isEmpty() ? "/" : path);
  }

  private static void protectedChanges(JsonNode before, JsonNode after, String path, List<String> changes) {
    if (before.isNull() || before.isMissingNode()) return;
    if (before.isObject()) {
      before.fields().forEachRemaining(field -> protectedChanges(field.getValue(), after.path(field.getKey()),
          path + "/" + escape(field.getKey()), changes));
    } else if (!before.equals(after)) changes.add(path);
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static List<String> owners(String path, DraftInput base, DraftInput candidate) {
    var owners = new java.util.LinkedHashSet<String>();
    var pointer = JsonPointer.compile(path);
    String family = pointer.getMatchingProperty();
    String id = pointer.tail().getMatchingProperty();
    if (Set.of("interactions", "capabilities", "transitions").contains(family) && id != null) owners.add(id);
    for (var draft : new DraftInput[] {base, candidate}) {
      if (draft == null || draft.facts() == null) continue;
      String ownerFamily = "recordOrder".equals(family) ? pointer.tail().getMatchingProperty() : family;
      String ownerId = "recordOrder".equals(family) ? diagnosticTree(draft).at(path).asText(null) : id;
      if (ownerId == null) continue;
      if ("recordOrder".equals(family) && Set.of("interactions", "capabilities", "transitions").contains(ownerFamily)) owners.add(ownerId);
      if ("facts".equals(ownerFamily)) draft.facts().stream().filter(fact -> fact != null && Objects.equals(fact.sourceFactId(), ownerId))
          .forEach(fact -> addOwners(owners, fact));
      if ("openQuestions".equals(ownerFamily) && draft.openQuestions() != null) draft.openQuestions().stream()
          .filter(question -> question != null && Objects.equals(ownerId, question.questionId()))
          .filter(question -> question.interactionIds() != null).forEach(question -> owners.addAll(question.interactionIds()));
    }
    owners.remove(null);
    return List.copyOf(owners);
  }

  private static void addOwners(Set<String> owners, FactInput fact) {
    if (fact.interactionIds() != null) owners.addAll(fact.interactionIds());
    if (fact.elementIntent() != null) owners.add(fact.elementIntent().ownerInteractionId());
    if (fact.fieldMapping() != null) {
      owners.add(fact.fieldMapping().sourceInteractionId());
      owners.add(fact.fieldMapping().targetInteractionId());
    }
  }

  private static List<String> dependentOwners(List<String> initial, DraftInput base, DraftInput candidate) {
    var owners = new java.util.LinkedHashSet<>(initial);
    int previous;
    do {
      previous = owners.size();
      for (var draft : new DraftInput[] {base, candidate}) {
        if (draft == null) continue;
        if (draft.facts() != null) for (var fact : draft.facts()) {
          if (fact == null) continue;
          var related = new java.util.LinkedHashSet<String>();
          addOwners(related, fact);
          if (related.stream().anyMatch(owners::contains)) owners.addAll(related);
        }
        if (draft.flow() != null && draft.flow().transitions() != null) for (var edge : draft.flow().transitions()) {
          if (edge != null && (owners.contains(edge.sourceInteractionId()) || owners.contains(edge.targetInteractionId()))) {
            owners.add(edge.sourceInteractionId());
            owners.add(edge.targetInteractionId());
          }
        }
      }
      owners.remove(null);
    } while (previous != owners.size());
    return List.copyOf(owners);
  }

  private static void checkSchemas(DraftInput candidate, DeterministicElementSchemaService schemas, List<Reference> refs,
      List<PreservationFinding> findings, List<Issue> nativeFindings) {
    for (var fact : candidate.facts()) {
      var intent = fact.elementIntent();
      if (intent == null || intent.placement() == RequirementElementIntent.Placement.CHAIN) continue;
      String path = "/facts/" + escape(fact.sourceFactId()) + "/elementIntent";
      if (schemas == null || !schemas.hasElementSchema(intent.elementType())) {
        findings.add(finding(path, fact.interactionIds(), "native-schema", refs, "element-schema", PreservationOutcome.UNPROVEN,
            "The native element schema is unavailable for " + intent.elementType() + "."));
        continue;
      }
      var keys = schemas.allowedPatchPropertyKeys(intent.elementType());
      var used = new java.util.HashSet<String>();
      for (int index = 0; index < intent.requestedProperties().size(); index++) {
        var property = intent.requestedProperties().get(index);
        String propertyPath = path + "/requestedProperties/" + index;
        if (property == null || property.key() == null || !keys.contains(property.key()) || !used.add(property.key())) {
          String message = "The native schema requires a unique declared property key.";
          nativeFindings.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath, fact.sourceFactId(), message));
          findings.add(finding(propertyPath, fact.interactionIds(), "native-schema", refs, "element-schema",
              PreservationOutcome.VIOLATED, message));
        } else if (property.value() == null) {
          findings.add(finding(propertyPath, fact.interactionIds(), "native-schema", refs, "element-schema",
              PreservationOutcome.UNPROVEN, "The requested property value is missing."));
        } else {
          var error = schemas.validateCapturePropertyValue(intent.elementType(), property.key(), MAPPER.valueToTree(
              schemas.coercePatchPropertyValue(intent.elementType(), property.key(), property.value())));
          error.ifPresent(message -> nativeFindings.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath, fact.sourceFactId(), message)));
          findings.add(finding(propertyPath, fact.interactionIds(), "native-schema", refs, "element-schema",
              error.isPresent() ? PreservationOutcome.VIOLATED : PreservationOutcome.VERIFIED,
              error.orElse("The value satisfies its native property schema; the schema does not establish author intent.")));
        }
      }
    }
  }

  private static void checkPolicyContracts(String conversationId, RequirementDraftStore drafts, CompilationArtifacts artifacts,
      String compilationId, DraftInput candidate, List<Reference> refs, List<PreservationFinding> findings) {
    for (var answer : drafts.confirmedAnswers(conversationId)) {
      var evidence = new ArrayList<>(refs);
      evidence.add(answer.admittedRevision());
      evidence.addAll(answer.sourceInputs());
      boolean resolved = artifacts.get(compilationId, answer.admittedRevision()).isPresent()
          && !answer.sourceInputs().isEmpty()
          && answer.sourceInputs().stream().allMatch(reference -> artifacts.get(compilationId, reference).isPresent());
      var fact = candidate.facts().stream().filter(value -> answer.sourceFactId().equals(value.sourceFactId())).findFirst().orElse(null);
      String path = "/facts/" + escape(answer.sourceFactId()) + "/policyConflict";
      boolean matches = fact != null && answer.interactionIds().equals(fact.interactionIds())
          && answer.policy().equals(fact.policyConflict());
      findings.add(finding(path, answer.interactionIds(), "persisted-policy-contract", evidence, "policy-contract-retention",
          !resolved ? PreservationOutcome.UNPROVEN : matches ? PreservationOutcome.VERIFIED : PreservationOutcome.VIOLATED,
          !resolved ? "The policy answer's exact admitted revision or source input cannot be resolved."
              : matches ? "The persisted typed policy contract is retained. Its model-recorded choice does not prove the author's selected option."
                  : "The candidate changes or removes the persisted policy contract or its interaction owner."));
    }
  }

  private static void checkConsumers(DraftInput candidate, RequirementDraft baseDraft,
      List<org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint> bindings,
      Map<String, String> targets, NativeInputs inputs, List<Reference> refs, List<PreservationFinding> findings) {
    if (inputs == null || inputs.brief() == null || inputs.graph() == null) {
      findings.add(finding("/consumers", List.of(), "unreached-native-consumers", refs, "consumer-inputs",
          PreservationOutcome.UNPROVEN, "Candidate brief and graph inputs are missing for native coverage, requested values, quality, and security checks."
              + " Semantic coverage and mapping checks also need their native artifacts when those obligations change."));
      return;
    }
    String origin = "diagnostic-native-inputs:" + CaptureProposal.digest(inputs);
    if (inputs.graph().nodes() == null || inputs.graph().chain() == null || inputs.graph().edges() == null
        || inputs.graph().nodes().stream().anyMatch(node -> node == null || node.nodeId() == null || node.type() == null
            || node.properties() == null || node.properties().stream().anyMatch(property -> property == null || property.key() == null))) {
      findings.add(finding("/consumers/graph", List.of(), origin, refs, "consumer-inputs", PreservationOutcome.UNPROVEN,
          "The native graph input is incomplete; requested-value, quality, and security checks cannot run."));
      return;
    }
    try {
      var projected = RequirementCaptureProjection.toDraft(candidate, baseDraft, bindings, "diagnostic", "diagnostic", targets);
      var coverage = new RequirementBriefCoverageValidator().validate(projected, inputs.brief());
      findings.add(finding("/consumers/brief", List.of(), origin, refs, "RequirementBriefCoverageValidator",
          coverage.isPresent() ? PreservationOutcome.VIOLATED : PreservationOutcome.VERIFIED,
          coverage.orElse("Native brief coverage passes; this comparison does not establish changed source meaning.")));
      try {
        var applied = RequestedElementValues.apply(inputs.graph(), inputs.brief().facts());
        findings.add(finding("/consumers/requestedValues", List.of(), origin, refs, "RequestedElementValues",
            applied.equals(inputs.graph()) ? PreservationOutcome.VERIFIED : PreservationOutcome.VIOLATED,
            applied.equals(inputs.graph()) ? "The graph retains the brief's requested values."
                : "Applying the brief's requested values changes the supplied graph."));
      } catch (IllegalArgumentException invalidIntent) {
        findings.add(finding("/consumers/requestedValues", List.of(), origin, refs, "RequestedElementValues",
            PreservationOutcome.VIOLATED, invalidIntent.getMessage()));
      }
      var quality = CompilerQualityValidator.validateApprovedValues(inputs.graph(), inputs.brief());
      if (quality.isEmpty()) findings.add(finding("/consumers/quality", List.of(), origin, refs, "CompilerQualityValidator",
          PreservationOutcome.VERIFIED, "The native approved-value check passes."));
      quality.forEach(issue -> findings.add(finding(issue.fieldPointer() == null ? "/consumers/quality" : issue.fieldPointer(),
          issue.affectedNodeIds(), origin, refs, "CompilerQualityValidator",
          issue.severity() == ValidationSeverity.BLOCKER ? PreservationOutcome.VIOLATED : PreservationOutcome.UNPROVEN, issue.message())));
      var security = new CompilerSecurityValidator().validate(inputs.graph(), inputs.brief());
      if (security.issues().isEmpty()) findings.add(finding("/consumers/security", List.of(), origin, refs, "CompilerSecurityValidator",
          PreservationOutcome.VERIFIED, "The native security check passes."));
      security.issues().forEach(issue -> findings.add(finding(issue.fieldPointer() == null ? "/consumers/security" : issue.fieldPointer(),
          issue.affectedNodeIds(), origin, refs, "CompilerSecurityValidator",
          issue.severity() == ValidationSeverity.BLOCKER ? PreservationOutcome.VIOLATED : PreservationOutcome.UNPROVEN, issue.message())));
    } catch (RuntimeException incompleteInput) {
      findings.add(finding("/consumers", List.of(), origin, refs, "consumer-inputs", PreservationOutcome.UNPROVEN,
          "Native consumer coverage did not finish: " + incompleteInput.getClass().getSimpleName() + ": " + incompleteInput.getMessage()));
    }
  }

  static List<Issue> validate(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      Set<String> authorPaths, Map<String, Set<String>> mappingPaths, Predicate<FactInput> canAnswer) {
    return validate(base, candidate, protectedFacts, MAPPER.createObjectNode(), authorPaths, Set.of(), mappingPaths, canAnswer);
  }

  static List<Issue> validate(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      JsonNode protectedValues, Set<String> authorPaths, Set<String> repairPaths, Map<String, Set<String>> mappingPaths,
      Predicate<FactInput> canAnswer) {
    return validate(base, candidate, protectedFacts, protectedValues, authorPaths, repairPaths, mappingPaths,
        canAnswer, null);
  }

  static List<Issue> validate(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      JsonNode protectedValues, Set<String> authorPaths, Set<String> repairPaths, Map<String, Set<String>> mappingPaths,
      Predicate<FactInput> canAnswer, RequirementSemanticBaseline baseline) {
    return admissionDecision(base, candidate, protectedFacts, protectedValues, authorPaths, repairPaths, mappingPaths,
        canAnswer, baseline, null).issues();
  }

  /** Issues that still block admission, plus the repair strategy for that issue list. */
  record AdmissionDecision(List<Issue> issues, RequirementRepairPolicy.Strategy strategy) {}

  static AdmissionDecision admissionDecision(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      JsonNode protectedValues, Set<String> authorPaths, Set<String> repairPaths, Map<String, Set<String>> mappingPaths,
      Predicate<FactInput> canAnswer, RequirementSemanticBaseline baseline) {
    return admissionDecision(base, candidate, protectedFacts, protectedValues, authorPaths, repairPaths, mappingPaths,
        canAnswer, baseline, null);
  }

  static AdmissionDecision admissionDecision(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      JsonNode protectedValues, Set<String> authorPaths, Set<String> repairPaths, Map<String, Set<String>> mappingPaths,
      Predicate<FactInput> canAnswer, RequirementSemanticBaseline baseline, AuthorSourceEvidence.Packet authorSource) {
    var issues = new ArrayList<>(base == null ? RequirementCaptureEditor.initialize(candidate).issues()
        : RequirementCaptureEditor.validate(candidate));
    if (!issues.isEmpty()) {
      return decide(base, candidate, authorPaths, repairPaths, baseline, authorSource, issues);
    }
    if (base != null) {
      var allowed = new java.util.LinkedHashSet<>(authorPaths);
      if (base.settings().idsRequested() != null && candidate.openQuestions().stream()
          .anyMatch(question -> PipelineGates.IDS_PATH_CHOICE
              .equals(question.questionId()))) {
        issues.add(new Issue("CONFIRMED_ANSWER_CHANGED", "/draft/openQuestions", null,
            "Retain the confirmed IDS choice and do not reopen its question."));
      }
      repairPaths.stream().filter(path -> !path.contains("/policyConflict") && validPath(base, path))
          .forEach(allowed::add);
      candidate.facts().stream().filter(fact -> fact.policyConflict() != null
          && fact.policyConflict().decision() != null && canAnswer.test(fact))
          .forEach(fact -> allowed.add("/facts/" + fact.sourceFactId() + "/policyConflict"));
      var expected = amended(base, candidate, allowed);
      issues.addAll(RequirementCaptureEditor.validatePolicyResponses(base, candidate, canAnswer).stream()
          .filter(issue -> !supersedesAnswer(base, candidate, authorPaths, issue.entityId())).toList());
      for (var old : base.facts()) {
        var policy = old.policyConflict();
        if (policy == null || policy.decision() == null) {
          continue;
        }
        var replacement = candidate.facts().stream()
            .filter(fact -> old.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
        boolean amended = authorPaths.stream().anyMatch(path -> path.equals("/facts/" + old.sourceFactId())
            || path.equals("/facts/" + old.sourceFactId() + "/policyConflict")
            || path.equals("/facts/" + old.sourceFactId() + "/policyConflict/decision"));
        if (!amended && (replacement == null || !Objects.equals(policy, replacement.policyConflict())
            || candidate.openQuestions().stream().anyMatch(question -> policy.questionId().equals(question.questionId())))) {
          issues.add(new Issue("CONFIRMED_ANSWER_CHANGED", "/draft/facts/" + old.sourceFactId() + "/policyConflict",
              old.sourceFactId(), "Retain the confirmed answer and do not reopen its question."
                  + " Only an explicit source-linked author amendment can supersede it."));
        }
      }
      issues.addAll(RequirementCaptureEditor.validateFactPreservation(expected, candidate, mappingPaths));
      preserveValues(tree(expected).get("capabilities"), tree(candidate).get("capabilities"),
          "/capabilities", issues);
      preserveValues(tree(expected).get("settings"), tree(candidate).get("settings"), "/settings", issues);
      var partial = base.flow().interactions().stream().filter(interaction -> interaction.direction() == null
          || interaction.participant() == null || interaction.operation() == null)
          .map(InteractionInput::interactionId).collect(java.util.stream.Collectors.toSet());
      var interactions = tree(expected).withObject("/interactions");
      partial.stream().filter(id -> candidate.flow().interactions().stream()
          .noneMatch(interaction -> id.equals(interaction.interactionId()))).forEach(interactions::remove);
      preserveValues(interactions, tree(candidate).get("interactions"), "/interactions", issues);
      preserveTransitions(tree(new DraftInput(expected.flow(), expected.facts(), expected.capabilities(),
          expected.openQuestions(), expected.settings())).get("transitions"), tree(candidate).get("transitions"),
          partial, candidate, issues);
    }
    if (!issues.isEmpty() && base != null) issues.addAll(RequirementCaptureEditor.capabilityAnchorConflicts(base));
    return decide(base, candidate, authorPaths, repairPaths, baseline, authorSource, issues);
  }

  private static AdmissionDecision decide(DraftInput base, DraftInput candidate, Set<String> authorPaths,
      Set<String> repairPaths, RequirementSemanticBaseline baseline, AuthorSourceEvidence.Packet authorSource,
      List<Issue> issues) {
    var context = new RequirementRepairPolicy.RequirementRepairContext(
        base, baseline, authorPaths, repairPaths, RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, authorSource);
    var decision = RequirementRepairPolicy.evaluate(context, candidate, findings(issues));
    var remaining = new ArrayList<>(issues);
    var selected = new ArrayList<Issue>();
    for (var finding : decision.findings()) {
      var match = remaining.stream().filter(issue -> Objects.equals(issue.code(), finding.code())
          && Objects.equals(issue.path(), finding.path())
          && Objects.equals(issue.entityId(), finding.entityId())).findFirst().orElse(null);
      if (match != null) {
        remaining.remove(match);
        selected.add(match);
      } else {
        selected.add(new Issue(finding.code(), finding.path(), finding.entityId(), finding.message(),
            finding.relatedEntityIds()));
      }
    }
    return new AdmissionDecision(List.copyOf(selected), decision.strategy());
  }

  private static List<RequirementFinding> findings(List<Issue> issues) {
    return issues.stream().map(issue -> new RequirementFinding(
        issue.code(), issue.path(), issue.entityId(), List.of(), issue.relatedEntityIds(), List.of(),
        issue.message())).toList();
  }

  private static boolean supersedesAnswer(DraftInput base, DraftInput candidate, Set<String> paths, String factId) {
    var original = base.facts().stream().filter(fact -> Objects.equals(factId, fact.sourceFactId()))
        .findFirst().orElse(null);
    var replacement = candidate.facts().stream().filter(fact -> Objects.equals(factId, fact.sourceFactId()))
        .findFirst().orElse(null);
    return original != null && replacement != null && original.policyConflict() != null
        && original.policyConflict().decision() != null && replacement.policyConflict() != null
        && Objects.equals(original.policyConflict().unresolved(), replacement.policyConflict().unresolved())
        && paths.contains("/facts/" + factId + "/policyConflict/decision");
  }

  static String canonicalPath(String path) {
    return path != null && (path.startsWith("/flow/interactions/") || path.startsWith("/flow/transitions/"))
        ? path.substring("/flow".length()) : path;
  }

  static boolean validPath(DraftInput base, String path) {
    if (base == null || path == null || !path.startsWith("/") || path.contains("~")) {
      return false;
    }
    try {
      var pointer = JsonPointer.compile(path);
      return Set.of("facts", "capabilities", "settings", "interactions", "transitions").contains(pointer.getMatchingProperty())
          && !pointer.tail().matches()
          && !path.endsWith("/sourceFactId") && !path.endsWith("/interactionId")
          && tree(base).at(pointer.head()).isObject()
          && !tree(base).at(pointer).isMissingNode();
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static DraftInput amended(DraftInput base, DraftInput candidate, Set<String> paths) {
    var expected = tree(base);
    var proposed = tree(candidate);
    replacePaths(expected, proposed, paths);
    return new DraftInput(new FlowInput(values(expected.get("interactions"), InteractionInput.class),
        transitions(expected.get("transitions"))), values(expected.get("facts"), FactInput.class),
        values(expected.get("capabilities"), CapabilityInput.class), base.openQuestions(),
        MAPPER.convertValue(expected.get("settings"), DraftSettings.class));
  }

  private static void replacePaths(ObjectNode expected, ObjectNode proposed, Set<String> paths) {
    for (var path : paths) {
      var pointer = JsonPointer.compile(path);
      if (expected.at(pointer.head()) instanceof ObjectNode parent) {
        var value = proposed.at(pointer);
        if (value.isMissingNode()) {
          parent.remove(pointer.last().getMatchingProperty());
        } else {
          parent.set(pointer.last().getMatchingProperty(), value);
        }
      }
    }
  }

  private static ObjectNode tree(DraftInput input) {
    var tree = MAPPER.createObjectNode();
    var facts = tree.putObject("facts");
    if (input.facts() != null) input.facts().stream().filter(Objects::nonNull).forEach(fact -> facts.set(fact.sourceFactId(), MAPPER.valueToTree(fact)));
    var capabilities = tree.putObject("capabilities");
    if (input.capabilities() != null) input.capabilities().stream().filter(capability -> capability != null && capability.interactionId() != null).forEach(capability -> capabilities.set(capability.interactionId(), MAPPER.valueToTree(capability)));
    tree.set("settings", MAPPER.valueToTree(input.settings()));
    var interactions = tree.putObject("interactions");
    if (input.flow() != null && input.flow().interactions() != null) input.flow().interactions().stream()
        .filter(interaction -> interaction != null && interaction.interactionId() != null)
        .forEach(interaction -> interactions.set(interaction.interactionId(), MAPPER.valueToTree(interaction)));
    var transitions = tree.putObject("transitions");
    if (input.flow() != null && input.flow().transitions() != null) input.flow().transitions().stream()
        .filter(edge -> edge != null && edge.sourceInteractionId() != null && edge.targetInteractionId() != null)
        .forEach(edge -> transitions.withArray(edge.sourceInteractionId()).add(edge.targetInteractionId()));
    return tree;
  }

  private static <T> List<T> values(JsonNode object, Class<T> type) {
    var values = new ArrayList<T>();
    object.elements().forEachRemaining(value -> values.add(MAPPER.convertValue(value, type)));
    return List.copyOf(values);
  }

  private static void preserveValues(JsonNode before, JsonNode after, String path, List<Issue> issues) {
    if (before.isNull() || before.isMissingNode()) {
      return;
    }
    if (before.isObject() && after.isObject()) {
      before.fields().forEachRemaining(field -> preserveValues(field.getValue(), after.path(field.getKey()),
          path + "/" + field.getKey(), issues));
    } else if (!before.equals(after)) {
      issues.add(new Issue("AUTHOR_AMENDMENT_REQUIRED", path, null,
          "Retain this requirement value until the change has independent preservation evidence: " + before
              + ". A saved extraction alone does not prove author intent. Use the native correction contract;"
              + " otherwise replacement requires an amendment citing the exact fresh author source and quote."));
    }
  }

  static List<FactInput> accumulate(List<FactInput> protectedFacts, List<FactInput> candidate) {
    var accumulated = new ArrayList<>(protectedFacts);
    for (var fact : candidate) {
      int index = -1;
      for (int i = 0; i < accumulated.size(); i++) {
        var old = accumulated.get(i);
        if (Objects.equals(old.sourceFactId(), fact.sourceFactId())
            || !RequirementCaptureEditor.validFactId(old.sourceFactId()) && Objects.equals(old.text(), fact.text())
            || sameFactContent(old, fact)) {
          index = i;
          break;
        }
      }
      if (index < 0) {
        accumulated.add(fact);
      } else {
        var old = accumulated.get(index);
        if ((old.text() == null || Objects.equals(old.text(), fact.text()))
            && (old.kind() == null || old.kind() == fact.kind()) && (old.polarity() == null || old.polarity() == fact.polarity())) {
          var originalIntent = old.elementIntent();
          var newIntent = fact.elementIntent();
          var intent = originalIntent == null ? newIntent : originalIntent;
          if (originalIntent != null && newIntent != null && Objects.equals(originalIntent.elementRef(), newIntent.elementRef())
              && Objects.equals(originalIntent.elementType(), newIntent.elementType())
              && originalIntent.placement() == newIntent.placement()) {
            var properties = new LinkedHashMap<String, org.qubership.integration.platform.ai.plan.model.PlanProperty>();
            originalIntent.requestedProperties().forEach(property -> properties.put(property.key(), property));
            newIntent.requestedProperties().forEach(property -> properties.putIfAbsent(property.key(), property));
            var values = new java.util.LinkedHashSet<>(originalIntent.exactValues());
            values.addAll(newIntent.exactValues());
            intent = new RequirementElementIntent(originalIntent.elementRef(), originalIntent.elementType(),
                originalIntent.placement(), first(originalIntent.ownerInteractionId(), newIntent.ownerInteractionId()),
                first(originalIntent.successorElementRef(), newIntent.successorElementRef()),
                first(originalIntent.predecessorElementRef(), newIntent.predecessorElementRef()),
                first(originalIntent.parentElementRef(), newIntent.parentElementRef()),
                first(originalIntent.requestedName(), newIntent.requestedName()), List.copyOf(properties.values()),
                List.copyOf(values), originalIntent.bodyBinding() == null
                    ? newIntent.bodyBinding() : originalIntent.bodyBinding());
          }
          var interactions = new java.util.LinkedHashSet<>(old.interactionIds());
          interactions.addAll(fact.interactionIds());
          accumulated.set(index, new FactInput(RequirementCaptureEditor.validFactId(old.sourceFactId())
              ? old.sourceFactId() : fact.sourceFactId(), List.copyOf(interactions), old.kind() == null ? fact.kind() : old.kind(),
              old.polarity() == null ? fact.polarity() : old.polarity(), first(old.text(), fact.text()), mergeMapping(old.fieldMapping(), fact.fieldMapping()),
              old.policyConflict() == null ? fact.policyConflict() : old.policyConflict(), intent,
              old.evidence() != null ? old.evidence() : fact.evidence()));
        }
      }
    }
    return List.copyOf(accumulated);
  }

  private static boolean sameFactContent(FactInput left, FactInput right) {
    return left.text() != null && !left.text().isBlank() && left.kind() != null && left.polarity() != null
        && Objects.equals(left.text(), right.text()) && left.kind() == right.kind()
        && left.polarity() == right.polarity() && Objects.equals(left.interactionIds(), right.interactionIds())
        && Objects.equals(left.fieldMapping(), right.fieldMapping())
        && Objects.equals(left.policyConflict(), right.policyConflict())
        && (left.elementIntent() == null && right.elementIntent() == null
            || left.elementIntent() != null && right.elementIntent() != null
                && Objects.equals(left.elementIntent().elementRef(), right.elementIntent().elementRef())
                && Objects.equals(left.elementIntent().elementType(), right.elementIntent().elementType())
                && Objects.equals(left.elementIntent().ownerInteractionId(), right.elementIntent().ownerInteractionId())
                && left.elementIntent().placement() == right.elementIntent().placement());
  }

  static ObjectNode valuesToPreserve(DraftInput input, List<Issue> issues) {
    var values = tree(input);
    values.remove("facts");
    var raw = MAPPER.<JsonNode>valueToTree(input);
    for (var issue : issues) {
      var path = issue.path();
      if (path.startsWith("/draft/")) path = path.substring(6);
      if (path.startsWith("/changes/")) path = path.substring(8);
      if (path.startsWith("/flow/")) path = path.substring(5);
      if (path.startsWith("/interactions/") || path.startsWith("/capabilities/")) {
        var pointer = JsonPointer.compile(path);
        var family = pointer.getMatchingProperty();
        var key = pointer.tail().getMatchingProperty();
        var array = family.equals("interactions") ? raw.path("flow").path(family) : raw.path(family);
        if (pointer.tail().getMatchingIndex() >= 0 && array.path(pointer.tail().getMatchingIndex()).isObject()) {
          key = array.path(pointer.tail().getMatchingIndex()).path("interactionId").asText();
        }
        var entity = JsonPointer.compile("/" + family).appendProperty(key);
        path = entity.append(pointer.tail().tail()).toString();
        if (path.endsWith("/interactionId")) path = entity.toString();
      }
      if (path.startsWith("/transitions")) {
        var pointer = JsonPointer.compile(path);
        var edge = input.flow() == null || input.flow().transitions() == null ? null
            : pointer.tail().getMatchingIndex() >= 0 && pointer.tail().getMatchingIndex() < input.flow().transitions().size()
                ? input.flow().transitions().get(pointer.tail().getMatchingIndex()) : null;
        if (edge == null) {
          if (pointer.tail().matches()) values.remove("transitions");
          else if (values.get("transitions") instanceof ObjectNode outgoing) outgoing.remove(pointer.tail().getMatchingProperty());
        } else if (values.path("transitions").path(edge.sourceInteractionId()).isArray()) {
          var remaining = MAPPER.createArrayNode();
          boolean removed = false;
          for (var target : values.path("transitions").path(edge.sourceInteractionId())) {
            if (!removed && target.asText().equals(edge.targetInteractionId())) removed = true;
            else remaining.add(target);
          }
          ((ObjectNode) values.get("transitions")).set(edge.sourceInteractionId(), remaining);
        }
      } else {
        var pointer = JsonPointer.compile(path);
        if (values.at(pointer.head()) instanceof ObjectNode parent) parent.putNull(pointer.last().getMatchingProperty());
      }
    }
    return pendingValues(values);
  }

  static ObjectNode pendingValues(JsonNode original) {
    var values = original instanceof ObjectNode object ? object.deepCopy() : MAPPER.createObjectNode();
    projectRecords(values.path("interactions"), InteractionInput.class);
    projectRecords(values.path("capabilities"), CapabilityInput.class);
    discardInvalidInteractionReferences(values);
    discardEmptyValues(values);
    return values;
  }

  private static void projectRecords(JsonNode family, Class<?> record) {
    family.fields().forEachRemaining(entry -> {
      if (!(entry.getValue() instanceof ObjectNode value)) return;
      // A rejected required discriminator cannot validate the fields that depend on it.
      for (var field : record.getRecordComponents()) {
        if (!field.isAnnotationPresent(NullableCaptureValue.class)
            && value.has(field.getName()) && value.get(field.getName()).isNull()) {
          value.removeAll();
          return;
        }
      }
      // The enclosing map already addresses the record; its identity is not an independent obligation.
      value.remove("interactionId");
    });
  }

  private static void discardEmptyValues(ObjectNode values) {
    var fields = values.fields();
    while (fields.hasNext()) {
      var value = fields.next().getValue();
      if (value instanceof ObjectNode object) discardEmptyValues(object);
      if (value.isObject() && value.isEmpty()) fields.remove();
    }
    if (java.util.stream.StreamSupport.stream(values.spliterator(), false).allMatch(JsonNode::isNull)) {
      values.removeAll();
    }
  }

  private static void discardInvalidInteractionReferences(ObjectNode values) {
    var invalid = new java.util.HashSet<String>();
    values.path("interactions").fields().forEachRemaining(field -> {
      if (field.getValue().isNull() || field.getValue().isObject() && field.getValue().isEmpty()) {
        invalid.add(field.getKey());
      }
    });
    if (invalid.isEmpty()) return;
    if (values.get("capabilities") instanceof ObjectNode capabilities) invalid.forEach(capabilities::remove);
    if (values.get("transitions") instanceof ObjectNode outgoing) {
      invalid.forEach(outgoing::remove);
      var edges = outgoing.fields();
      while (edges.hasNext()) {
        var edge = edges.next();
        var targets = MAPPER.createArrayNode();
        edge.getValue().forEach(target -> {
          if (!invalid.contains(target.asText())) targets.add(target);
        });
        if (targets.isEmpty()) edges.remove();
        else outgoing.set(edge.getKey(), targets);
      }
    }
  }

  static ObjectNode accumulateValues(JsonNode original, ObjectNode candidate) {
    var accumulated = pendingValues(original);
    pendingValues(candidate).fields().forEachRemaining(field -> {
      var old = accumulated.path(field.getKey());
      var value = field.getValue();
      if (old.isNull() || old.isMissingNode()) accumulated.set(field.getKey(), value);
      else if (old.isObject() && value.isObject()) accumulated.set(field.getKey(), accumulateValues(old, (ObjectNode) value));
      else if (old.isArray() && value.isArray()) {
        var joined = ((com.fasterxml.jackson.databind.node.ArrayNode) old).deepCopy();
        value.forEach(item -> { if (!contains(joined, item)) joined.add(item); });
        accumulated.set(field.getKey(), joined);
      }
    });
    return pendingValues(accumulated);
  }

  private static boolean contains(JsonNode array, JsonNode value) {
    for (var item : array) if (item.equals(value)) return true;
    return false;
  }

  private static List<TransitionInput> transitions(JsonNode values) {
    var transitions = new ArrayList<TransitionInput>();
    values.fields().forEachRemaining(field -> field.getValue().forEach(target ->
        transitions.add(new TransitionInput(field.getKey(), target.asText()))));
    return transitions;
  }

  /**
   * Puts back a fact or transition the candidate dropped when the accepted draft already holds that
   * value. Facts and transitions come only from that draft. A fact is restored only when the
   * candidate already applies the named HTTP mode correction. Other requirement changes stay rejected.
   */
  static DraftInput restoreProtectedSnapshot(DraftInput base, DraftInput candidate,
      JsonNode protectedValues, List<CatalogBindingHint> bindings, List<Issue> issues) {
    if (candidate == null || candidate.flow() == null || issues == null || issues.isEmpty()) return null;
    boolean restorable = issues.stream().allMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
        || "AUTHOR_AMENDMENT_REQUIRED".equals(issue.code()) && issue.path() != null
            && issue.path().startsWith("/transitions/"));
    if (!restorable) return null;
    boolean factIssue = issues.stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code()));
    if (factIssue && !namedModeRepair(base, candidate, protectedValues, bindings)) return null;
    var facts = candidate.facts() == null ? List.<FactInput>of() : candidate.facts();
    if (factIssue) facts = restoredFacts(base, candidate);
    var transitions = new LinkedHashMap<TransitionInput, TransitionInput>();
    if (candidate.flow().transitions() != null) {
      for (var edge : candidate.flow().transitions()) if (edge != null) transitions.put(edge, edge);
    }
    if (issues.stream().anyMatch(issue -> "AUTHOR_AMENDMENT_REQUIRED".equals(issue.code()))) {
      if (base != null && base.flow() != null && base.flow().transitions() != null) {
        for (var edge : base.flow().transitions()) if (edge != null) transitions.putIfAbsent(edge, edge);
      }
    }
    var restored = new DraftInput(new FlowInput(candidate.flow().interactions(), List.copyOf(transitions.values())),
        facts, candidate.capabilities(), candidate.openQuestions(), candidate.settings());
    return restored.equals(candidate) ? null : restored;
  }

  private static List<FactInput> restoredFacts(DraftInput base, DraftInput candidate) {
    var snapshot = new LinkedHashMap<String, FactInput>();
    if (base != null && base.facts() != null) {
      for (var fact : base.facts()) if (fact != null && fact.sourceFactId() != null) snapshot.putIfAbsent(fact.sourceFactId(), fact);
    }
    var chosen = new ArrayList<FactInput>();
    var used = new java.util.HashSet<String>();
    for (var original : snapshot.values()) {
      FactInput updated = candidate.facts() == null ? null : candidate.facts().stream()
          .filter(fact -> original.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
      chosen.add(updated == null ? original : updated);
      used.add(original.sourceFactId());
    }
    if (candidate.facts() != null) {
      for (var fact : candidate.facts()) {
        if (fact != null && fact.sourceFactId() != null && used.add(fact.sourceFactId())) chosen.add(fact);
      }
    }
    return List.copyOf(chosen);
  }

  private static boolean namedModeRepair(DraftInput base, DraftInput candidate,
      JsonNode protectedValues, List<CatalogBindingHint> bindings) {
    if (candidate.flow() == null || candidate.flow().interactions() == null) return false;
    var retainedModes = protectedValues == null ? null : protectedValues.path("interactions");
    var interactions = candidate.flow().interactions().stream().map(value -> {
      var stored = retainedModes == null ? null : retainedModes.path(value.interactionId()).path("failureMode");
      boolean named = value.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE
          && (stored == null || stored.isMissingNode() || stored.isNull()
              || ServiceCallFailureMode.PROPAGATE.name().equals(stored.asText()));
      return named ? new InteractionInput(value.interactionId(), value.direction(), value.participant(), value.operation(),
          value.description(), ServiceCallFailureMode.PROPAGATE, value.retryPolicy()) : value;
    }).toList();
    var probe = new DraftInput(new FlowInput(interactions, candidate.flow().transitions()),
        restoredFacts(base, candidate), candidate.capabilities(), candidate.openQuestions(),
        candidate.settings());
    var findings = RequirementFailureConsistency.issues(probe, bindings == null ? List.of() : bindings);
    return !findings.isEmpty() && findings.stream().allMatch(finding -> candidate.flow().interactions().stream()
        .anyMatch(value -> finding.entityId().equals(value.interactionId())
            && value.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE));
  }

  private static void preserveTransitions(JsonNode before, JsonNode after, Set<String> removable,
      DraftInput candidate, List<Issue> issues) {
    before.fields().forEachRemaining(field -> field.getValue().forEach(target -> {
      if (!contains(after.path(field.getKey()), target)
          && !(removable.contains(field.getKey()) && candidate.flow().interactions().stream()
              .noneMatch(i -> i.interactionId().equals(field.getKey())))
          && !(removable.contains(target.asText()) && candidate.flow().interactions().stream()
              .noneMatch(i -> i.interactionId().equals(target.asText())))) {
        issues.add(new Issue("AUTHOR_AMENDMENT_REQUIRED", "/transitions/" + field.getKey(), field.getKey(),
            "Preserve this accepted transition or cite a source-linked amendment for its outgoing transitions."));
      }
    }));
  }

  private static FieldMappingInput mergeMapping(FieldMappingInput old, FieldMappingInput candidate) {
    if (old == null) return candidate;
    if (candidate == null || old.sourcePath() != null && old.targetPath() != null) return old;
    return new FieldMappingInput(first(old.sourceInteractionId(), candidate.sourceInteractionId()),
        first(old.sourcePath(), candidate.sourcePath()), first(old.targetInteractionId(), candidate.targetInteractionId()),
        first(old.targetPath(), candidate.targetPath()), first(old.expression(), candidate.expression()));
  }

  private static String first(String original, String candidate) {
    return original == null ? candidate : original;
  }

  /** Pinned-question disposition, or the issues that keep the candidate out. */
  record ClarificationVerdict(List<Issue> issues, ClarificationTransition.Disposition disposition) {}

  static ClarificationVerdict clarify(RequirementDraftStore.ClarificationAdmissionContext context,
      RequirementDraft candidate, Predicate<String> validCandidate, Function<String, Set<String>> defaultsFor) {
    if (context == null || candidate == null || candidate.authoredDraft() == null
        || context.pinnedQuestion() == null || context.offer() == null) {
      return new ClarificationVerdict(List.of(), null);
    }
    DraftInput base = context.pinnedBase() == null ? null : context.pinnedBase().authoredDraft();
    DraftInput proposed = candidate.authoredDraft();
    QuestionInput pinned = context.pinnedQuestion();
    var issues = new ArrayList<Issue>();
    issues.addAll(reorderedQuestions(base, proposed, pinned.questionId()));
    if (!issues.isEmpty()) {
      return new ClarificationVerdict(List.copyOf(issues), null);
    }
    boolean closed = proposed.openQuestions().stream()
        .noneMatch(question -> question != null && pinned.questionId().equals(question.questionId()));
    var after = new RequirementClarificationCoordinator().targetProjection(candidate, pinned);
    boolean targetChanged = context.targetProjection() != null && !context.targetProjection().equals(after);
    var removals = unappliedRemovals(base, candidate, defaultsFor);
    boolean pinnedUnapplied = removals.stream().anyMatch(issue -> pinned.questionId().equals(issue.entityId()));
    if (closed && !pinnedUnapplied) {
      var others = removals.stream().filter(issue -> !pinned.questionId().equals(issue.entityId())).toList();
      if (!others.isEmpty()) {
        return new ClarificationVerdict(List.copyOf(others), null);
      }
      return new ClarificationVerdict(List.of(), ClarificationTransition.Disposition.ANSWER);
    }
    if (closed) {
      return new ClarificationVerdict(List.copyOf(removals), null);
    }
    if (!closed && targetChanged) {
      issues.add(new Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions/" + pinned.questionId(),
          pinned.questionId(), "The pinned target changed. Remove its question."));
      issues.addAll(removals);
      return new ClarificationVerdict(List.copyOf(issues), null);
    }
    var input = context.disposition();
    if (input != null && input.disposition() != null
        && input.disposition() != ClarificationTransition.Disposition.ANSWER
        && !context.focusedAnswerRepair()) {
      issues.add(new Issue("DISPOSITION_NOT_AUTHORIZED", "/changes/clarificationDisposition", pinned.questionId(),
          "AMBIGUOUS and NON_ANSWER are accepted only from focused answer repair."));
      return new ClarificationVerdict(List.copyOf(issues), null);
    }
    if (input != null && input.questionId() != null && !input.questionId().equals(pinned.questionId())
        && input.disposition() != null && input.disposition() != ClarificationTransition.Disposition.ANSWER) {
      issues.add(new Issue("AMBIGUOUS_DISPOSITION_INVALID", "/changes/clarificationDisposition", pinned.questionId(),
          "The disposition must name the pinned question."));
      return new ClarificationVerdict(List.copyOf(issues), null);
    }
    if (input != null && input.disposition() == ClarificationTransition.Disposition.AMBIGUOUS
        && context.focusedAnswerRepair()) {
      var findings = new ArrayList<>(AuthorQuestionAdmission.ambiguityFindings(
          input, context.answerTurnId(), context.answerText(), validCandidate));
      QuestionInput replacement = proposed.openQuestions().stream()
          .filter(question -> pinned.questionId().equals(question.questionId())).findFirst().orElse(null);
      if (replacement == null || Objects.equals(pinned.text(), replacement.text())) {
        findings.add("AMBIGUOUS requires a narrower replacement question.");
      }
      if (replacement != null && replacement.evidence() != null && replacement.evidence().ambiguity() != null) {
        AuthorQuestionEvidence.AmbiguityEvidence ambiguity = replacement.evidence().ambiguity();
        if (!Objects.equals(input.exactQuote(), ambiguity.exactQuote())
            || !Objects.equals(input.candidateValues(), ambiguity.candidateValues())) {
          findings.add("Typed ambiguity evidence must match the focused disposition.");
        }
      }
      if (!reviewed(replacement, context.readyInputIds())) {
        findings.add("Reconcile the pinned question evidence so reviewedInputIds includes the answer turn.");
      }
      if (!findings.isEmpty()) {
        issues.add(new Issue("AMBIGUOUS_DISPOSITION_INVALID", "/changes/clarificationDisposition",
            pinned.questionId(), String.join(" ", findings)));
        return new ClarificationVerdict(List.copyOf(issues), null);
      }
      if (!removals.isEmpty()) {
        return new ClarificationVerdict(List.copyOf(removals), null);
      }
      return new ClarificationVerdict(List.of(), ClarificationTransition.Disposition.AMBIGUOUS);
    }
    if (input != null && input.disposition() == ClarificationTransition.Disposition.NON_ANSWER
        && context.focusedAnswerRepair()) {
      QuestionInput current = proposed.openQuestions().stream()
          .filter(question -> pinned.questionId().equals(question.questionId())).findFirst().orElse(null);
      if (!reviewed(current, context.readyInputIds())
          || context.answerTurnId() == null
          || current.evidence() == null
          || !current.evidence().reviewedInputIds().contains(context.answerTurnId())) {
        issues.add(new Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions/" + pinned.questionId(),
            pinned.questionId(),
            "Reconcile the pinned question evidence so reviewedInputIds includes the answer turn."));
        return new ClarificationVerdict(List.copyOf(issues), null);
      }
      if (!removals.isEmpty()) {
        return new ClarificationVerdict(List.copyOf(removals), null);
      }
      return new ClarificationVerdict(List.of(), ClarificationTransition.Disposition.NON_ANSWER);
    }
    issues.add(new Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions/" + pinned.questionId(),
        pinned.questionId(),
        "The admitted answer has not changed the pinned question. Apply it to the pinned target,"
            + " or classify it from focused answer repair."));
    issues.addAll(removals);
    return new ClarificationVerdict(List.copyOf(issues), null);
  }

  private static boolean reviewed(QuestionInput question, Set<String> readyInputIds) {
    if (question == null || question.evidence() == null) {
      return false;
    }
    var recorded = new java.util.HashSet<>(question.evidence().reviewedInputIds());
    if (readyInputIds == null || readyInputIds.isEmpty()) {
      return !recorded.isEmpty();
    }
    return readyInputIds.equals(recorded);
  }

  private static List<Issue> reorderedQuestions(DraftInput base, DraftInput proposed, String pinnedId) {
    if (base == null || proposed == null || base.openQuestions() == null || proposed.openQuestions() == null
        || pinnedId == null) {
      return List.of();
    }
    var baseIds = base.openQuestions().stream().filter(Objects::nonNull).map(QuestionInput::questionId).toList();
    var candidateIds = proposed.openQuestions().stream().filter(Objects::nonNull).map(QuestionInput::questionId).toList();
    int pinnedAt = candidateIds.indexOf(pinnedId);
    int basePinned = baseIds.indexOf(pinnedId);
    if (pinnedAt < 0 || basePinned < 0) {
      return List.of();
    }
    var issues = new ArrayList<Issue>();
    for (int index = 0; index < candidateIds.size(); index++) {
      String id = candidateIds.get(index);
      int baseIndex = baseIds.indexOf(id);
      if (baseIndex > basePinned && index < pinnedAt) {
        issues.add(new Issue("PINNED_QUESTION_REORDERED", "/draft/openQuestions", id,
            "Keep untouched queued questions behind the pinned question."));
      }
    }
    return issues;
  }

  static List<Issue> unappliedRemovals(DraftInput base, RequirementDraft candidate,
      Function<String, Set<String>> defaultsFor) {
    if (base == null || base.openQuestions() == null || candidate == null || candidate.authoredDraft() == null) {
      return List.of();
    }
    DraftInput draft = candidate.authoredDraft();
    var facts = RequirementCaptureProjection.facts(draft);
    var issues = new ArrayList<Issue>();
    for (var question : base.openQuestions()) {
      if (question == null) {
        continue;
      }
      var evidence = question.evidence();
      if (evidence == null || draft.openQuestions().stream()
          .anyMatch(open -> open != null && open.questionId().equals(question.questionId()))) {
        continue;
      }
      boolean resolverWork = RequirementCaptureProjection.pending(draft, candidate.catalogBindings(),
          RequirementCaptureProjection.resolvedTargets(candidate)).stream().anyMatch(work ->
              evidence.interactionId().equals(work.interactionId()) && work.code().endsWith("LOOKUP_REQUIRED"));
      if (resolverWork) {
        continue;
      }
      var values = facts.stream().filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
          .filter(fact -> fact.targetsInteraction(evidence.interactionId())
              || fact.elementIntent() != null && evidence.interactionId().equals(fact.elementIntent().elementRef()))
          .flatMap(fact -> AuthorQuestionAdmission.suppliedValues(fact, evidence.propertyKey()).stream()).toList();
      boolean resolvedPolicy = evidence.kind() != AuthorQuestionEvidence.Kind.POLICY_CHOICE
          || facts.stream().anyMatch(fact -> fact.targetsInteraction(evidence.interactionId())
              && fact.policyConflict() != null
              && evidence.propertyKey().equals(fact.policyConflict().propertyKey())
              && fact.policyConflict().decision() != null);
      var defaults = defaultsFor == null ? Set.<String>of() : defaultsFor.apply(evidence.interactionId());
      if ((values.isEmpty() && !defaults.contains(evidence.propertyKey())) || !resolvedPolicy) {
        issues.add(new Issue("UNAPPLIED_AUTHOR_ANSWER", "/draft/openQuestions/" + question.questionId(),
            question.questionId(), "Removing a question is insufficient: apply the saved answer to "
                + evidence.interactionId() + "." + evidence.propertyKey() + " before continuing."));
      }
    }
    return issues;
  }
}
