package org.qubership.integration.platform.runtime.catalog.cr.sources;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
@Builder
public class SourceBuilderContext {
    private String domainName;
    private String buildName;
    private Instant buildTimestamp;
}
