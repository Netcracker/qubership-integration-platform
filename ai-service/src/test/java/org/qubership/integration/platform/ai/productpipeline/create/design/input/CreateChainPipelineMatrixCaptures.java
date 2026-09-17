package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedOperation;

/** Minimal capture shapes for create-chain trigger and sender pipeline matrix tests. */
public final class CreateChainPipelineMatrixCaptures {

  private CreateChainPipelineMatrixCaptures() {}

  public static ChainSemanticCapture directTriggerWithScript(
      String entryPointId, String scriptFactId) {
    return new ChainSemanticCapture(
        "chain-matrix",
        List.of(new CapturedOperation("op-script", "script", List.of(scriptFactId))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new CapturedEdge(entryPointId, "op-script", null, null, null, null, null, null)),
        List.of());
  }

  public static ChainSemanticCapture directSenderRelay(String inboundId, String outboundId) {
    return new ChainSemanticCapture(
        "chain-relay",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new CapturedEdge(inboundId, outboundId, null, null, null, null, null, null)),
        List.of());
  }
}
