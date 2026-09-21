package org.qubership.integration.platform.maven.plugin.domain.tasks;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;

@Data
@Builder
public class BuildLibsTaskParameters {
    Collection<String> sourceRoots;
    String outputDirectory;
    // TODO
}
