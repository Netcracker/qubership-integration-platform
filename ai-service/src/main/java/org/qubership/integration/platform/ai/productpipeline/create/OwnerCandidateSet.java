package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;

/**
 * Closed owner set for a failed stage: the stage itself plus producers of its consumed types.
 * Deepen once to those producers' producers when the first layer cannot pick an owner.
 */
public final class OwnerCandidateSet {

  private static final Set<String> PLAN_ARTIFACT_TYPES =
      Set.of("implementation-plan", "design-execution-plan", "design-plan-report");

  private static final Set<String> BRIEF_ARTIFACT_TYPES = Set.of("requirement-brief");
  private static final Set<String> DRAFT_ARTIFACT_TYPES = Set.of("requirement-draft");
  private static final Set<String> CATALOG_BINDING_HINT_TYPES = Set.of("catalog-binding-hint");
  private static final List<String> CATALOG_BINDING_PRODUCER_STAGE_IDS =
      List.of("requirement-discovery", "uploaded-spec-import");
  private static final Set<String> SEMANTIC_REVISION_TYPES = Set.of("chain-semantic-revision");
  private static final List<String> SEMANTIC_REVISION_PRODUCER_STAGE_IDS = List.of("design-input");

  private static final Pattern GO_BACK_TO_TARGET =
      Pattern.compile(
          "(go\\s+back|back|return|reopen)\\s+to\\s+\\S+", Pattern.CANON_EQ);

  /**
   * Closed finding-owner categories for remapping. Extend only by adding a category, not by growing
   * product-word lists. Driven by finding codes and a few structural cues.
   *
   * <ul>
   *   <li>{@link FindingOwnerCategory#POLICY_OR_BRIEF} — access policy, auth, scope, or constraints
   *       owned by the requirement brief
   *   <li>{@link FindingOwnerCategory#PLAN_FILL} — plan structure, bindings, or step properties when
   *       policy already allows them
   *   <li>{@link FindingOwnerCategory#EXECUTION} — transient compile/runtime with good upstream
   *       inputs
   *   <li>{@link FindingOwnerCategory#UNSPECIFIED} — no automatic remap preference
   * </ul>
   */
  enum FindingOwnerCategory {
    POLICY_OR_BRIEF,
    PLAN_FILL,
    EXECUTION,
    UNSPECIFIED
  }

  private OwnerCandidateSet() {}

  /**
   * Failed stage plus earlier stages that produce any type it consumes. Run inputs have no producer
   * stage and do not appear.
   */
  public static List<OwnerCandidate> firstLayer(
      ProductPipelineProfile profile, String failedStageId) {
    int failedIndex = indexOf(profile, failedStageId);
    if (failedIndex < 0) {
      return List.of();
    }
    ProfileStage failed = profile.stages().get(failedIndex);
    Map<String, OwnerCandidate> byId = new LinkedHashMap<>();
    byId.put(failed.stageId(), candidateFor(failed));
    addProducersOf(profile, failed, failedIndex, byId);
    return List.copyOf(byId.values());
  }

  /**
   * First layer plus producers of each first-layer stage's consumes. Same failed-stage bound: only
   * earlier stages.
   */
  public static List<OwnerCandidate> deepen(
      ProductPipelineProfile profile, List<OwnerCandidate> layer) {
    if (profile == null || layer == null || layer.isEmpty()) {
      return layer == null ? List.of() : List.copyOf(layer);
    }
    Map<String, OwnerCandidate> byId = new LinkedHashMap<>();
    for (OwnerCandidate candidate : layer) {
      if (candidate != null && !candidate.stageId().isBlank()) {
        byId.put(candidate.stageId(), candidate);
      }
    }
    for (OwnerCandidate candidate : List.copyOf(byId.values())) {
      int index = indexOf(profile, candidate.stageId());
      if (index < 0) {
        continue;
      }
      addProducersOf(profile, profile.stages().get(index), index, byId);
    }
    return List.copyOf(byId.values());
  }

  public static boolean containsStage(List<OwnerCandidate> candidates, String stageId) {
    if (candidates == null || stageId == null || stageId.isBlank()) {
      return false;
    }
    return candidates.stream().anyMatch(candidate -> stageId.equals(candidate.stageId()));
  }

  public static List<String> stageIds(List<OwnerCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (OwnerCandidate candidate : candidates) {
      if (candidate != null && !candidate.stageId().isBlank()) {
        ids.add(candidate.stageId());
      }
    }
    return List.copyOf(ids);
  }

