package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;

/** Restores a rejected internal reference only when immutable identity history proves its target. */
final class RejectedReferenceRepair {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> REFERENCES = List.of("successorElementRef", "predecessorElementRef");

  record Attempt(long sequence, DraftInput input, List<RequirementCaptureResult.CaptureIssue> issues) {}
  record Repair(List<FactInput> facts, Set<Long> evidenceSequences) {}

  private RejectedReferenceRepair() {}

  static Repair repair(DraftInput base, List<FactInput> protectedFacts, List<Attempt> history) {
    if (history.isEmpty()) return new Repair(protectedFacts, Set.of());
    var proof = history.getLast();
    if (proof.issues().stream().noneMatch(issue -> "UNKNOWN_ELEMENT_REFERENCE".equals(issue.code()))) {
      return new Repair(protectedFacts, Set.of());
    }
    var projection = withFacts(proof.input(), protectedFacts);
    var nativeIssues = RequirementCaptureEditor.validate(proof.input());
    var protectedIssues = RequirementCaptureEditor.validate(projection);
    var repaired = new ArrayList<>(protectedFacts);
    var evidence = new LinkedHashSet<Long>();
    for (var issue : proof.issues()) {
      if (!"UNKNOWN_ELEMENT_REFERENCE".equals(issue.code())
          || !RequirementCaptureEditor.validFactId(issue.entityId())) continue;
      String prefix = "/draft/facts/" + issue.entityId() + "/elementIntent/";
      if (!issue.path().startsWith(prefix)) continue;
      String field = issue.path().substring(prefix.length());
      if (!REFERENCES.contains(field)
          || nativeIssues.stream().noneMatch(value -> value.code().equals(issue.code()) && value.path().equals(issue.path()))
          || protectedIssues.stream().noneMatch(value -> value.code().equals(issue.code()) && value.path().equals(issue.path()))) continue;
      var owner = unique(protectedFacts, issue.entityId());
      if (owner == null || owner.elementIntent() == null
          || owner.polarity() != RequirementCaptureInput.Polarity.POSITIVE
          || !owner.equals(unique(proof.input().facts(), issue.entityId()))
          || base != null && unique(base.facts(), issue.entityId()) != null) continue;
      String invalid = reference(owner, field);
      if (invalid == null) continue;
      var candidates = new ArrayList<RepairTarget>();
      for (var target : protectedFacts) {
        var intent = target.elementIntent();
        if (intent == null || target.polarity() != RequirementCaptureInput.Polarity.POSITIVE
            || !RequirementCaptureEditor.validFactId(target.sourceFactId())
            || intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE
            || !Objects.equals(intent.ownerInteractionId(), owner.elementIntent().ownerInteractionId())
            || intent.ownerInteractionId() == null || target.sourceFactId().equals(owner.sourceFactId())
            || unique(protectedFacts, target.sourceFactId()) == null
            || protectedFacts.stream().filter(value -> value.elementIntent() != null
                && Objects.equals(value.elementIntent().elementRef(), intent.elementRef())).count() != 1
            || !Objects.equals(reference(target, reciprocal(field)), owner.elementIntent().elementRef())) continue;
        var origin = originalSequence(history, proof, owner, target, field);
        var rename = renamedSequence(history, proof, owner, target, invalid, field, origin);
        if (origin >= 0 && rename >= 0) candidates.add(new RepairTarget(target, origin, rename));
      }
      if (candidates.size() != 1) continue;
      var target = candidates.getFirst();
      var replacement = replaceReference(owner, field, target.fact().elementIntent().elementRef());
      var corrected = new ArrayList<>(protectedFacts);
      corrected.set(protectedFacts.indexOf(owner), replacement);
      if (RequirementCaptureEditor.validate(withFacts(proof.input(), corrected)).stream()
          .anyMatch(value -> value.path().startsWith(prefix))) continue;
      repaired.set(protectedFacts.indexOf(owner), replacement);
      evidence.add(proof.sequence());
      evidence.add(target.originalSequence());
      evidence.add(target.renamedSequence());
    }
    return new Repair(List.copyOf(repaired), Set.copyOf(evidence));
  }

  private record RepairTarget(FactInput fact, long originalSequence, long renamedSequence) {}

