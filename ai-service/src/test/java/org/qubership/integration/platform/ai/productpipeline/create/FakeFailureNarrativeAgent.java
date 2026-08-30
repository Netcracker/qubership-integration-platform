package org.qubership.integration.platform.ai.productpipeline.create;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.agent.HaltQuestionDraft;
import org.qubership.integration.platform.ai.llm.agent.OwnerDiagnosisDraft;
import org.qubership.integration.platform.ai.productpipeline.recovery.ProposedBriefChange;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;

/** Test double for {@link FailureNarrativeAgent}; captures the candidate list and follow-up. */
public final class FakeFailureNarrativeAgent implements FailureNarrativeAgent {

  /** Stand-in for the LLM go-back offer; tests assert this token, not live prose. */
  public static final String GO_BACK_OFFER =
      "Type go back or click Revise; you do not write YAML.";

  private final String narrative;
  private final List<String> ownerStageIds;
  private final AtomicInteger ownerIndex = new AtomicInteger();
  private final boolean ambiguous;
  private final RuntimeException boom;
  private final Duration delay;
  private String remedy = "";
  private String instruction = "";
  private String questionVerdict = "INSTRUCTION";
  private String questionAnswer = "";
  private String onlyQuestion;

  /** Turns the double was asked for, whatever it answered. */
  public final AtomicInteger calls = new AtomicInteger();

  /** Halt-question turns alone, so a test can count them apart from diagnosis turns. */
  public final AtomicInteger questionCalls = new AtomicInteger();

  /** Approval-question turns alone, counted apart from halt questions. */
  public final AtomicInteger approvalQuestionCalls = new AtomicInteger();

  public final AtomicReference<String> lastQuestion = new AtomicReference<>();

  /** Candidate evidence the last approval question was asked against. */
  public final AtomicReference<String> lastApprovalCandidate = new AtomicReference<>();

  public final AtomicReference<String> lastCandidateSet = new AtomicReference<>();
  public final AtomicReference<String> lastFollowUp = new AtomicReference<>();
  public final AtomicReference<String> lastOutcome = new AtomicReference<>();
  public final AtomicReference<String> lastException = new AtomicReference<>();
  public final AtomicReference<String> lastFindings = new AtomicReference<>();
  public final AtomicReference<String> lastClarifyRoles = new AtomicReference<>();

  public final AtomicReference<String> lastRequestedFact = new AtomicReference<>();

  public final AtomicReference<String> lastClarificationLocale = new AtomicReference<>();

  /** Recovery context JSON from the last recover turn. */
  public final AtomicReference<String> lastRecoveryContextJson = new AtomicReference<>();

  private String clarificationQuestion = "";
  private final Deque<RecoveryDecision> recoveryDecisions = new ArrayDeque<>();
  private Kind regenerateFaultKind;
  private String regenerateSummary = "";
  private Reference reviseBriefRef;
  private List<ProposedBriefChange> reviseBriefChanges = List.of();
  private String reviseBriefSummary = "";
  private static final ObjectMapper CONTEXT_MAPPER = new ObjectMapper();

  private FakeFailureNarrativeAgent(
      String narrative, List<String> ownerStageIds, boolean ambiguous, RuntimeException boom) {
    this(narrative, ownerStageIds, ambiguous, boom, null);
  }

  private FakeFailureNarrativeAgent(
      String narrative,
      List<String> ownerStageIds,
      boolean ambiguous,
      RuntimeException boom,
      Duration delay) {
    this.narrative = narrative;
    this.ownerStageIds = ownerStageIds == null ? List.of() : List.copyOf(ownerStageIds);
    this.ambiguous = ambiguous;
    this.boom = boom;
    this.delay = delay;
  }

  public static FakeFailureNarrativeAgent narrates(String text) {
    return new FakeFailureNarrativeAgent(text, List.of(), false, null);
  }

  public static FakeFailureNarrativeAgent owner(String narrative, String ownerStageId) {
    return new FakeFailureNarrativeAgent(narrative, List.of(ownerStageId), false, null);
  }

  public static FakeFailureNarrativeAgent offeringGoBack(String narrative, String ownerStageId) {
    return owner(narrative + " " + GO_BACK_OFFER, ownerStageId);
  }

