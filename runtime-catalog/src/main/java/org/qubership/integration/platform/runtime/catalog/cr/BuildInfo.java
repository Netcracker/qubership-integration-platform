package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.Builder;
import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;

import java.time.Instant;

@Getter
@Builder
public class BuildInfo {
    private String id;
    private Instant timestamp;
    private String name;
    private ResourceBuildOptions options;
}
