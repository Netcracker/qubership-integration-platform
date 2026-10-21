package org.qubership.integration.platform.ai.productpipeline.packageindex;

/** One hashed pin in a product-pipeline dependency closure. */
public record DependencyPin(
    String dependencyId,
    String kind,
    String path,
    String sha256,
    ReferenceDisposition disposition,
    String adaptationReason) {}
