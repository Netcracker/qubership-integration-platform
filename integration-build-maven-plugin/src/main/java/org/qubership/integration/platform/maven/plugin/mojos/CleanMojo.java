package org.qubership.integration.platform.maven.plugin.mojos;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.qubership.integration.platform.maven.plugin.domain.util.GeneratedFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.qubership.integration.platform.maven.plugin.mojos.MojoConstants.PARAMETER_PROPERTY_PREFIX;

@Mojo(name = "clean", defaultPhase = LifecyclePhase.CLEAN)
public class CleanMojo extends AbstractMojo {
    @Parameter(
        name = "outputDirectory",
        property = PARAMETER_PROPERTY_PREFIX + "outputDirectory",
        defaultValue = "${project.build.directory}"
    )
    private String outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<Path> deleted = GeneratedFiles.deleteRecorded(Path.of(outputDirectory), project.getArtifactId());
            if (deleted.isEmpty()) {
                getLog().debug("No generated files to delete in '" + outputDirectory + "'.");
            }
            deleted.forEach(file -> getLog().info("Deleted " + file));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clean generated files", e);
        }
    }
}
