package org.qubership.integration.platform.maven.plugin.mojos;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.qubership.integration.platform.maven.plugin.domain.TaskContext;
import org.qubership.integration.platform.maven.plugin.domain.TaskRunner;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTask;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildLibsTaskParameters;

import java.util.List;
import javax.inject.Inject;

import static org.qubership.integration.platform.maven.plugin.mojos.MojoConstants.PARAMETER_PROPERTY_PREFIX;

@Mojo(name = "build-libs", defaultPhase = LifecyclePhase.NONE)
public class BuildLibsMojo extends AbstractMojo {
    @Parameter(
        name = "sourceRoots",
        property = PARAMETER_PROPERTY_PREFIX + "sourceRoots",
        defaultValue = "${project.basedir}/src/main/integration"
    )
    private List<String> sourceRoots;

    @Parameter(
        name = "outputDirectory",
        property = PARAMETER_PROPERTY_PREFIX + "outputDirectory",
        defaultValue = "${project.build.directory}"
    )
    private String outputDirectory;

    @Parameter(
        name = "failFast",
        property = PARAMETER_PROPERTY_PREFIX + "failFast",
        defaultValue = "true"
    )
    private boolean failFast;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Inject
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            TaskRunner taskRunner = new TaskRunner();
            BuildLibsTask task = new BuildLibsTask();
            taskRunner.execute(task, buildTaskContext());
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to build DTO libraries", exception);
        }
    }

    private TaskContext<BuildLibsTaskParameters> buildTaskContext() {
        return TaskContext.<BuildLibsTaskParameters>builder()
            .project(project)
            .projectHelper(projectHelper)
            .taskParameters(getTaskParameters())
            .build();
    }

    private BuildLibsTaskParameters getTaskParameters() {
        return BuildLibsTaskParameters.builder()
            .sourceRoots(sourceRoots)
            .outputDirectory(outputDirectory)
            .failFast(failFast)
            .build();
    }
}