  public static FakeFailureNarrativeAgent owners(String narrative, String... ownerStageIds) {
    return new FakeFailureNarrativeAgent(narrative, List.of(ownerStageIds), false, null);
  }

  public static FakeFailureNarrativeAgent ask(String narrative) {
    return new FakeFailureNarrativeAgent(narrative, List.of(), true, null);
  }

  /**
   * Puts a remedy on the diagnosis draft. {@code remedy} is the raw token the model would emit, so
   * a test can hand over one the closed set does not hold.
   */
  public FakeFailureNarrativeAgent remedying(String remedy, String instruction) {
    this.remedy = remedy == null ? "" : remedy;
    this.instruction = instruction == null ? "" : instruction;
    return this;
  }

  /**
   * Sets the model-authored clarification question this double returns.
   */
  public FakeFailureNarrativeAgent clarifying(String question) {
    this.clarificationQuestion = question == null ? "" : question;
    return this;
  }

  /**
   * Reads the next halt message as a question and answers it with {@code answer}. Without this the
   * double calls every halt message an instruction, which is the verdict that changes nothing.
   */
  public FakeFailureNarrativeAgent answering(String answer) {
    this.questionVerdict = "QUESTION";
    this.questionAnswer = answer == null ? "" : answer;
    return this;
  }

  /**
   * Reads only {@code question} as a question and every other message as an instruction, so one run
   * can both ask and instruct without swapping the double.
   */
  public FakeFailureNarrativeAgent answeringOnly(String question, String answer) {
    this.onlyQuestion = question;
    return answering(answer);
  }

  /** Answers a halt question under a verdict token the closed pair may or may not hold. */
  public FakeFailureNarrativeAgent answeringUnder(String verdict, String answer) {
    this.questionVerdict = verdict == null ? "" : verdict;
    this.questionAnswer = answer == null ? "" : answer;
    return this;
  }

  /** Appends structured recovery decisions in the order this double returns them. */
  public FakeFailureNarrativeAgent recoverReturns(RecoveryDecision... decisions) {
    if (decisions != null) {
      Arrays.stream(decisions).filter(java.util.Objects::nonNull).forEach(recoveryDecisions::addLast);
    }
    return this;
  }

  /** Returns {@link RecoveryAction#REGENERATE_ARTIFACT} with evidence refs bound to the context. */
  public FakeFailureNarrativeAgent recoverRegenerates(Kind faultKind, String summary) {
    this.regenerateFaultKind = faultKind;
    this.regenerateSummary = summary == null ? "" : summary;
    return this;
  }

  /** Returns {@link RecoveryAction#REVISE_BRIEF} with evidence refs bound to the context. */
  public FakeFailureNarrativeAgent recoverReviseBrief(
      Reference approvedBrief, List<ProposedBriefChange> changes, String summary) {
    this.reviseBriefRef = approvedBrief;
    this.reviseBriefChanges = changes == null ? List.of() : List.copyOf(changes);
    this.reviseBriefSummary = summary == null ? "" : summary;
    return this;
  }

  public static FakeFailureNarrativeAgent boom() {
    return new FakeFailureNarrativeAgent(
        "", List.of(), false, new IllegalStateException("model unavailable"));
  }

  /** Answers only after {@code delay}, so a caller with a shorter timeout gives up first. */
  public static FakeFailureNarrativeAgent slow(String narrative, Duration delay) {
    return new FakeFailureNarrativeAgent(narrative, List.of(), false, null, delay);
  }

