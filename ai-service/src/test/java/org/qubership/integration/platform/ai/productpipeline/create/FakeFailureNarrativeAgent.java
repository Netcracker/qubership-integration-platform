package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.agent.OwnerDiagnosisDraft;

/** Test double for {@link FailureNarrativeAgent}; captures the candidate list and follow-up. */
public final class FakeFailureNarrativeAgent implements FailureNarrativeAgent {

  private final String narrative;
  private final List<String> ownerStageIds;
  private final AtomicInteger ownerIndex = new AtomicInteger();
  private final boolean ambiguous;
  private final RuntimeException boom;
  public final AtomicReference<String> lastCandidateSet = new AtomicReference<>();
  public final AtomicReference<String> lastFollowUp = new AtomicReference<>();
  public final AtomicReference<String> lastOutcome = new AtomicReference<>();
  public final AtomicReference<String> lastException = new AtomicReference<>();
  public final AtomicReference<String> lastFindings = new AtomicReference<>();

  private FakeFailureNarrativeAgent(
      String narrative, List<String> ownerStageIds, boolean ambiguous, RuntimeException boom) {
    this.narrative = narrative;
    this.ownerStageIds = ownerStageIds == null ? List.of() : List.copyOf(ownerStageIds);
    this.ambiguous = ambiguous;
    this.boom = boom;
  }

  public static FakeFailureNarrativeAgent narrates(String text) {
    return new FakeFailureNarrativeAgent(text, List.of(), false, null);
  }

  public static FakeFailureNarrativeAgent owner(String narrative, String ownerStageId) {
    return new FakeFailureNarrativeAgent(narrative, List.of(ownerStageId), false, null);
  }

  public static FakeFailureNarrativeAgent owners(String narrative, String... ownerStageIds) {
    return new FakeFailureNarrativeAgent(narrative, List.of(ownerStageIds), false, null);
  }

  public static FakeFailureNarrativeAgent ask(String narrative) {
    return new FakeFailureNarrativeAgent(narrative, List.of(), true, null);
  }

  public static FakeFailureNarrativeAgent boom() {
    return new FakeFailureNarrativeAgent(
        "", List.of(), false, new IllegalStateException("model unavailable"));
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
      String followUpText) {
    if (boom != null) {
      throw boom;
    }
    lastFollowUp.set(followUpText);
    lastOutcome.set(outcomeClass);
    lastException.set(exceptionMessage);
    lastFindings.set(validationFindings);
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
      String followUpText) {
    if (boom != null) {
      throw boom;
    }
    lastCandidateSet.set(candidateSet);
    lastFollowUp.set(followUpText);
    lastOutcome.set(outcomeClass);
    lastException.set(exceptionMessage);
    lastFindings.set(validationFindings);
    return new OwnerDiagnosisDraft(narrative, nextOwnerStageId(), ambiguous);
  }
}
