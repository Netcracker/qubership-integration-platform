package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;

/** Builds immutable graph-patch artifacts from one validated execution attempt. */
@ApplicationScoped
public class GraphPatchArtifactFactory {

  private static final int SCHEMA_VERSION = 1;

  private final CanonicalGraphDigest canonicalGraphDigest;
  private final ObjectMapper objectMapper;

  @Inject
  public GraphPatchArtifactFactory(CanonicalGraphDigest canonicalGraphDigest) {
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
    this.objectMapper = new ObjectMapper();
  }

  public GraphPatchArtifact create(
      GraphPatchExecutionContext context, GraphPatch patch, ChainPlanGraph resultGraph) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(patch, "patch");
    Objects.requireNonNull(resultGraph, "resultGraph");

    String baseGraphDigest = canonicalGraphDigest.sha256(context.inputGraph());
    String resultGraphDigest = canonicalGraphDigest.sha256(resultGraph);
    PatchApplicability applicability =
        baseGraphDigest.equals(resultGraphDigest)
            ? PatchApplicability.NOT_APPLICABLE
            : PatchApplicability.APPLICABLE;

    return new GraphPatchArtifact(
        SCHEMA_VERSION,
        patch.patchId(),
        patch.ownerCapabilityId(),
        baseGraphDigest,
        resultGraphDigest,
        patch,
        context.consumedArtifacts(),
        sourceRequirementFactIds(context),
        patch.usedKnowledgeRefs(),
        patch.rationale(),
        applicability,
        invocationKey(context));
  }

  private static List<String> sourceRequirementFactIds(GraphPatchExecutionContext context) {
    if (context.requirementBrief() == null || context.requirementBrief().facts() == null) {
      return List.of();
    }
    return context.requirementBrief().facts().stream().map(RequirementFact::sourceFactId).toList();
  }

  private String invocationKey(GraphPatchExecutionContext context) {
    Map<String, String> material = new LinkedHashMap<>();
    material.put("runId", context.runId());
    material.put("skillId", context.skillId());
    material.put("requirementDigest", context.requirementDigest());
    material.put("inputGraphDigest", context.inputGraphDigest());
    material.put("compilerPackageDigest", context.compilerPackageDigest());
    material.put("attemptId", context.attemptId() == null ? "" : context.attemptId());
    try {
      byte[] payload = objectMapper.writeValueAsBytes(material);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot build graph patch invocation key", e);
    }
  }
}
