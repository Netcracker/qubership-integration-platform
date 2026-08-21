package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PipelineSignalLiveSinkTest {

  private static final String RUN_ID = "run-live-sink-1";

  @AfterEach
  void tearDown() {
    PipelineSignalLiveSink.unbind(RUN_ID);
  }

  @Test
  void emitIsANoOpUntilBound() {
    List<PipelineSignal> out = new ArrayList<>();
    PipelineSignalLiveSink.bind(RUN_ID, out::add);
    PipelineSignalLiveSink.unbind(RUN_ID);
    PipelineSignalLiveSink.emit(
        RUN_ID, new PipelineSignal.SkillProgress("cip-http-generator", "running"));
    assertTrue(out.isEmpty());
  }

  @Test
  void boundConsumerReceivesSkillProgress() {
    List<PipelineSignal> out = new CopyOnWriteArrayList<>();
    PipelineSignalLiveSink.bind(RUN_ID, out::add);
    PipelineSignalLiveSink.emit(
        RUN_ID, new PipelineSignal.SkillProgress("cip-http-generator", "running"));
    PipelineSignalLiveSink.emit(
        RUN_ID, new PipelineSignal.SkillProgress("cip-http-generator", "completed"));

    assertEquals(2, out.size());
    assertEquals("cip-http-generator", ((PipelineSignal.SkillProgress) out.get(0)).skillId());
    assertEquals("running", ((PipelineSignal.SkillProgress) out.get(0)).status());
  }

  @Test
  void isBoundTracksTheActiveConsumer() {
    assertFalse(PipelineSignalLiveSink.isBound(RUN_ID));
    PipelineSignalLiveSink.bind(RUN_ID, signal -> {});
    assertTrue(PipelineSignalLiveSink.isBound(RUN_ID));
    PipelineSignalLiveSink.unbind(RUN_ID);
    assertFalse(PipelineSignalLiveSink.isBound(RUN_ID));
  }
}
