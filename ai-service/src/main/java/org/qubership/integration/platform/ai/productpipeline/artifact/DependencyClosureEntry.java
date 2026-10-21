package org.qubership.integration.platform.ai.productpipeline.artifact;

/** One capability entry in a pinned dependency closure. */
public record DependencyClosureEntry(String capabilityId, String version, String digest) {}
