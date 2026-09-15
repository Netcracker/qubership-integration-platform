package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;

/** Prepared child state that is safe to activate with a conversation-pointer CAS. */
public record PreparedRestart(ProductPipelineRunDocument document, RunManifest runManifest) {}
