package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;

/**
 * Closed owner set for a failed stage: the stage itself plus producers of its consumed types.
 * Deepen once to those producers' producers when the first layer cannot pick an owner.
 */
public final class OwnerCandidateSet {

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
