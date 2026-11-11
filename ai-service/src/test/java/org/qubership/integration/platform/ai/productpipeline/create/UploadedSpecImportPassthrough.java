package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/** Test stand-in for uploaded-spec import on create-chain@2 when the stage is not under test. */
public final class UploadedSpecImportPassthrough {

  private UploadedSpecImportPassthrough() {}

  public static StageCapability capability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return AutoUploadedSpecImportCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        Object approved = context.attributes().get("approvedDraft");
        RequirementDraft draft =
            approved instanceof RequirementDraft requirementDraft
                ? requirementDraft
                : RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "uploaded-spec import skipped",
                        null)));
      }
    };
  }
}
