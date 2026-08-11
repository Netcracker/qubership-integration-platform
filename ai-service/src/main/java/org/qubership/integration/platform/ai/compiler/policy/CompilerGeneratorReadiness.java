package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;

/** Machine-readable readiness metadata for a generator skill in ai-service. */
public record CompilerGeneratorReadiness(String mode, List<String> signals) {}
