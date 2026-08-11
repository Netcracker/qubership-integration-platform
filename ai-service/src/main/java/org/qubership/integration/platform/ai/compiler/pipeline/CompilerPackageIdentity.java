package org.qubership.integration.platform.ai.compiler.pipeline;

/** Identity of the compiled compiler package that owns a pipeline index. */
public record CompilerPackageIdentity(
    String packageId, String packageVersion, String packageDigest) {}
