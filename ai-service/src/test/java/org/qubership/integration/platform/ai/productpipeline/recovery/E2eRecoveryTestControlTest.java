package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.HttpTriggerCapture;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;

class E2eRecoveryTestControlTest {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void disabledAndForeignConversationControlsAreInert() throws Exception {
    var proposal = new HttpTriggerCapture(List.of(new HttpTriggerCapture.Endpoint("role", "node", false)));
    assertSame(proposal, new E2eRecoveryTestControl((Path) null, mapper).httpCapture("owned", "trigger", proposal));
    writePlan("checkpoint");
    var control = new E2eRecoveryTestControl(directory, mapper);
    assertSame(proposal, control.httpCapture("foreign", "trigger", proposal));
    assertFalse(control.materializationInputsAccepted(context("foreign"), ref(), List.of(ref())));
    assertFalse(Files.exists(directory.resolve("events")));
  }

  @Test void onlyOneReferenceIsChangedAndFullRunsContinue() throws Exception {
    writePlan("completion");
    var control = new E2eRecoveryTestControl(directory, mapper);
    var proposal = new HttpTriggerCapture(List.of(new HttpTriggerCapture.Endpoint("role", "node", false)));
    var changed = control.httpCapture("owned", "trigger", proposal);
    assertEquals("role", changed.endpoints().getFirst().roleId());
    assertEquals(false, changed.endpoints().getFirst().externalRoute());
    assertFalse(changed.endpoints().getFirst().semanticNodeId().equals("node"));
    assertSame(proposal, control.httpCapture("owned", "trigger", proposal));
    assertFalse(control.materializationInputsAccepted(context("owned"), ref(), List.of(ref())));
    assertTrue(Files.exists(directory.resolve("checkpoint.json")));
  }

  private void writePlan(String boundary) throws Exception {
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of("schemaVersion", 1,
        "conversationId", "owned", "boundary", boundary, "mutation", "http-semantic-reference"));
  }

  private static Reference ref() { return new Reference(Kind.CHAIN_PLAN_GRAPH, "graph", "hash"); }
  private static StageExecutionContext context(String conversation) {
    return new StageExecutionContext("run", conversation, "materialization", "key", "attempt",
        null, null, List.of(ref()), Map.of());
  }
}
