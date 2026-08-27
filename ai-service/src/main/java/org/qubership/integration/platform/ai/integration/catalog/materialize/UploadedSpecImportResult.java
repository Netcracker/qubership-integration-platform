package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;

public record UploadedSpecImportResult(String s3Key, ResolvedCatalogBinding binding) {}
