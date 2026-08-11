package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Directed dependency between catalog elements. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainCatalogDependency(String fromElementId, String toElementId) {}
