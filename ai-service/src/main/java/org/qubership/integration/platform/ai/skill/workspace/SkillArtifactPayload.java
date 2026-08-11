package org.qubership.integration.platform.ai.skill.workspace;

import org.qubership.integration.platform.ai.compiler.plan.CompilerStatus;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.skill.executor.HitlCheckpoint;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.DecisionTrace;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

import java.util.List;

/** Sealed payload union stored inside {@link SkillArtifact}. */
public sealed interface SkillArtifactPayload
    permits SkillArtifactPayload.RawUserRequestPayload,
        SkillArtifactPayload.RequirementBriefPayload,
        SkillArtifactPayload.DecisionTracePayload,
        SkillArtifactPayload.SelectedPatternPayload,
        SkillArtifactPayload.ElementSkeletonPayload,
        SkillArtifactPayload.NamingManifestPayload,
        SkillArtifactPayload.ConfiguredTriggerSetPayload,
        SkillArtifactPayload.ChainStructurePayload,
        SkillArtifactPayload.ChainPlanGraphPayload,
        SkillArtifactPayload.GraphPatchPayload,
        SkillArtifactPayload.GraphPatchArtifactPayload,
        SkillArtifactPayload.GraphAssemblyResultPayload,
        SkillArtifactPayload.CompilerValidationBundlePayload,
        SkillArtifactPayload.GeneratorPlanManifestPayload,
        SkillArtifactPayload.CompilerStatusPayload,
        SkillArtifactPayload.ValidationResultPayload,
        SkillArtifactPayload.PlanCaptureOutcomePayload,
        SkillArtifactPayload.MaterializationMapPayload,
        SkillArtifactPayload.ReconcileResultPayload,
        SkillArtifactPayload.CatalogChainSnapshotPayload,
        SkillArtifactPayload.HitlCheckpointPayload {

  record RawUserRequestPayload(String effectiveText, List<String> attachmentObjectKeys)
      implements SkillArtifactPayload {}

  record RequirementBriefPayload(RequirementBrief brief) implements SkillArtifactPayload {}

  record DecisionTracePayload(DecisionTrace trace) implements SkillArtifactPayload {}

  record SelectedPatternPayload(SelectedPattern pattern) implements SkillArtifactPayload {}

  record ElementSkeletonPayload(ElementSkeleton skeleton) implements SkillArtifactPayload {}

  record NamingManifestPayload(NamingManifest manifest) implements SkillArtifactPayload {}

  record ConfiguredTriggerSetPayload(ConfiguredTriggerSet triggers)
      implements SkillArtifactPayload {}

  record ChainStructurePayload(ChainStructure structure) implements SkillArtifactPayload {}

  record ChainPlanGraphPayload(ChainPlanGraph graph) implements SkillArtifactPayload {}

  record GraphPatchPayload(GraphPatch patch) implements SkillArtifactPayload {}

  record GraphPatchArtifactPayload(GraphPatchArtifact artifact) implements SkillArtifactPayload {}

  record GraphAssemblyResultPayload(GraphAssemblyResult result) implements SkillArtifactPayload {}

  record CompilerValidationBundlePayload(CompilerValidationBundle bundle)
      implements SkillArtifactPayload {}

  record GeneratorPlanManifestPayload(GeneratorPlanManifest manifest) implements SkillArtifactPayload {}

  record CompilerStatusPayload(CompilerStatus status) implements SkillArtifactPayload {}

  record ValidationResultPayload(ValidationResult result) implements SkillArtifactPayload {}

  record PlanCaptureOutcomePayload(boolean captured, String message) implements SkillArtifactPayload {}


  record MaterializationMapPayload(MaterializationMap map) implements SkillArtifactPayload {}

  record ReconcileResultPayload(ReconcileResult result) implements SkillArtifactPayload {}

  record CatalogChainSnapshotPayload(ChainCatalogFacts facts) implements SkillArtifactPayload {}

  record HitlCheckpointPayload(HitlCheckpoint checkpoint) implements SkillArtifactPayload {}
}
