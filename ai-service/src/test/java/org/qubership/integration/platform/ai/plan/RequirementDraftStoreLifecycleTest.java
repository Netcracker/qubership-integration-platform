package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

class RequirementDraftStoreLifecycleTest {

  @Test
  void resetStartsNewCompilationWithoutReusingItsDraft() {
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    TestRuntime runtime = runtime(blobStore);
    CompilationArtifacts artifacts = runtime.artifacts();
    CompilationSessions sessions = runtime.sessions();
    RequirementDraftStore store = runtime.store();
    store.put("conversation-1", new RequirementDraft(false, "first draft"));
    String firstCompilationId = store.activeCompilationId("conversation-1");

    store.remove("conversation-1");

    String secondCompilationId = store.activeCompilationId("conversation-1");
    assertNotEquals(firstCompilationId, secondCompilationId);
    assertTrue(store.get("conversation-1").isEmpty());
    assertEquals(1, artifacts.history(firstCompilationId, Kind.REQUIREMENT_DRAFT).size());
    assertEquals(2, sessions.history("conversation-1").size());

    sessions.activate("conversation-1", firstCompilationId);
    assertEquals("first draft", store.get("conversation-1").orElseThrow().assembledText());
  }

  @Test
  void updatesProduceReadableDraftRevisions() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put("conversation-1", new RequirementDraft(false, "first draft"));
    store.put("conversation-1", new RequirementDraft(true, "second draft"));

    RequirementDraft latest = store.get("conversation-1").orElseThrow();

    assertEquals("second draft", latest.assembledText());
    assertTrue(latest.complete());
  }

  @Test
  void reconstructingStoreRecoversActiveDraft() {
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    runtime(blobStore).store().put("conversation-1", new RequirementDraft(true, "saved draft"));

    RequirementDraft recovered = runtime(blobStore).store().get("conversation-1").orElseThrow();

    assertEquals("saved draft", recovered.assembledText());
  }

  private static TestRuntime runtime(InMemoryArtifactBlobStore blobStore) {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.systemUTC());
    CompilationSessions sessions =
        new CompilationSessions(blobStore, mapper, Clock.systemUTC());
    return new TestRuntime(
        artifacts, sessions, new RequirementDraftStore(artifacts, sessions));
  }

  private record TestRuntime(
      CompilationArtifacts artifacts,
      CompilationSessions sessions,
      RequirementDraftStore store) {}
}
