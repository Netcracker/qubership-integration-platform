/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.snapshotbundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class SnapshotBundleWriter {
    public static final int FORMAT_VERSION = 1;
    public static final String INDEX_FILE_NAME = "snapshotindex.yml";
    public static final String SNAPSHOTS_DIRECTORY_NAME = "snapshots";
    public static final String SNAPSHOT_FILE_NAME = "snapshot.xml";

    private final ObjectMapper yamlMapper;

    public SnapshotBundleWriter() {
        this(new YAMLMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
    }

    SnapshotBundleWriter(ObjectMapper yamlMapper) {
        this.yamlMapper = requirePresent(yamlMapper, "YAML mapper");
    }

    public SnapshotBundleIndex write(Path bundleDirectory, Collection<SnapshotBundleSource> snapshots) throws IOException {
        requirePresent(bundleDirectory, "bundle directory");
        requirePresent(snapshots, "snapshot collection");

        List<PreparedSnapshot> preparedSnapshots = prepareSnapshots(snapshots);
        if (preparedSnapshots.isEmpty()) {
            throw new IllegalArgumentException("Cannot write snapshot bundle: snapshot collection is empty.");
        }
        validateUniqueSnapshotMetadata(preparedSnapshots);
        preparedSnapshots.sort(Comparator
                .comparing((PreparedSnapshot prepared) -> prepared.entry().chainName())
                .thenComparing(prepared -> prepared.entry().chainId()));

        SnapshotBundleIndex index = new SnapshotBundleIndex(
                FORMAT_VERSION,
                preparedSnapshots.stream().map(PreparedSnapshot::entry).toList()
        );
        writeBundle(bundleDirectory, preparedSnapshots, index);
        return index;
    }

    private List<PreparedSnapshot> prepareSnapshots(Collection<SnapshotBundleSource> snapshots) {
        List<PreparedSnapshot> preparedSnapshots = new ArrayList<>();
        int snapshotIndex = 0;
        for (SnapshotBundleSource source : snapshots) {
            if (source == null || source.snapshot() == null) {
                throw new IllegalArgumentException(
                        "Cannot write snapshot bundle: snapshot at index " + snapshotIndex + " is missing."
                );
            }
            preparedSnapshots.add(prepareSnapshot(source));
            snapshotIndex++;
        }
        return preparedSnapshots;
    }

    private PreparedSnapshot prepareSnapshot(SnapshotBundleSource source) {
        Snapshot snapshot = source.snapshot();
        String snapshotId = requireNonBlank(snapshot.getId(), "snapshot ID");
        Chain chain = snapshot.getChain();
        if (chain == null) {
            throw new IllegalArgumentException(
                    "Cannot write snapshot bundle for snapshot '" + snapshotId + "': chain is missing."
            );
        }

        String chainId = requireNonBlank(chain.getId(), "chain ID");
        String chainName = requireNonBlank(chain.getName(), "chain name");
        validatePortablePathSegment(chainId);
        String xmlDefinition = requireNonBlank(source.xml(), "snapshot XML");
        Map<String, String> elementIds = extractElementIds(snapshot, chainId, snapshotId);
        String snapshotPath = SNAPSHOTS_DIRECTORY_NAME + "/" + chainId + "/" + SNAPSHOT_FILE_NAME;

        return new PreparedSnapshot(
                new SnapshotBundleEntry(chainName, chainId, snapshotId, snapshotPath, elementIds),
                xmlDefinition
        );
    }

    private Map<String, String> extractElementIds(Snapshot snapshot, String chainId, String snapshotId) {
        Collection<Element> elements = snapshot.getElements();
        if (elements == null || elements.isEmpty()) {
            throw invalidSnapshot(chainId, snapshotId, "element list is empty");
        }

        Map<String, String> snapshotNodeIdsBySourceElementId = new TreeMap<>();
        Set<String> snapshotNodeIds = new HashSet<>();
        int index = 0;
        for (Element element : elements) {
            if (element == null) {
                throw invalidSnapshot(chainId, snapshotId, "element at index " + index + " is missing");
            }

            String sourceElementId = requireElementId(
                    element.getOriginalId().orElse(null),
                    "source element ID",
                    chainId,
                    snapshotId
            );
            String snapshotNodeId = requireElementId(
                    element.getId(),
                    "snapshot node ID",
                    chainId,
                    snapshotId
            );
            if (snapshotNodeIdsBySourceElementId.putIfAbsent(sourceElementId, snapshotNodeId) != null) {
                throw invalidSnapshot(
                        chainId,
                        snapshotId,
                        "source element ID '" + sourceElementId + "' is duplicated"
                );
            }
            if (!snapshotNodeIds.add(snapshotNodeId)) {
                throw invalidSnapshot(
                        chainId,
                        snapshotId,
                        "snapshot node ID '" + snapshotNodeId + "' is duplicated"
                );
            }
            index++;
        }
        return snapshotNodeIdsBySourceElementId;
    }

    private void validateUniqueSnapshotMetadata(List<PreparedSnapshot> preparedSnapshots) {
        Map<String, String> chainIdsByName = new HashMap<>();
        Set<String> chainIds = new HashSet<>();
        Set<String> snapshotIds = new HashSet<>();
        for (PreparedSnapshot preparedSnapshot : preparedSnapshots) {
            SnapshotBundleEntry entry = preparedSnapshot.entry();
            String previousChainId = chainIdsByName.putIfAbsent(entry.chainName(), entry.chainId());
            if (previousChainId != null) {
                throw new IllegalArgumentException(
                        "Cannot write snapshot bundle: chain name '" + entry.chainName()
                                + "' is used by chains '" + previousChainId + "' and '" + entry.chainId() + "'."
                );
            }
            if (!chainIds.add(entry.chainId())) {
                throw new IllegalArgumentException(
                        "Cannot write snapshot bundle: chain ID '" + entry.chainId() + "' is duplicated."
                );
            }
            if (!snapshotIds.add(entry.snapshotId())) {
                throw new IllegalArgumentException(
                        "Cannot write snapshot bundle: snapshot ID '" + entry.snapshotId() + "' is duplicated."
                );
            }
        }
    }

    private void writeBundle(
            Path bundleDirectory,
            List<PreparedSnapshot> preparedSnapshots,
            SnapshotBundleIndex index
    ) throws IOException {
        byte[] serializedIndex = yamlMapper.writeValueAsBytes(index);
        Files.createDirectories(bundleDirectory);
        Path indexFile = bundleDirectory.resolve(INDEX_FILE_NAME);
        Files.deleteIfExists(indexFile);
        deleteRecursively(bundleDirectory.resolve(SNAPSHOTS_DIRECTORY_NAME));
        for (PreparedSnapshot preparedSnapshot : preparedSnapshots) {
            Path snapshotFile = bundleDirectory.resolve(preparedSnapshot.entry().snapshotPath());
            Files.createDirectories(snapshotFile.getParent());
            Files.writeString(snapshotFile, preparedSnapshot.xmlDefinition(), StandardCharsets.UTF_8);
        }

        Path temporaryIndexFile = Files.createTempFile(bundleDirectory, INDEX_FILE_NAME, ".tmp");
        try {
            Files.write(temporaryIndexFile, serializedIndex);
            Files.move(
                    temporaryIndexFile,
                    indexFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporaryIndexFile);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (var pathStream = Files.walk(root)) {
            paths = pathStream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static void validatePortablePathSegment(String chainId) {
        try {
            Path chainIdPath = Path.of(chainId);
            if (chainIdPath.isAbsolute()
                    || chainIdPath.getNameCount() != 1
                    || ".".equals(chainId)
                    || "..".equals(chainId)
                    || chainId.contains("\\")) {
                throw new IllegalArgumentException(
                        "Cannot write snapshot bundle for chain '" + chainId
                                + "': chain ID is not a portable path segment."
                );
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                    "Cannot write snapshot bundle for chain '" + chainId
                            + "': chain ID is not a portable path segment.",
                    exception
            );
        }
    }

    private static String requireElementId(
            String value,
            String fieldName,
            String chainId,
            String snapshotId
    ) {
        if (value == null || value.isBlank()) {
            throw invalidSnapshot(chainId, snapshotId, fieldName + " is missing");
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cannot write snapshot bundle: " + fieldName + " is missing.");
        }
        return value;
    }

    private static <T> T requirePresent(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot write snapshot bundle: " + fieldName + " is missing.");
        }
        return value;
    }

    private static IllegalArgumentException invalidSnapshot(String chainId, String snapshotId, String reason) {
        return new IllegalArgumentException(
                "Cannot index snapshot '" + snapshotId + "' for chain '" + chainId + "': " + reason + "."
        );
    }

    private record PreparedSnapshot(SnapshotBundleEntry entry, String xmlDefinition) {
    }
}
