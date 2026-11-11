package org.qubership.integration.platform.ai.integration.catalog.materialize;

/** One catalog parent move that completed during EDIT materialization. */
public record CompletedTransfer(String catalogElementId, String previousParentCatalogId) {}