  /**
   * Selects the owner from the typed cause and the closed candidate set. The model's owner and
   * ambiguity flag are not inputs. When the cause does not name a unique producer, this asks among
   * the candidates rather than guessing. That extra owner-choice card is the recorded trade for
   * dropping model-assisted disambiguation: a card the author answers beats a routing decision
   * nothing can check.
   *
   * <p>A follow-up that names exactly one candidate still wins, because that is the author speaking,
   * not the model.
   */
  public static OwnerDiagnosis selectOwner(
      String narrative,
      List<OwnerCandidate> candidates,
      String failedStageId,
      RecoveryCause cause,
      String followUpText) {
    String text = narrative == null ? "" : narrative;
    RecoveryCauseCode code =
        cause == null ? RecoveryCauseCode.VALIDATION_BLOCKER : cause.causeCode();
    Optional<String> preferred =
        preferredProducer(HaltProducerCauseTable.ownerCategory(code), candidates, failedStageId);
    OwnerDiagnosis routed;
    if (preferred.isPresent()) {
      routed = OwnerDiagnosis.of(text, preferred.get());
    } else {
      List<String> ids = stageIds(candidates);
      if (ids.size() == 1) {
        routed = OwnerDiagnosis.of(text, ids.get(0));
      } else if (!ids.isEmpty()) {
        routed = OwnerDiagnosis.ask(text);
      } else {
        routed = OwnerDiagnosis.none(text);
      }
    }
    return preferNamedOwner(routed, candidates, followUpText);
  }

  /**
   * When the follow-up names exactly one stage in {@code candidates}, that owner replaces the
   * automatic diagnosis. Two matches become an ask. No named stage keeps {@code diagnosis}.
   */
  public static OwnerDiagnosis preferNamedOwner(
      OwnerDiagnosis diagnosis,
      List<OwnerCandidate> candidates,
      String followUpText) {
    OwnerDiagnosis current = diagnosis == null ? OwnerDiagnosis.none("") : diagnosis;
    List<String> named = namedStages(followUpText, candidates);
    if (named.size() == 1) {
      return current.withOwner(named.get(0));
    }
    if (named.size() > 1) {
      return current.asAsk();
    }
    return current;
  }

  /**
   * Stage ids from {@code candidates} that the follow-up names. Exact stage ids match always; fuzzy
   * labels such as "requirements gathering" match only on an explicit go-back request.
   */
  public static List<String> namedStages(String followUpText, List<OwnerCandidate> candidates) {
    if (followUpText == null || followUpText.isBlank() || candidates == null) {
      return List.of();
    }
    String haystack = normalize(followUpText);
    boolean goBack = requestsNamedStage(followUpText);
    List<String> matched = new ArrayList<>();
    for (OwnerCandidate candidate : candidates) {
      if (candidate == null || candidate.stageId().isBlank()) {
        continue;
      }
      if (namesCandidate(haystack, candidate, goBack)) {
        matched.add(candidate.stageId());
      }
    }
    return List.copyOf(matched);
  }

  /**
   * True when the follow-up asks to go back, with or without naming a stage. Matches English
   * aliases including a bare {@code back}.
   */
  public static boolean requestsNamedStage(String followUpText) {
    String haystack = normalize(followUpText);
    return containsPhrase(haystack, "go back")
        || containsPhrase(haystack, "back to")
        || containsPhrase(haystack, "return to")
        || containsPhrase(haystack, "reopen")
        || containsPhrase(haystack, "back");
  }

  /**
   * True when the follow-up asks to go back without naming a stage ({@code to} plus a token).
   */
  public static boolean isBareGoBack(String followUpText) {
    return requestsNamedStage(followUpText) && !hasExplicitStageTarget(followUpText);
  }

  /**
   * Owner for a bare go-back: the diagnosed owner when it is already an earlier stage; otherwise
   * the earliest upstream producer (brief, then plan) when the failed stage is {@code
   * design-execution}.
   */
  public static Optional<String> ownerForBareGoBack(
      String diagnosedOwner, List<OwnerCandidate> candidates, String failedStageId) {
    String owner = diagnosedOwner == null ? "" : diagnosedOwner.trim();
    if (!owner.isBlank() && !owner.equals(failedStageId)) {
      return Optional.of(owner);
    }
    if ("design-execution".equals(failedStageId)) {
      Optional<String> brief = briefProducerStageId(candidates, failedStageId);
      if (brief.isPresent()) {
        return brief;
      }
      Optional<String> producer = planProducerStageId(candidates, failedStageId);
      if (producer.isPresent()) {
        return producer;
      }
    }
    return owner.isBlank() ? Optional.empty() : Optional.of(owner);
  }

  /** Short role for halt prose: {@code the plan} / {@code requirements}, not the stage id. */
  public static String clarifyRole(OwnerCandidate candidate) {
    if (candidate == null) {
      return "";
    }
    String type = candidate.artifactType();
    if (PLAN_ARTIFACT_TYPES.contains(type)) {
      return "the plan";
    }
    if (type.contains("requirement")) {
      return "requirements";
    }
    return type.replace('-', ' ').trim();
  }

