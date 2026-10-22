package org.qubership.integration.platform.ai.skill.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory workspace store. Syncs {@link SkillArtifactType#CHAIN_PLAN_GRAPH} to {@link
 * ChainPlanStore}. Product planning writes its inputs and outputs explicitly; lazy seeding from
 * legacy bundle/validation/publication stores is removed after CREATE hard cutover.
 */
@ApplicationScoped
public class InMemorySkillWorkspaceStore {

  private final ConcurrentHashMap<String, InMemorySkillWorkspace> workspaces = new ConcurrentHashMap<>();
  private final ChainPlanStore chainPlanStore;

  @Inject
  public InMemorySkillWorkspaceStore(ChainPlanStore chainPlanStore) {
    this.chainPlanStore = chainPlanStore;
  }

  public SkillWorkspace getOrCreate(String conversationId) {
    return workspaces.computeIfAbsent(conversationId, InMemorySkillWorkspace::new);
  }

  public void clear(String conversationId) {
    workspaces.remove(conversationId);
    chainPlanStore.remove(conversationId);
  }

  /**
   * Returns skill ids that recorded at least one run before the workspace is cleared. Used when
   * resetting compiler skill chat memory.
   */
  public java.util.Set<String> completedSkillIds(String conversationId) {
    InMemorySkillWorkspace workspace = workspaces.get(conversationId);
    if (workspace == null) {
      return java.util.Set.of();
    }
    return workspace.completedSkillIds();
  }

  /** Writes an artifact and mirrors the chain plan into {@link ChainPlanStore} when applicable. */
  public void putArtifact(String conversationId, SkillArtifact artifact) {
    SkillWorkspace workspace = getOrCreate(conversationId);
    workspace.put(artifact);
    syncChainPlan(conversationId, artifact);
  }

  private void syncChainPlan(String conversationId, SkillArtifact artifact) {
    if (artifact.type() != SkillArtifactType.CHAIN_PLAN_GRAPH) {
      return;
    }
    if (!(artifact.payload() instanceof SkillArtifactPayload.ChainPlanGraphPayload payload)) {
      return;
    }
    ChainPlanGraph graph = payload.graph();
    if (graph != null) {
      chainPlanStore.put(conversationId, graph);
    }
  }

  /** Legacy bundle seeding removed after CREATE hard cutover. */
  public boolean seedImplementArtifactsFromBundle(String conversationId) {
    return false;
  }

  /** Workspace-only gate: requires an explicit chain plan graph in the workspace. */
  public boolean canRunImplementSegment(String conversationId) {
    return getOrCreate(conversationId).get(SkillArtifactType.CHAIN_PLAN_GRAPH).isPresent();
  }
}
