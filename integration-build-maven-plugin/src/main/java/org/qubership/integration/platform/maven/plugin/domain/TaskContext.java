package org.qubership.integration.platform.maven.plugin.domain;

import lombok.Builder;
import lombok.Data;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

@Data
@Builder(toBuilder = true)
public class TaskContext<T> {
    MavenProject project;
    MavenProjectHelper projectHelper;
    T taskParameters;
}