  /** Compact {@code stageId:role} list the diagnosis turn receives. */
  public static String formatClarifyRoles(List<OwnerCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (OwnerCandidate candidate : candidates) {
      if (candidate == null || candidate.stageId().isBlank()) {
        continue;
      }
      String role = clarifyRole(candidate);
      if (role.isBlank()) {
        continue;
      }
      lines.add(candidate.stageId() + ":" + role);
    }
    return String.join(",", lines);
  }

  private static boolean hasExplicitStageTarget(String followUpText) {
    return GO_BACK_TO_TARGET.matcher(normalize(followUpText)).find();
  }

  private static boolean namesCandidate(
      String haystack, OwnerCandidate candidate, boolean goBack) {
    String stageId = candidate.stageId();
    if (containsPhrase(haystack, stageId) || containsPhrase(haystack, stageId.replace('-', ' '))) {
      return true;
    }
    String type = candidate.artifactType();
    if (!type.isBlank()
        && (containsPhrase(haystack, type) || containsPhrase(haystack, type.replace('-', ' ')))) {
      return true;
    }
    if (goBack
        && BRIEF_ARTIFACT_TYPES.contains(type)
        && containsPhrase(haystack, "brief")) {
      return true;
    }
    return goBack && fuzzyStageMatch(haystack, stageId);
  }

  private static boolean fuzzyStageMatch(String haystack, String stageId) {
    for (String token : stageId.split("-")) {
      if (token.isBlank()) {
        continue;
      }
      if (tokenRelated(haystack, token)) {
        return true;
      }
      if ("discovery".equals(token) && tokenRelated(haystack, "gathering")) {
        return true;
      }
    }
    return false;
  }

  private static boolean tokenRelated(String haystack, String token) {
    String needle = normalize(token);
    if (needle.isBlank()) {
      return false;
    }
    for (String part : haystack.split("[^a-z0-9]+")) {
      if (part.isBlank()) {
        continue;
      }
      if (part.equals(needle) || part.startsWith(needle)) {
        return true;
      }
      if (part.length() >= 4 && needle.startsWith(part)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsPhrase(String haystack, String phrase) {
    String needle = normalize(phrase);
    if (needle.isBlank()) {
      return false;
    }
    int from = 0;
    while (from <= haystack.length() - needle.length()) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) {
        return false;
      }
      boolean startOk = at == 0 || !isTokenChar(haystack.charAt(at - 1));
      int end = at + needle.length();
      boolean endOk = end == haystack.length() || !isTokenChar(haystack.charAt(end));
      if (startOk && endOk) {
        return true;
      }
      from = at + 1;
    }
    return false;
  }

  private static boolean isTokenChar(char value) {
    return Character.isLetterOrDigit(value);
  }

  static Optional<String> preferredProducer(
      FindingOwnerCategory category, List<OwnerCandidate> candidates, String failedStageId) {
    if (category == null) {
      return Optional.empty();
    }
    return switch (category) {
      case POLICY_OR_BRIEF ->
          briefProducerStageId(candidates, failedStageId)
              .or(() -> planProducerStageId(candidates, failedStageId));
      case PLAN_FILL -> planProducerStageId(candidates, failedStageId);
      case EXECUTION -> failedStageIfPresent(candidates, failedStageId);
      case UNSPECIFIED -> Optional.empty();
    };
  }

  private static Optional<String> failedStageIfPresent(
      List<OwnerCandidate> candidates, String failedStageId) {
    if (failedStageId == null
        || failedStageId.isBlank()
        || !containsStage(candidates, failedStageId)) {
      return Optional.empty();
    }
    return Optional.of(failedStageId);
  }

  static Optional<String> planProducerStageId(
      List<OwnerCandidate> candidates, String failedStageId) {
    return producerStageId(candidates, failedStageId, PLAN_ARTIFACT_TYPES);
  }

  static Optional<String> briefProducerStageId(
      List<OwnerCandidate> candidates, String failedStageId) {
    return producerStageId(candidates, failedStageId, BRIEF_ARTIFACT_TYPES);
  }

  static Optional<String> draftProducerStageId(
      List<OwnerCandidate> candidates, String failedStageId) {
    return producerStageId(candidates, failedStageId, DRAFT_ARTIFACT_TYPES);
  }

  /**
   * Stage that can persist a {@code catalog-binding-hint}. Prefers requirement discovery, then
   * uploaded-spec import, then any candidate whose produced type is the hint.
   */
  static Optional<String> catalogBindingProducerStageId(
      List<OwnerCandidate> candidates, String failedStageId) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    for (String stageId : CATALOG_BINDING_PRODUCER_STAGE_IDS) {
      if (!stageId.equals(failedStageId) && containsStage(candidates, stageId)) {
        return Optional.of(stageId);
      }
    }
    return producerStageId(candidates, failedStageId, CATALOG_BINDING_HINT_TYPES);
  }

