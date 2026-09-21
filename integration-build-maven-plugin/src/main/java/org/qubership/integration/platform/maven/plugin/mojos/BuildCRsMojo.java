package org.qubership.integration.platform.maven.plugin.mojos;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.integration.platform.maven.plugin.domain.TaskRunner;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTask;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;

import java.util.List;

@Mojo(name = "build-crs", defaultPhase = LifecyclePhase.COMPILE)
public class BuildCRsMojo extends AbstractMojo {
    @Parameter(name = "sourceRoots", defaultValue = "${project.compileSourceRoots}")
    private List<String> sourceRoots;

    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}")
    private String outputDirectory;

    @Parameter(name = "defaultDomain", defaultValue = "default")
    private String defaultDomain;

    @Parameter(name = "controlPlaneType", defaultValue = "ISTIO")
    private ControlPlaneType controlPlaneType;

    @Parameter(name = "defaultSecretEnabled", defaultValue = "false")
    private boolean defaultSecretEnabled;

    @Parameter(name = "options")
    private BuildCRsOptions options = new BuildCRsOptions();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            TaskRunner taskRunner = new TaskRunner();
            BuildCRsTask task = new BuildCRsTask();
            taskRunner.execute(task, getTaskParameters());
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to build K8s resources", exception);
        }
    }

    private BuildCRsTaskParameters getTaskParameters() {
        return BuildCRsTaskParameters.builder()
            .sourceRoots(sourceRoots)
            .outputDirectory(outputDirectory)
            .defaultDomain(defaultDomain)
            .controlPlaneType(controlPlaneType)
            .options(options)
            .build();
    }
}
