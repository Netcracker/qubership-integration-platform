package org.qubership.integration.platform.ai.plan.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Compiler-added structure separate from the user's core business flow. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanCompilerAddition(String kind, String description, List<String> nodeTypes) {}
