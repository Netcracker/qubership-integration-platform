package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchRejection;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphOwnershipFact;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;

/** Replays accepted patch ledger and emits a deterministic final graph assembly result. */
@ApplicationScoped
public class GraphAssemblyService implements CompilerNodeExecutionAdapter {

  private static final int SCHEMA_VERSION = 1;
  private static final String ADAPTER_ID = "graph-assembly";

  private final CanonicalGraphDigest canonicalGraphDigest;
  private final GraphPatchApplier graphPatchApplier;

  @Inject
  public GraphAssemblyService(CanonicalGraphDigest canonicalGraphDigest) {
    this(canonicalGraphDigest, new GraphPatchApplier());
  }

  GraphAssemblyService(CanonicalGraphDigest canonicalGraphDigest, GraphPatchApplier graphPatchApplier) {
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
    this.graphPatchApplier = Objects.requireNonNull(graphPatchApplier, "graphPatchApplier");
  }

  @Override
  public String adapterId() {
    return ADAPTER_ID;
  }

  @Override
  public CompilerNodeExecutionResult execute(ResolvedCompilerNode node, PlanningSchedulerState state) {
    return new CompilerNodeExecutionResult(List.of(), List.of());
  }

  public GraphAssemblyResult assemble(
      ChainStructure structure, List<GraphPatchArtifact> acceptedPatchArtifacts) {
    Objects.requireNonNull(structure, "structure");
    ChainPlanGraph graph = structure.graph();
    if (graph == null) {
      throw new IllegalStateException("contract failure: chain structure graph is required");
    }
    List<GraphPatchArtifact> patches =
        acceptedPatchArtifacts == null ? List.of() : List.copyOf(acceptedPatchArtifacts);
    List<CompilationArtifacts.Reference> orderedPatchReferences = new ArrayList<>();
    List<PatchRejection> rejected = new ArrayList<>();
    for (GraphPatchArtifact artifact : patches) {
      if (artifact == null) {
        continue;
      }
      if (artifact.consumedArtifacts() != null) {
        orderedPatchReferences.addAll(artifact.consumedArtifacts());
      }
      if (artifact.applicability() == PatchApplicability.NOT_APPLICABLE) {
        continue;
      }
      var applyResult = graphPatchApplier.apply(graph, artifact.patch());
      if (!applyResult.validationResult().valid()) {
        List<String> findings =
            applyResult.validationResult().issues().stream()
                .map(issue -> issue.message() == null ? "patch validation failed" : issue.message())
                .toList();
        rejected.add(
            new PatchRejection(
                artifact.ownerCapabilityId(),
                artifact.patchId(),
                canonicalGraphDigest.sha256(graph),
                findings));
        continue;
      }
      graph = applyResult.graph();
    }
    graph = CompilerSecurityFallback.apply(graph);
    return assembled(graph, orderedPatchReferences, rejected);
  }

  /**
   * Prefer this when the workspace already holds the sequentially patched {@link ChainPlanGraph}.
   * Replaying only the latest {@link GraphPatchArtifact} would drop earlier generator patches.
   */
  public GraphAssemblyResult assembleFromGraph(ChainPlanGraph graph) {
    return assembleFromGraph(graph, List.of(), List.of());
  }

  public GraphAssemblyResult assembleFromGraph(
      ChainPlanGraph graph,
      List<CompilationArtifacts.Reference> orderedPatchReferences,
      List<GraphOwnershipFact> ownershipFacts) {
    Objects.requireNonNull(graph, "graph");
    return assembled(
        graph,
        orderedPatchReferences == null ? List.of() : orderedPatchReferences,
        ownershipFacts == null ? List.of() : ownershipFacts,
        List.of());
  }

  private GraphAssemblyResult assembled(
      ChainPlanGraph graph,
      List<CompilationArtifacts.Reference> orderedPatchReferences,
      List<PatchRejection> rejected) {
    return assembled(graph, orderedPatchReferences, List.of(), rejected);
  }

  private GraphAssemblyResult assembled(
      ChainPlanGraph graph,
      List<CompilationArtifacts.Reference> orderedPatchReferences,
      List<GraphOwnershipFact> ownershipFacts,
      List<PatchRejection> rejected) {
    return new GraphAssemblyResult(
        SCHEMA_VERSION,
        graph,
        canonicalGraphDigest.sha256(graph),
        List.copyOf(orderedPatchReferences),
        List.copyOf(ownershipFacts),
        List.copyOf(rejected));
  }
}
