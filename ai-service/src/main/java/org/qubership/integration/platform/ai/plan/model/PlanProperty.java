package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single element property on the plan graph after generator patch merges. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanProperty(String key, String value) {}
