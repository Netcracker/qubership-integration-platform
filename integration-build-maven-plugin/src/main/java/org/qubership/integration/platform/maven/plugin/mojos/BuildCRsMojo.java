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
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTask;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;

import java.util.List;
import javax.inject.Inject;

import static org.qubership.integration.platform.maven.plugin.mojos.MojoConstants.PARAMETER_PROPERTY_PREFIX;

@Mojo(name = "build-crs", defaultPhase = LifecyclePhase.COMPILE)
public class BuildCRsMojo extends AbstractMojo {
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

    @Parameter(
        name = "defaultDomain",
        property = PARAMETER_PROPERTY_PREFIX + "defaultDomain",
        defaultValue = "me-domain"
    )
    private String defaultDomain;

    @Parameter(
        name = "deployAll",
        property = PARAMETER_PROPERTY_PREFIX + "deployAll",
        defaultValue = "false"
    )
    private boolean deployAll;

    @Parameter(
        name = "controlPlaneType",
        property = PARAMETER_PROPERTY_PREFIX + "controlPlaneType",
        defaultValue = "ISTIO"
    )
    private ControlPlaneType controlPlaneType;

    @Parameter(
        name = "defaultSecretEnabled",
        property = PARAMETER_PROPERTY_PREFIX + "defaultSecretEnabled",
        defaultValue = "false"
    )
    private boolean defaultSecretEnabled;

    @Parameter(name = "options")
    private BuildCRsOptions options = new BuildCRsOptions();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Inject
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            TaskRunner taskRunner = new TaskRunner();
            BuildCRsTask task = new BuildCRsTask();
            taskRunner.execute(task, buildTaskContext());
        } catch (Exception exception) {
            throw new MojoExecutionException("Failed to build K8s resources", exception);
        }
    }

    private TaskContext<BuildCRsTaskParameters> buildTaskContext() {
        return TaskContext.<BuildCRsTaskParameters>builder()
            .project(project)
            .projectHelper(projectHelper)
            .taskParameters(getTaskParameters())
            .build();
    }

    private BuildCRsTaskParameters getTaskParameters() {
        return BuildCRsTaskParameters.builder()
            .sourceRoots(sourceRoots)
            .outputDirectory(outputDirectory)
            .failFast(failFast)
            .defaultDomain(defaultDomain)
            .deployAll(deployAll)
            .controlPlaneType(controlPlaneType)
            .defaultSecretEnabled(defaultSecretEnabled)
            .options(options)
            .build();
    }
}
