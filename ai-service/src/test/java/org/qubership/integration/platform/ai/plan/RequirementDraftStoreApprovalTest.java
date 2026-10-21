package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;

class RequirementDraftStoreApprovalTest {

  @Test
  void approvalSucceedsForExactCurrentDraft() {
    TestRuntime runtime = runtime(new InMemoryArtifactBlobStore());
    runtime.store().put("conversation-1", RequirementFactFixtures.readyDraft("ready draft"));
    Revision draftRevision = runtime.store().latestRevision("conversation-1").orElseThrow();

    runtime.store().approve("conversation-1", draftRevision.reference(), "user-1", null);

    assertTrue(runtime.store().isApproved("conversation-1", draftRevision.reference()));
  }

  @Test
  void newDraftRevisionDoesNotInheritEarlierApproval() {
    TestRuntime runtime = runtime(new InMemoryArtifactBlobStore());
    runtime.store().put("conversation-1", RequirementFactFixtures.readyDraft("draft one"));
    Revision draftOne = runtime.store().latestRevision("conversation-1").orElseThrow();
    runtime.store().approve("conversation-1", draftOne.reference(), "user-1", null);

    runtime.store().put("conversation-1", RequirementFactFixtures.readyDraft("draft two"));
    Revision draftTwo = runtime.store().latestRevision("conversation-1").orElseThrow();

    assertTrue(runtime.store().isApproved("conversation-1", draftOne.reference()));
    assertFalse(runtime.store().isApproved("conversation-1", draftTwo.reference()));
  }

  @Test
  void approvingOlderDraftFailsAsStale() {
    TestRuntime runtime = runtime(new InMemoryArtifactBlobStore());
    runtime.store().put("conversation-1", RequirementFactFixtures.readyDraft("draft one"));
    Revision draftOne = runtime.store().latestRevision("conversation-1").orElseThrow();
    runtime.store().put("conversation-1", RequirementFactFixtures.readyDraft("draft two"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                runtime
                    .store()
                    .approve("conversation-1", draftOne.reference(), "user-1", null));
    assertEquals("requirement draft is stale", error.getMessage());
  }

  private static TestRuntime runtime(InMemoryArtifactBlobStore blobStore) {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.systemUTC());
    CompilationSessions sessions =
        new CompilationSessions(blobStore, mapper, Clock.systemUTC());
    return new TestRuntime(new RequirementDraftStore(artifacts, sessions));
  }

  private record TestRuntime(RequirementDraftStore store) {}
}
