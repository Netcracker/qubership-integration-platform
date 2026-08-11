package org.qubership.integration.platform.ai.chat.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;

class EvidenceEmitterTest {

  private ConversationEvidenceStore store;
  private EvidenceEmitter emitter;

  @BeforeEach
  void setUp() {
    store = new ConversationEvidenceStore();
    emitter = new EvidenceEmitter(store);
  }

  @Test
  void pipeline_recordsBareTimelineAndEmitsPrefixedStep() {
    List<ChatEvent> emitted = new ArrayList<>();
    emitter.pipeline("c1", "compile", "running", emitted::add);

    assertEquals(1, emitted.size());
    ChatEvent.Step step = (ChatEvent.Step) emitted.get(0);
    assertEquals("pipeline:compile", step.id());
    assertEquals("pipeline", step.kind());
    assertEquals("running", step.status());
    assertEquals("compile", step.label());
    assertNull(step.parentId());

    EvidenceSnapshot snap = store.getOrCreate("c1").toSnapshot("c1");
    assertEquals(1, snap.timeline().size());
    assertEquals("pipeline", snap.timeline().get(0).kind());
    assertEquals("compile", snap.timeline().get(0).id());
    assertEquals("running", snap.timeline().get(0).status());
    assertNull(snap.timeline().get(0).parentId());
  }

  @Test
  void skill_recordsBareParentAndEmitsWireParentId() {
    List<ChatEvent> emitted = new ArrayList<>();
    emitter.skill("c1", "cip-trigger-generator", "completed", "compile", emitted::add);

    assertEquals(1, emitted.size());
    ChatEvent.Step step = (ChatEvent.Step) emitted.get(0);
    assertEquals("skill:cip-trigger-generator", step.id());
    assertEquals("skill", step.kind());
    assertEquals("completed", step.status());
    assertEquals("cip-trigger-generator", step.label());
    assertEquals("pipeline:compile", step.parentId());

    EvidenceSnapshot.TimelineEntry entry = store.getOrCreate("c1").toSnapshot("c1").timeline().get(0);
    assertEquals("skill", entry.kind());
    assertEquals("cip-trigger-generator", entry.id());
    assertEquals("compile", entry.parentId());
  }

  @Test
  void skill_withoutParent_emitsNullParentWire() {
    List<ChatEvent> emitted = new ArrayList<>();
    emitter.skill("c1", "cip-auth-generator", "error", null, emitted::add);

    ChatEvent.Step step = (ChatEvent.Step) emitted.get(0);
    assertEquals("error", step.status());
    assertNull(step.parentId());
  }

  @Test
  void prependStep_concatenatesRunningStepBeforeTail() {
    List<ChatEvent> events =
        emitter
            .prependStep(
                ChatEvent.step("pipeline:gather", "pipeline", "running", "gather", null),
                Multi.createFrom().item(ChatEvent.token("tail")))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(2, events.size());
    assertEquals("running", ((ChatEvent.Step) events.get(0)).status());
    assertInstanceOf(ChatEvent.Token.class, events.get(1));
  }

  @Test
  void pipelineStep_recordsAndReturnsMatchingStep() {
    ChatEvent step = emitter.pipelineStep("c1", "gather", "completed");

    assertEquals("pipeline:gather", ((ChatEvent.Step) step).id());
    assertEquals("completed", ((ChatEvent.Step) step).status());
    assertEquals(
        "completed", store.getOrCreate("c1").toSnapshot("c1").timeline().get(0).status());
  }

  @Test
  void knowledgeRecordsPackageIdsAndCharsWithoutBodies() {
    KnowledgePackageRef ref =
        new KnowledgePackageRef(
            "fixture@1.0.0",
            "1.0.0",
            "1.0.0",
            "sha256:package",
            "CERTIFIED",
            "sha256:certificate");

    emitter.knowledge(
        "c1",
        ref,
        List.of("CIP:GEN-000049", "CIP:RULE-000001"),
        321);

    EvidenceSnapshot.Knowledge knowledge =
        store.getOrCreate("c1").toSnapshot("c1").knowledge();
    assertEquals(ref, knowledge.packageRef());
    assertEquals(
        List.of("CIP:GEN-000049", "CIP:RULE-000001"),
        knowledge.objectIds());
    assertEquals(321, knowledge.contentChars());
  }

  @Test
  void emitConsumerFailure_doesNotThrow() {
    assertDoesNotThrow(
        () ->
            emitter.pipeline(
                "c1",
                "gather",
                "running",
                ignored -> {
                  throw new RuntimeException("sse emit failed");
                }));
    assertEquals(1, store.getOrCreate("c1").toSnapshot("c1").timeline().size());
  }
}
