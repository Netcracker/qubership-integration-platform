package org.qubership.integration.platform.camelk.model;

import lombok.Builder;
import lombok.Getter;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;

import java.time.Instant;

@Getter
@Builder
public class BuildInfo {
    private String id;
    private Instant timestamp;
    private String name;
    private ResourceBuildOptions options;
}
