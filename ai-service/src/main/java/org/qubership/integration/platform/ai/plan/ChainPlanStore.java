package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * Per-conversation chain plan access. Durable revisions are the authority when compilation stores
 * are wired; the in-memory map is a projection for fast orchestration reads.
 */
@ApplicationScoped
public class ChainPlanStore {

  private final ConcurrentHashMap<String, ChainPlanGraph> projection = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> updatedAt = new ConcurrentHashMap<>();
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;

  @Inject
  public ChainPlanStore(CompilationArtifacts artifacts, CompilationSessions sessions) {
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  public ChainPlanStore() {
    this.artifacts = null;
    this.sessions = null;
  }

  public void put(String conversationId, ChainPlanGraph graph) {
    put(conversationId, graph, "chain-plan-store", null);
  }

  public Optional<Revision> put(
      String conversationId, ChainPlanGraph graph, String producerId, String producerVersion) {
    Objects.requireNonNull(graph, "graph");
    if (!durable()) {
      project(conversationId, graph);
      return Optional.empty();
    }

    String compilationId = sessions.active(conversationId);
    String revisesArtifactId =
        artifacts
            .latest(compilationId, Kind.CHAIN_PLAN_GRAPH)
            .map(Revision::artifactId)
            .orElse(null);

    Revision revision =
        artifacts.append(
            new AppendCommand(
                compilationId,
                Kind.CHAIN_PLAN_GRAPH,
                graph.schemaVersion() != null ? graph.schemaVersion() : "1.0",
                producerId != null ? producerId : "chain-plan-store",
                producerVersion,
                new ChainPlanGraphArtifact(graph),
                List.of(),
                revisesArtifactId));
    project(conversationId, graph);
    return Optional.of(revision);
  }

  /** Updates the in-memory projection without appending a durable revision. */
  public void project(String conversationId, ChainPlanGraph graph) {
    Objects.requireNonNull(graph, "graph");
    projection.put(conversationId, graph);
    updatedAt.put(conversationId, Instant.now());
  }

  public Optional<ChainPlanGraph> get(String conversationId) {
    if (durable()) {
      return latestCurrentRevision(conversationId)
          .map(revision -> artifacts.payload(revision, ChainPlanGraphArtifact.class).graph())
          .or(() -> Optional.ofNullable(projection.get(conversationId)));
    }
    return Optional.ofNullable(projection.get(conversationId));
  }

  public Optional<Instant> updatedAt(String conversationId) {
    return Optional.ofNullable(updatedAt.get(conversationId));
  }

  public Optional<Revision> latestRevision(String conversationId) {
    if (!durable()) {
      return Optional.empty();
    }
    return sessions
        .current(conversationId)
        .flatMap(link -> artifacts.latest(link.compilationId(), Kind.CHAIN_PLAN_GRAPH));
  }

  public Optional<Revision> latestCurrentRevision(String conversationId) {
    return latestRevision(conversationId)
        .filter(revision -> !isStale(conversationId, revision.reference()));
  }

  public boolean isStale(String conversationId, Reference target) {
    if (!durable() || target == null || target.kind() != Kind.CHAIN_PLAN_GRAPH) {
      return true;
    }
    Optional<String> compilationId =
        sessions.current(conversationId).map(link -> link.compilationId());
    if (compilationId.isEmpty()) {
      return true;
    }
    if (artifacts.get(compilationId.get(), target).isEmpty()) {
      return true;
    }
    Optional<Revision> latestGraph =
        artifacts.latest(compilationId.get(), Kind.CHAIN_PLAN_GRAPH);
    return latestGraph.isEmpty()
        || !latestGraph.orElseThrow().artifactId().equals(target.artifactId());
  }

  public void remove(String conversationId) {
    projection.remove(conversationId);
    updatedAt.remove(conversationId);
  }

  private boolean durable() {
    return artifacts != null && sessions != null;
  }
}
