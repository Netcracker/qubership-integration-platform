package org.qubership.integration.platform.ai.compiler.capture.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;

/** Per-conversation soft-credit ledger keyed by fingerprint identity (ADR 0005). */
@ApplicationScoped
public class ToolCallFingerprintStore {

  private final ObjectMapper objectMapper;
  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final ConcurrentHashMap<String, Set<String>> softConsumedByConversation =
      new ConcurrentHashMap<>();

  @Inject
  public ToolCallFingerprintStore(ObjectMapper objectMapper,
      CompilationArtifacts artifacts, CompilationSessions sessions) {
    this.objectMapper = objectMapper;
    this.artifacts = artifacts;
    this.sessions = sessions;
  }

  public ToolCallFingerprintStore(ObjectMapper objectMapper) {
    this(objectMapper, null, null);
  }

  /** Test helper without CDI. */
  public ToolCallFingerprintStore() {
    this(new ObjectMapper());
  }

  public String fingerprint(String tool, String capability, Object args) {
    return ToolCallFingerprints.fingerprint(objectMapper, tool, capability, args);
  }

  /** Soft-budget key for a rejection, so a reworded payload does not buy a fresh credit. */
  public String failureFingerprint(String tool, String capability, String message) {
    return ToolCallFingerprints.failureFingerprint(tool, capability, message);
  }

  public boolean softCreditUsed(String conversationId, String fingerprint) {
    if (conversationId == null || conversationId.isBlank() || fingerprint == null) {
      return false;
    }
    return softFingerprints(conversationId).contains(fingerprint);
  }

  public void consumeSoftCredit(String conversationId, String fingerprint) {
    if (conversationId == null || conversationId.isBlank() || fingerprint == null) {
      return;
    }
    consumeSoftCredits(conversationId, List.of(fingerprint));
  }

  public synchronized void consumeSoftCredits(String conversationId, Collection<String> fingerprints) {
    if (conversationId == null || conversationId.isBlank() || fingerprints.isEmpty()) {
      return;
    }
    if (artifacts == null) {
      softConsumedByConversation.computeIfAbsent(conversationId, ignored -> ConcurrentHashMap.newKeySet())
          .addAll(fingerprints);
      return;
    }
    Set<String> consumed = new HashSet<>(softFingerprints(conversationId));
    if (consumed.addAll(fingerprints)) {
      saveCredits(conversationId, consumed);
    }
  }

  public int softCreditsUsed(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return 0;
    }
    return softFingerprints(conversationId).size();
  }

  public Set<String> softFingerprints(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return Set.of();
    }
    if (artifacts != null) {
      return sessions.current(conversationId)
          .flatMap(link -> artifacts.latest(link.compilationId(), Kind.CAPTURE_CREDITS))
          .map(revision -> Set.copyOf(artifacts.payload(revision, CaptureCredits.class).fingerprints()))
          .orElse(Set.of());
    }
    Set<String> consumed = softConsumedByConversation.get(conversationId);
    return consumed == null ? Set.of() : Collections.unmodifiableSet(consumed);
  }

  public synchronized void clear(String conversationId) {
    if (conversationId != null && !conversationId.isBlank()) {
      if (artifacts != null && !softFingerprints(conversationId).isEmpty()) {
        saveCredits(conversationId, Set.of());
      }
      softConsumedByConversation.remove(conversationId);
    }
  }

  private void saveCredits(String conversationId, Set<String> fingerprints) {
    String compilationId = sessions.active(conversationId);
    var previous = artifacts.latest(compilationId, Kind.CAPTURE_CREDITS);
    artifacts.append(new AppendCommand(compilationId, Kind.CAPTURE_CREDITS, "1",
        "capture-credit-store", "1", new CaptureCredits(fingerprints.stream().sorted().toList()),
        List.of(), previous.map(Revision::artifactId).orElse(null)));
  }

  public record CaptureCredits(List<String> fingerprints) {
    public CaptureCredits {
      fingerprints = List.copyOf(fingerprints);
    }
  }
}
