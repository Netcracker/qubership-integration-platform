package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.List;

/** Request for one compiled runtime context package from the sidecar. */
public record KnowledgeContextRequest(
    String requestText,
    String capabilityId,
    String phase,
    List<String> elementTypes,
    int maxObjects,
    int maxChars) {}
