package org.qubership.integration.platform.ai.compiler.plan;

import java.util.List;

/** Workspace manifest describing which generators are ready for execution. */
public record GeneratorPlanManifest(String packVersion, List<GeneratorPlan> plans) {}
