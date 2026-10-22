package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

/** Payload for chain presentation after implement or during explain. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainImplementationPresentationFacts(
    String userRequest,
    String userQuestion,
    ChainCatalogFacts catalogFacts,
    ReconcileResult reconcileResult) {}