  private void waitOutTheDelay() {
    if (delay == null) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException abandoned) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("narrative turn was abandoned", abandoned);
    }
  }

  private String nextOwnerStageId() {
    if (ownerStageIds.isEmpty()) {
      return "";
    }
    int index = Math.min(ownerIndex.getAndIncrement(), ownerStageIds.size() - 1);
    return ownerStageIds.get(index);
  }

  @Override
  public String narrate(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String followUpText,
      String clarifyRoles) {
    calls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastFollowUp.set(followUpText);
    lastOutcome.set(outcomeClass);
    lastException.set(exceptionMessage);
    lastFindings.set(validationFindings);
    lastClarifyRoles.set(clarifyRoles);
    return narrative;
  }

  @Override
  public OwnerDiagnosisDraft diagnose(
      String responseLocale,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String candidateSet,
      String followUpText,
      String clarifyRoles) {
    calls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastCandidateSet.set(candidateSet);
    lastFollowUp.set(followUpText);
    lastOutcome.set(outcomeClass);
    lastException.set(exceptionMessage);
    lastFindings.set(validationFindings);
    lastClarifyRoles.set(clarifyRoles);
    return new OwnerDiagnosisDraft(narrative);
  }

  @Override
  public String askClarification(
      String responseLocale, String requestedFact, String stageId, String exceptionMessage) {
    calls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastClarificationLocale.set(responseLocale);
    lastRequestedFact.set(requestedFact);
    lastException.set(exceptionMessage);
    if (!clarificationQuestion.isBlank()) {
      return clarificationQuestion;
    }
    return requestedFact == null ? "" : requestedFact;
  }

  @Override
  public HaltQuestionDraft answerHaltQuestion(
      String responseLocale,
      String message,
      String stageId,
      String outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String candidateSet,
      String followUpText) {
    calls.incrementAndGet();
    questionCalls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastQuestion.set(message);
    lastCandidateSet.set(candidateSet);
    lastFollowUp.set(followUpText);
    lastOutcome.set(outcomeClass);
    lastException.set(exceptionMessage);
    lastFindings.set(validationFindings);
    return draftFor(message);
  }

  @Override
  public HaltQuestionDraft answerApprovalQuestion(
      String responseLocale, String message, String stageId, String candidate) {
    calls.incrementAndGet();
    approvalQuestionCalls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastQuestion.set(message);
    lastApprovalCandidate.set(candidate);
    return draftFor(message);
  }

  private HaltQuestionDraft draftFor(String message) {
    if (onlyQuestion != null && !onlyQuestion.equals(message)) {
      return new HaltQuestionDraft("INSTRUCTION", "");
    }
    return new HaltQuestionDraft(questionVerdict, questionAnswer);
  }

  @Override
  public RecoveryDecision recover(String responseLocale, String recoveryContextJson) {
    calls.incrementAndGet();
    if (boom != null) {
      throw boom;
    }
    waitOutTheDelay();
    lastRecoveryContextJson.set(recoveryContextJson);
    if (regenerateFaultKind != null) {
      String failureId = failureIdFromContext(recoveryContextJson);
      Reference faultRef = faultRefFromContext(recoveryContextJson, regenerateFaultKind);
      return new RecoveryDecision(
          RecoveryCauseClass.DERIVATION_DEFECT,
          faultRef,
          List.of(failureId),
          RecoveryAction.REGENERATE_ARTIFACT,
          List.of(),
          "",
          regenerateSummary);
    }
    if (reviseBriefRef != null) {
      String failureId = failureIdFromContext(recoveryContextJson);
      return new RecoveryDecision(
          RecoveryCauseClass.BRIEF_DEFECT,
          reviseBriefRef,
          List.of(failureId),
          RecoveryAction.REVISE_BRIEF,
          reviseBriefChanges,
          "",
          reviseBriefSummary);
    }
    return recoveryDecisions.size() > 1
        ? recoveryDecisions.removeFirst()
        : recoveryDecisions.peekFirst();
  }

  private static String failureIdFromContext(String recoveryContextJson) {
    if (recoveryContextJson == null || recoveryContextJson.isBlank()) {
      return "";
    }
    int marker = recoveryContextJson.indexOf("\"failureId\"");
    if (marker < 0) {
      return "";
    }
    int colon = recoveryContextJson.indexOf(':', marker);
    int start = recoveryContextJson.indexOf('"', colon + 1);
    if (start < 0) {
      return "";
    }
    int end = recoveryContextJson.indexOf('"', start + 1);
    return end < 0 ? "" : recoveryContextJson.substring(start + 1, end);
  }

  private static Reference faultRefFromContext(String recoveryContextJson, Kind faultKind) {
    if (recoveryContextJson == null || recoveryContextJson.isBlank() || faultKind == null) {
      return null;
    }
    try {
      JsonNode refs = CONTEXT_MAPPER.readTree(recoveryContextJson).path("evidence").path("rejectedArtifactRefs");
      if (!refs.isArray()) {
        return null;
      }
      for (JsonNode ref : refs) {
        if (faultKind.name().equals(ref.path("kind").asText())) {
          return new Reference(
              faultKind, ref.path("artifactId").asText(), ref.path("contentHash").asText());
        }
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }
}
