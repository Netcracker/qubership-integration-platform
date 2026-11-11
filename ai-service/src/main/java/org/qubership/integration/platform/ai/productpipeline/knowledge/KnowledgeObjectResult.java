package org.qubership.integration.platform.ai.productpipeline.knowledge;

/** One knowledge object plus response identity. */
public record KnowledgeObjectResult(
    KnowledgeResponseIdentity identity, CanonicalKnowledgeObject object) {}
