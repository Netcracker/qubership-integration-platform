package org.qubership.integration.platform.maven.plugin.domain.tasks;

import lombok.Builder;
import lombok.Data;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;

import java.util.Collection;

@Data
@Builder
public class BuildCRsTaskParameters {
    Collection<String> sourceRoots;
    String outputDirectory;
    String defaultDomain;
    ControlPlaneType controlPlaneType;
    boolean defaultSecretEnabled;
    BuildCRsOptions options;
}
