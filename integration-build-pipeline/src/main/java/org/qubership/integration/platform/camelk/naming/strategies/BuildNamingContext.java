package org.qubership.integration.platform.camelk.naming.strategies;

import lombok.Builder;
import lombok.Getter;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;

import java.time.Instant;

@Getter
@Builder
public class BuildNamingContext {
    String id;
    Instant timestamp;
    ResourceBuildOptions options;
}
