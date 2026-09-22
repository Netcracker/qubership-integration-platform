package org.qubership.integration.platform.maven.plugin.mojos;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.integration.platform.maven.plugin.domain.TaskRunner;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTask;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;

import java.util.List;

@Mojo(name = "build-libs", defaultPhase = LifecyclePhase.COMPILE)
public class BuildLibsMojo extends AbstractMojo {
    @Parameter(name = "sourceRoots", defaultValue = "${project.compileSourceRoots}")
    private List<String> sourceRoots;

    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}")
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            TaskRunner taskRunner = new TaskRunner();
            BuildLibsTask task = new BuildLibsTask();
            taskRunner.execute(task, getTaskParameters());
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to build DTO libraries", exception);
        }
    }

    private BuildLibsTaskParameters getTaskParameters() {
        return BuildLibsTaskParameters.builder()
            .sourceRoots(sourceRoots)
            .outputDirectory(outputDirectory)
            .build();
    }
}
