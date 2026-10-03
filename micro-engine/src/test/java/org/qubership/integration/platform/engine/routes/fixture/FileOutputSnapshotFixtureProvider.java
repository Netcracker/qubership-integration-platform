package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOutputSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "file-output";
    private static final Path CHAIN_TEMP_DIRECTORY = Path.of("/tmp/chain_tmp")
            .toAbsolutePath()
            .normalize();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean requiresNodeId() {
        return false;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        Map<String, List<OutputExpectation>> expectationsByInvocationId = new LinkedHashMap<>();
        Map<String, Map<Path, String>> fixtureIdsByTargetByInvocationId = new LinkedHashMap<>();
        for (SnapshotFixtureBinding binding : bindings) {
            SnapshotFixtureDefinition definition = binding.definition();
            SnapshotFixtureValidation.requireNoNodeId(definition, "File output");
            binding.interactionsByInvocationId().forEach((invocationId, interaction) -> {
                OutputExpectation expectation = createExpectation(
                        definition,
                        interaction
                );
                Map<Path, String> fixtureIdsByTarget = fixtureIdsByTargetByInvocationId.computeIfAbsent(
                        invocationId,
                        ignored -> new LinkedHashMap<>()
                );
                String existingFixtureId = fixtureIdsByTarget.putIfAbsent(
                        expectation.target(),
                        expectation.fixtureId()
                );
                if (existingFixtureId != null) {
                    throw new IllegalArgumentException(
                            "File output fixtures '" + existingFixtureId + "' and '"
                                    + expectation.fixtureId() + "' use the same path '"
                                    + expectation.target() + "' in invocation '" + invocationId + "'."
                    );
                }
                expectationsByInvocationId.computeIfAbsent(invocationId, ignored -> new ArrayList<>())
                        .add(expectation);
            });
        }
        return new FileOutputSnapshotFixture(deploymentId, expectationsByInvocationId);
    }

    private static OutputExpectation createExpectation(
            SnapshotFixtureDefinition definition,
            SnapshotFixtureInteraction interaction
    ) {
        String fixtureId = definition.getId();
        if (interaction.getResponse() != null) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId + "' only supports expectedRequest."
            );
        }

        SnapshotFixtureRequestExpectation expectedRequest = interaction.getExpectedRequest();
        if (expectedRequest == null) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId + "' must define expectedRequest."
            );
        }
        if (expectedRequest.getCount() != 1) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId + "' expectedRequest count must be 1."
            );
        }
        if (expectedRequest.getPath() == null) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId + "' expectedRequest path is missing."
            );
        }
        if (expectedRequest.getMethod() != null
                || expectedRequest.getQuery() != null
                || expectedRequest.getDestination() != null
                || expectedRequest.getKey() != null
                || !expectedRequest.getHeaders().isEmpty()
                || !expectedRequest.getProperties().isEmpty()) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId
                            + "' expectedRequest only supports count, path, and body."
            );
        }
        if (!(expectedRequest.getBody() instanceof String expectedContent)) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId + "' expectedRequest body must be a string."
            );
        }
        return new OutputExpectation(
                fixtureId,
                resolveTarget(fixtureId, expectedRequest.getPath()),
                expectedContent
        );
    }

    private static Path resolveTarget(String fixtureId, String path) {
        Path relativePath = Path.of(path).normalize();
        Path target = CHAIN_TEMP_DIRECTORY.resolve(relativePath).normalize();
        if (relativePath.isAbsolute()
                || relativePath.getNameCount() == 0
                || !target.startsWith(CHAIN_TEMP_DIRECTORY)
                || target.equals(CHAIN_TEMP_DIRECTORY)) {
            throw new IllegalArgumentException(
                    "File output fixture '" + fixtureId
                            + "' path must stay inside the chain temporary directory: " + path
            );
        }
        return target;
    }

    private static final class FileOutputSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final Map<String, List<OutputExpectation>> expectationsByInvocationId;
        private final Set<Path> ownedTargets = new LinkedHashSet<>();
        private final Set<Path> createdDirectories = new LinkedHashSet<>();
        private List<OutputExpectation> currentExpectations = List.of();

        private FileOutputSnapshotFixture(
                String deploymentId,
                Map<String, List<OutputExpectation>> expectationsByInvocationId
        ) {
            this.deploymentId = deploymentId;
            Map<String, List<OutputExpectation>> immutableExpectations = new LinkedHashMap<>();
            expectationsByInvocationId.forEach((invocationId, expectations) ->
                    immutableExpectations.put(invocationId, List.copyOf(expectations)));
            this.expectationsByInvocationId = Map.copyOf(immutableExpectations);
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void configure(CamelContext camelContext) {
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            if (!currentExpectations.isEmpty()) {
                throw new IllegalStateException(
                        "File output fixture did not finish the previous invocation."
                );
            }
            if (invocation.getRepeat() != 1) {
                throw new IllegalArgumentException(
                        "File output fixture does not support repeated invocation '"
                                + invocation.getId() + "'."
                );
            }

            List<OutputExpectation> expectations = expectationsByInvocationId.get(invocation.getId());
            if (expectations == null) {
                throw new IllegalStateException(
                        "File output fixture does not contain invocation '" + invocation.getId() + "'."
                );
            }
            for (OutputExpectation expectation : expectations) {
                prepareTarget(expectation);
            }
            currentExpectations = expectations;
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws IOException {
            for (OutputExpectation expectation : currentExpectations) {
                Path target = expectation.target();
                assertTrue(
                        Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS),
                        () -> "File output fixture '" + expectation.fixtureId()
                                + "' did not create a regular file at '" + target + "'."
                );
                String actualContent = Files.readString(target, StandardCharsets.UTF_8);
                assertEquals(
                        expectation.expectedContent(),
                        actualContent,
                        () -> "File output fixture '" + expectation.fixtureId()
                                + "' wrote unexpected content to '" + target + "'."
                );
            }
            for (OutputExpectation expectation : currentExpectations) {
                Files.delete(expectation.target());
                ownedTargets.remove(expectation.target());
            }
            currentExpectations = List.of();
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() throws IOException {
            IOException cleanupFailure = null;
            List<Path> targets = new ArrayList<>(ownedTargets);
            for (int index = targets.size() - 1; index >= 0; index--) {
                cleanupFailure = delete(targets.get(index), cleanupFailure);
            }
            List<Path> directories = new ArrayList<>(createdDirectories);
            directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path directory : directories) {
                try {
                    Files.deleteIfExists(directory);
                } catch (DirectoryNotEmptyException ignored) {
                    // Preserve files that another process created while the snapshot scenario was running.
                } catch (IOException exception) {
                    cleanupFailure = append(cleanupFailure, exception);
                }
            }
            ownedTargets.clear();
            createdDirectories.clear();
            currentExpectations = List.of();
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private void prepareTarget(OutputExpectation expectation) throws IOException {
            Path target = expectation.target();
            rejectSymbolicLinkDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "File output fixture '" + expectation.fixtureId()
                                + "' target already exists and cannot be observed: " + target
                );
            }
            trackMissingDirectories(target.getParent());
            Files.createDirectories(target.getParent());
            rejectSymbolicLinkDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "File output fixture '" + expectation.fixtureId()
                                + "' target appeared while the fixture was preparing it: " + target
                );
            }
            ownedTargets.add(target);
        }

        private void trackMissingDirectories(Path parent) {
            Path directory = parent;
            while (directory != null
                    && directory.startsWith(CHAIN_TEMP_DIRECTORY)
                    && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                createdDirectories.add(directory);
                directory = directory.getParent();
            }
        }

        private static void rejectSymbolicLinkDirectories(Path parent) throws IOException {
            Path directory = CHAIN_TEMP_DIRECTORY;
            if (Files.isSymbolicLink(directory)) {
                throw symbolicLinkDirectory(directory);
            }
            for (Path name : CHAIN_TEMP_DIRECTORY.relativize(parent)) {
                directory = directory.resolve(name);
                if (Files.isSymbolicLink(directory)) {
                    throw symbolicLinkDirectory(directory);
                }
            }
        }

        private static IOException symbolicLinkDirectory(Path directory) {
            return new IOException(
                    "File output fixtures cannot use a symbolic-link directory: " + directory
            );
        }

        private static IOException delete(Path path, IOException cleanupFailure) {
            try {
                Files.deleteIfExists(path);
                return cleanupFailure;
            } catch (IOException exception) {
                return append(cleanupFailure, exception);
            }
        }

        private static IOException append(IOException cleanupFailure, IOException exception) {
            if (cleanupFailure == null) {
                return exception;
            }
            cleanupFailure.addSuppressed(exception);
            return cleanupFailure;
        }
    }

    private record OutputExpectation(
            String fixtureId,
            Path target,
            String expectedContent
    ) {
    }
}
