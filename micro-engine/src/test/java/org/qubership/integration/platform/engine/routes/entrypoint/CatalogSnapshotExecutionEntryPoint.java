package org.qubership.integration.platform.engine.routes.entrypoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.routes.entrypoint.bundle.SnapshotBundle;
import org.qubership.integration.platform.engine.routes.entrypoint.bundle.SnapshotBundleEntry;
import org.qubership.integration.platform.engine.routes.entrypoint.bundle.SnapshotBundleReader;
import org.qubership.integration.platform.engine.routes.entrypoint.bundle.SnapshotYamlMappers;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotDeployment;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionPlan;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionTarget;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class CatalogSnapshotExecutionEntryPoint {
    public static final String CONTRACT_DIR_PROPERTY = "route.contract.dir";

    private static final Path DEFAULT_CONTRACT_DIR = Path.of("target", "snapshotbundle");
    private static final String DEFAULT_TEST_SPECIFICATIONS_DIRECTORY_RESOURCE = "/testspecifications";
    private static final String YAML_FILE_EXTENSION = ".yml";

    private final Path bundleDirectory;
    private final Path testSpecificationsDirectory;
    private final ObjectMapper objectMapper;
    private final SnapshotBundleReader bundleReader;

    public CatalogSnapshotExecutionEntryPoint() {
        this(
                contractDirFromSystemProperty(),
                null,
                SnapshotYamlMappers.create()
        );
    }

    CatalogSnapshotExecutionEntryPoint(
            Path bundleDirectory,
            Path testSpecificationsDirectory,
            ObjectMapper objectMapper
    ) {
        this.bundleDirectory = Objects.requireNonNull(bundleDirectory, "bundleDirectory")
                .toAbsolutePath()
                .normalize();
        this.testSpecificationsDirectory = testSpecificationsDirectory == null
                ? null
                : testSpecificationsDirectory.toAbsolutePath().normalize();
        this.objectMapper = SnapshotYamlMappers.strictCopy(objectMapper);
        this.bundleReader = new SnapshotBundleReader(this.objectMapper);
    }

    public SnapshotExecutionPlan buildExecutionPlan() throws IOException {
        SnapshotBundle bundle = bundleReader.read(bundleDirectory);
        List<SnapshotExecutionTarget> targets = new ArrayList<>();
        for (Path testSpecificationPath : findTestSpecificationPaths()) {
            targets.add(loadTarget(bundle, testSpecificationPath));
        }
        return new SnapshotExecutionPlan(targets);
    }

    private SnapshotExecutionTarget loadTarget(
            SnapshotBundle bundle,
            Path testSpecificationPath
    ) throws IOException {
        String testSpecificationFileName = testSpecificationPath.getFileName().toString();
        String targetId = testSpecificationFileName.substring(
                0,
                testSpecificationFileName.length() - YAML_FILE_EXTENSION.length()
        );

        try (InputStream inputStream = Files.newInputStream(testSpecificationPath)) {
            SnapshotTestSpecification testSpecification = objectMapper.readValue(
                    inputStream,
                    SnapshotTestSpecification.class
            );
            List<SnapshotDeployment> deployments = resolveDeployments(
                    bundle,
                    targetId,
                    testSpecificationFileName,
                    testSpecification
            );
            try {
                return new SnapshotExecutionTarget(
                        targetId,
                        resolveSubjectDeploymentId(targetId, testSpecificationFileName, testSpecification),
                        deployments,
                        testSpecification.getResources(),
                        testSpecification.getFixtures(),
                        testSpecification.getScenarios()
                );
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Catalog snapshot test specification '" + testSpecificationFileName
                                + "' is invalid: " + exception.getMessage(),
                        exception
                );
            }
        }
    }

    private static String resolveSubjectDeploymentId(
            String targetId,
            String testSpecificationFileName,
            SnapshotTestSpecification testSpecification
    ) throws IOException {
        if (testSpecification.getDeployments().isEmpty()) {
            if (testSpecification.getSubject() != null && !testSpecification.getSubject().isBlank()) {
                throw new IOException(
                        "Catalog snapshot test specification '" + testSpecificationFileName
                                + "' defines a subject deployment without explicit deployments."
                );
            }
            return targetId;
        }
        if (testSpecification.getSubject() == null || testSpecification.getSubject().isBlank()) {
            throw new IOException(
                    "Catalog snapshot test specification '" + testSpecificationFileName
                            + "' does not define a subject deployment."
            );
        }
        return testSpecification.getSubject();
    }

    private static List<SnapshotDeployment> resolveDeployments(
            SnapshotBundle bundle,
            String targetId,
            String testSpecificationFileName,
            SnapshotTestSpecification testSpecification
    ) throws IOException {
        if (testSpecification.getDeployments().isEmpty()) {
            return List.of(resolveDeployment(
                    bundle,
                    targetId,
                    targetId,
                    List.of(),
                    testSpecificationFileName
            ));
        }

        List<SnapshotDeployment> deployments = new ArrayList<>();
        for (SnapshotDeploymentDefinition definition : testSpecification.getDeployments()) {
            if (definition == null) {
                throw new IOException(
                        "Catalog snapshot test specification '" + testSpecificationFileName
                                + "' contains a null deployment."
                );
            }
            deployments.add(resolveDeployment(
                    bundle,
                    definition.getId(),
                    definition.getRoute(),
                    definition.getDependsOn(),
                    testSpecificationFileName
            ));
        }
        return deployments;
    }

    private static SnapshotDeployment resolveDeployment(
            SnapshotBundle bundle,
            String deploymentId,
            String chainName,
            List<String> dependencyIds,
            String testSpecificationFileName
    ) throws IOException {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IOException(
                    "Catalog snapshot test specification '" + testSpecificationFileName
                            + "' contains a deployment without an id."
            );
        }
        if (chainName == null || chainName.isBlank()) {
            throw new IOException(
                    "Catalog snapshot deployment '" + deploymentId
                            + "' does not define a route in test specification '"
                            + testSpecificationFileName + "'."
            );
        }

        SnapshotBundleEntry bundleEntry = bundle.findByChainName(chainName)
                .orElseThrow(() -> new IOException(
                        "Snapshot bundle does not contain chain '" + chainName
                                + "' required by deployment '" + deploymentId
                                + "' in test specification '" + testSpecificationFileName + "'."
                ));
        return new SnapshotDeployment(
                deploymentId,
                bundleEntry.snapshotFile().toUri().toString(),
                dependencyIds,
                bundleEntry.snapshotNodeIdsBySourceElementId()
        );
    }

    private List<Path> findTestSpecificationPaths() throws IOException {
        Path directory = testSpecificationsDirectory == null
                ? resolveClasspathDirectory()
                : testSpecificationsDirectory;
        if (!Files.isDirectory(directory)) {
            throw new IOException(
                    "Catalog snapshot test specification directory is missing: " + directory
            );
        }

        List<Path> testSpecificationPaths;
        try (Stream<Path> paths = Files.walk(directory)) {
            testSpecificationPaths = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(YAML_FILE_EXTENSION))
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .toList();
        }
        if (testSpecificationPaths.isEmpty()) {
            throw new IOException(
                    "Catalog snapshot test specification directory does not contain YAML files: " + directory
            );
        }
        return testSpecificationPaths;
    }

    private static Path resolveClasspathDirectory() throws IOException {
        URL resource = CatalogSnapshotExecutionEntryPoint.class.getResource(
                DEFAULT_TEST_SPECIFICATIONS_DIRECTORY_RESOURCE
        );
        if (resource == null) {
            throw new IOException(
                    "Catalog snapshot test specification directory is missing: classpath:"
                            + DEFAULT_TEST_SPECIFICATIONS_DIRECTORY_RESOURCE
            );
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException | FileSystemNotFoundException exception) {
            throw new IOException(
                    "Catalog snapshot test specification directory is not available as a file-system path: "
                            + resource,
                    exception
            );
        }
    }

    private static Path contractDirFromSystemProperty() {
        return Path.of(System.getProperty(CONTRACT_DIR_PROPERTY, DEFAULT_CONTRACT_DIR.toString()));
    }

}
