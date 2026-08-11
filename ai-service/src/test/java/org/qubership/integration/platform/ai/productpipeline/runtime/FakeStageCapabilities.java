package org.qubership.integration.platform.ai.productpipeline.runtime;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Test-only fake capabilities for the two-stage approval profile. */
public final class FakeStageCapabilities {

  private FakeStageCapabilities() {}

  public static StageCapability collector() {
    return new StageCapability() {
      private final AtomicInteger invocations = new AtomicInteger();
      private final Map<String, Integer> perRun = new ConcurrentHashMap<>();

      @Override
      public String capabilityId() {
        return "fake-collector";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        int call = perRun.merge(context.runId(), 1, Integer::sum);
        invocations.incrementAndGet();
        if (call == 1 && !context.attributes().containsKey("userText")) {
          return Multi.createFrom()
              .items(
                  new CapabilitySignal.Progress("collect", "waiting"),
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need user input")));
        }
        RequirementBrief draft =
            new RequirementBrief(
                "collected",
                List.of(
                    String.valueOf(
                        context.attributes().getOrDefault("userText", "default"))),
                List.of(),
                List.of(),
                List.of(),
                "draft");
        return Multi.createFrom()
            .items(
                new CapabilitySignal.Message("producing draft"),
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, draft, List.of())),
                        "draft ready",
                        null)));
      }
    };
  }

  public static StageCapability finisher() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "fake-finisher";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        IdsBypass plan = new IdsBypass("finished", "test-two-stage", "1");
        return Multi.createFrom()
            .items(
                new CapabilitySignal.Message("producing plan"),
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.IDS_BYPASS, plan, context.inputRefs())),
                        "plan ready",
                        null)));
      }
    };
  }

  public static StageCapability flakyTechnical() {
    return flakyTechnical("temporary transport failure");
  }

  public static StageCapability flakyTechnical(String firstFailureMessage) {
    return new StageCapability() {
      private final AtomicInteger attempts = new AtomicInteger();

      @Override
      public String capabilityId() {
        return "fake-collector";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (attempts.incrementAndGet() == 1) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                          List.of(),
                          firstFailureMessage,
                          1L)));
        }
        RequirementBrief draft =
            new RequirementBrief(
                "ok", List.of(), List.of(), List.of(), List.of(), "ok");
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.REQUIREMENT_BRIEF,
                                draft,
                                List.of())),
                        "recovered",
                        null)));
      }
    };
  }

  /** Always fails with a retryable technical outcome so retry budgets can be asserted. */
  public static StageCapability alwaysTechnicalFailure() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "fake-collector";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                        List.of(),
                        "persistent transport failure",
                        1L)));
      }
    };
  }
}