  private static long originalSequence(List<Attempt> history, Attempt proof, FactInput owner,
      FactInput target, String field) {
    for (var attempt : history) {
      if (attempt.sequence() >= proof.sequence()) break;
      var originalOwner = unique(attempt.input().facts(), owner.sourceFactId());
      var originalTarget = unique(attempt.input().facts(), target.sourceFactId());
      if (!sameFact(owner, originalOwner) || !sameFact(target, originalTarget)
          || !sameIntentExcept(owner, originalOwner, Set.of("elementRef", field))
          || !sameIntentExcept(target, originalTarget, Set.of(reciprocal(field)))
          || !Objects.equals(reference(originalOwner, field), target.elementIntent().elementRef())
          || !Objects.equals(reference(originalTarget, reciprocal(field)), originalOwner.elementIntent().elementRef())) continue;
      String ownerRef = originalOwner.elementIntent().elementRef();
      var nativeIssues = RequirementCaptureEditor.validate(attempt.input());
      String ownerPath = "/draft/facts/" + owner.sourceFactId() + "/elementIntent/elementRef";
      if (!Objects.equals(ownerRef, owner.elementIntent().elementRef()) && attempt.issues().stream().noneMatch(issue ->
          Set.of("INVALID_ELEMENT_REFERENCE", "INVALID_VALUE").contains(issue.code()) && ownerPath.equals(issue.path())
              && nativeIssues.stream().anyMatch(value -> value.code().equals(issue.code()) && value.path().equals(ownerPath)))) continue;
      if (nativeIssues.stream().anyMatch(issue ->
          issue.path().equals("/draft/facts/" + target.sourceFactId() + "/elementIntent/elementRef"))) continue;
      return attempt.sequence();
    }
    return -1;
  }

  private static long renamedSequence(List<Attempt> history, Attempt proof, FactInput owner,
      FactInput target, String invalid, String field, long originalSequence) {
    if (originalSequence < 0) return -1;
    for (var attempt : history) {
      if (attempt.sequence() <= originalSequence || attempt.sequence() >= proof.sequence()) continue;
      var renamedOwner = unique(attempt.input().facts(), owner.sourceFactId());
      var renamedTarget = unique(attempt.input().facts(), target.sourceFactId());
      if (!owner.equals(renamedOwner) || !sameFact(target, renamedTarget)
          || !sameIntentExcept(target, renamedTarget, Set.of("elementRef"))
          || !Objects.equals(renamedTarget.elementIntent().elementRef(), invalid)
          || !Objects.equals(reference(renamedTarget, reciprocal(field)), owner.elementIntent().elementRef())
          || attempt.issues().stream().noneMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())
              && ("/draft/facts/" + target.sourceFactId()).equals(issue.path()))) continue;
      if (!RequirementCaptureEditor.validate(attempt.input()).isEmpty()) continue;
      return attempt.sequence();
    }
    return -1;
  }

  private static boolean sameFact(FactInput left, FactInput right) {
    if (right == null) return false;
    var first = (ObjectNode) JSON.valueToTree(left);
    var second = (ObjectNode) JSON.valueToTree(right);
    first.remove("elementIntent");
    second.remove("elementIntent");
    return first.equals(second);
  }

  private static boolean sameIntentExcept(FactInput left, FactInput right, Set<String> fields) {
    if (right == null || right.elementIntent() == null) return false;
    var first = (ObjectNode) JSON.valueToTree(left.elementIntent());
    var second = (ObjectNode) JSON.valueToTree(right.elementIntent());
    first.remove(fields);
    second.remove(fields);
    return first.equals(second);
  }

  private static FactInput unique(List<FactInput> facts, String id) {
    var matches = facts.stream().filter(Objects::nonNull).filter(fact -> Objects.equals(fact.sourceFactId(), id)).toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static String reciprocal(String field) {
    return "successorElementRef".equals(field) ? "predecessorElementRef" : "successorElementRef";
  }

  private static String reference(FactInput fact, String field) {
    return "successorElementRef".equals(field) ? fact.elementIntent().successorElementRef()
        : fact.elementIntent().predecessorElementRef();
  }

  private static FactInput replaceReference(FactInput fact, String field, String value) {
    var tree = (ObjectNode) JSON.valueToTree(fact);
    ((ObjectNode) tree.path("elementIntent")).put(field, value);
    return JSON.convertValue(tree, FactInput.class);
  }

  private static DraftInput withFacts(DraftInput draft, List<FactInput> facts) {
    return new DraftInput(draft.flow(), facts, draft.capabilities(), draft.openQuestions(), draft.settings());
  }
}