  /**
   * Stage that produced the {@code chain-semantic-revision}. Prefers design-input, then any
   * candidate whose produced type is the revision.
   */
  static Optional<String> semanticRevisionProducerStageId(
      List<OwnerCandidate> candidates, String failedStageId) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    for (String stageId : SEMANTIC_REVISION_PRODUCER_STAGE_IDS) {
      if (!stageId.equals(failedStageId) && containsStage(candidates, stageId)) {
        return Optional.of(stageId);
      }
    }
    return producerStageId(candidates, failedStageId, SEMANTIC_REVISION_TYPES);
  }

  /**
   * Stage that authored the inputs the failed stage consumed. Skips import and catalog write
   * stages so Retry lands before the model turn, not on a generated artifact.
   */
  public static Optional<String> previousGenerativeStageId(
      ProductPipelineProfile profile, String failedStageId) {
    int failedIndex = indexOf(profile, failedStageId);
    if (failedIndex <= 0) {
      return Optional.empty();
    }
    for (int i = failedIndex - 1; i >= 0; i--) {
      String stageId = profile.stages().get(i).stageId();
      if (!isNonGenerativeStage(stageId)) {
        return Optional.of(stageId);
      }
    }
    return Optional.empty();
  }

  static boolean isNonGenerativeStage(String stageId) {
    return "import-stage".equals(stageId)
        || "uploaded-spec-import".equals(stageId)
        || "specification-import".equals(stageId)
        || "materialization".equals(stageId)
        || "catalog-binding".equals(stageId);
  }

  private static Optional<String> producerStageId(
      List<OwnerCandidate> candidates, String failedStageId, Set<String> artifactTypes) {
    if (candidates == null || candidates.isEmpty() || artifactTypes == null) {
      return Optional.empty();
    }
    String found = null;
    for (OwnerCandidate candidate : candidates) {
      if (candidate != null
          && !candidate.stageId().isBlank()
          && !candidate.stageId().equals(failedStageId)
          && artifactTypes.contains(candidate.artifactType())) {
        found = candidate.stageId();
      }
    }
    return Optional.ofNullable(found);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  /** Compact list the diagnosis turn receives: {@code stageId:artifactType}, comma-separated. */
  public static String format(List<OwnerCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (OwnerCandidate candidate : candidates) {
      if (candidate == null || candidate.stageId().isBlank()) {
        continue;
      }
      String type = candidate.artifactType().isBlank() ? "-" : candidate.artifactType();
      lines.add(candidate.stageId() + ":" + type);
    }
    return String.join(",", lines);
  }

  private static void addProducersOf(
      ProductPipelineProfile profile,
      ProfileStage consumer,
      int consumerIndex,
      Map<String, OwnerCandidate> byId) {
    for (ArtifactTypeRef consumed : declaredConsumes(consumer)) {
      for (int i = 0; i < consumerIndex; i++) {
        ProfileStage producer = profile.stages().get(i);
        if (produces(producer, consumed)) {
          byId.putIfAbsent(producer.stageId(), candidateFor(producer));
        }
      }
    }
  }

  private static OwnerCandidate candidateFor(ProfileStage stage) {
    List<ArtifactTypeRef> produced = declaredProduces(stage);
    String type = produced.isEmpty() ? "" : produced.get(0).type();
    return new OwnerCandidate(stage.stageId(), type == null ? "" : type);
  }

  private static boolean produces(ProfileStage stage, ArtifactTypeRef consumed) {
    if (consumed == null) {
      return false;
    }
    return declaredProduces(stage).stream().anyMatch(consumed::equals);
  }

  private static List<ArtifactTypeRef> declaredProduces(ProfileStage stage) {
    List<ArtifactTypeRef> produced = new ArrayList<>();
    if (stage.produces() != null) {
      produced.addAll(stage.produces());
    }
    if (stage.optionalProduces() != null) {
      produced.addAll(stage.optionalProduces());
    }
    return produced;
  }

  private static List<ArtifactTypeRef> declaredConsumes(ProfileStage stage) {
    List<ArtifactTypeRef> consumed = new ArrayList<>();
    if (stage.consumes() != null) {
      consumed.addAll(stage.consumes());
    }
    if (stage.optionalConsumes() != null) {
      consumed.addAll(stage.optionalConsumes());
    }
    return consumed;
  }

  private static int indexOf(ProductPipelineProfile profile, String stageId) {
    if (profile == null || profile.stages() == null || stageId == null) {
      return -1;
    }
    List<ProfileStage> stages = profile.stages();
    for (int i = 0; i < stages.size(); i++) {
      if (stageId.equals(stages.get(i).stageId())) {
        return i;
      }
    }
    return -1;
  }
}
