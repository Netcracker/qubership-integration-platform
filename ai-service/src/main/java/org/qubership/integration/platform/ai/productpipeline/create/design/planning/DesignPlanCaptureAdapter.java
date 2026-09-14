package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;

/** Adds server-owned identity to a planner capture without interpreting step summaries. */
public final class DesignPlanCaptureAdapter {

  public static final String SCHEMA_VERSION = "design-plan-contract/v1";

  public DesignPlanContract adapt(
      DesignPlanCapture capture,
      String semanticRevisionId,
      String semanticRevisionHash,
      String apiRelease) {
    Objects.requireNonNull(capture, "capture");
    Set<String> stepIds = new HashSet<>();
    List<DesignPlanContract.Step> steps =
        capture.steps().stream()
            .map(
                step -> {
                  DesignPlanContract.Step adapted =
                      new DesignPlanContract.Step(
                          step.stepId(),
                          step.summary(),
                          new DesignPlanContract.Owner(step.owner().kind(), step.owner().id()),
                          step.claims().stream()
                              .map(
                                  claim ->
                                      new DesignPlanContract.Claim(
                                          claim.targetKind(), claim.targetId(), claim.role()))
                              .toList(),
                          step.dependsOnStepIds());
                  if (!stepIds.add(adapted.stepId())) {
                    throw new IllegalArgumentException(
                        "Duplicate stepId=" + adapted.stepId());
                  }
                  return adapted;
                })
            .toList();
    String contractId = semanticContractId(semanticRevisionHash, steps);
    return new DesignPlanContract(
        SCHEMA_VERSION,
        contractId,
        semanticRevisionId,
        semanticRevisionHash,
        apiRelease,
        steps);
  }

  static String semanticContractId(
      String semanticRevisionHash, List<DesignPlanContract.Step> steps) {
    StringBuilder canonical = new StringBuilder(Objects.requireNonNull(semanticRevisionHash));
    for (DesignPlanContract.Step step : steps) {
      canonical.append('\u0000').append(step.stepId());
      canonical
          .append('\u0000')
          .append(step.owner().kind())
          .append('\u0000')
          .append(step.owner().id());
      for (DesignPlanContract.Claim claim : step.claims()) {
        canonical
            .append('\u0000')
            .append(claim.targetKind())
            .append('\u0000')
            .append(claim.targetId())
            .append('\u0000')
            .append(claim.role());
      }
      for (String dependency : step.dependsOnStepIds()) {
        canonical.append('\u0000').append(dependency);
      }
    }
    return "plan-" + sha256(canonical.toString()).substring(0, 24);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
