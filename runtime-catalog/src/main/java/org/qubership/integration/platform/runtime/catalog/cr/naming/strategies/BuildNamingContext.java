package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import lombok.Builder;
import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.cr.model.options.ResourceBuildOptions;

import java.time.Instant;

@Getter
@Builder
public class BuildNamingContext {
    String id;
    Instant timestamp;
    ResourceBuildOptions options;
}
