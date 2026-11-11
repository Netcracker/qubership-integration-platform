package org.qubership.integration.platform.ai.productpipeline.capability;

import io.smallrye.mutiny.Multi;

/** Profile-neutral executable bound to one capability ID. */
public interface StageCapability {

  String capabilityId();

  Multi<CapabilitySignal> execute(StageExecutionContext context);
}
