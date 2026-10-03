package org.qubership.integration.platform.engine.routes.entrypoint.bundle;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SnapshotBundleReader {
    private static final int SUPPORTED_FORMAT_VERSION = 1;
    private static final String INDEX_FILE_NAME = "snapshotindex.yml";

    private final ObjectMapper objectMapper;

    SnapshotBundleReader() {
        this(SnapshotYamlMappers.create());
    }

    public SnapshotBundleReader(ObjectMapper objectMapper) {
        this.objectMapper = SnapshotYamlMappers.strictCopy(objectMapper);
    }

    public SnapshotBundle read(Path bundleDirectory) throws IOException {
        Path normalizedBundleDirectory = Objects.requireNonNull(bundleDirectory, "bundleDirectory")
                .toAbsolutePath()
                .normalize();
        Path indexFile = normalizedBundleDirectory.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(indexFile)) {
            throw new IOException("Snapshot bundle index is missing: " + indexFile);
        }

        RawSnapshotBundleIndex index = objectMapper.readValue(
                indexFile.toFile(),
                RawSnapshotBundleIndex.class
        );
        validateFormatVersion(index.formatVersion());
        if (index.snapshots() == null || index.snapshots().isEmpty()) {
            throw new IOException("Snapshot bundle index does not contain any snapshots: " + indexFile);
        }

        Path realBundleDirectory = normalizedBundleDirectory.toRealPath();
        Set<String> chainNames = new HashSet<>();
        Set<String> chainIds = new HashSet<>();
        Set<String> snapshotIds = new HashSet<>();
        Set<String> snapshotPaths = new HashSet<>();
        List<SnapshotBundleEntry> entries = new ArrayList<>(index.snapshots().size());
        for (int entryIndex = 0; entryIndex < index.snapshots().size(); entryIndex++) {
            RawSnapshotBundleEntry rawEntry = index.snapshots().get(entryIndex);
            if (rawEntry == null) {
                throw new IOException(
                        "Snapshot bundle contains a null snapshot entry at index " + entryIndex + "."
                );
            }

            String chainName = requireNonBlank(rawEntry.chainName(), "chainName", entryIndex);
            String chainId = requireNonBlank(rawEntry.chainId(), "chainId", entryIndex);
            String snapshotId = requireNonBlank(rawEntry.snapshotId(), "snapshotId", entryIndex);
            String snapshotPath = requireNonBlank(rawEntry.snapshotPath(), "snapshotPath", entryIndex);
            requireUnique(chainNames, chainName, "chain name");
            requireUnique(chainIds, chainId, "chain ID");
            requireUnique(snapshotIds, snapshotId, "snapshot ID");
            requireUnique(snapshotPaths, snapshotPath, "snapshot path");

            Path snapshotFile = resolveSnapshotFile(
                    normalizedBundleDirectory,
                    realBundleDirectory,
                    chainName,
                    snapshotPath
            );
            Map<String, String> nodeIds = validateNodeIds(
                    chainName,
                    rawEntry.snapshotNodeIdsBySourceElementId()
            );
            entries.add(new SnapshotBundleEntry(
                    chainName,
                    chainId,
                    snapshotId,
                    snapshotFile,
                    nodeIds
            ));
        }
        return new SnapshotBundle(entries);
    }

    private static void validateFormatVersion(Integer formatVersion) throws IOException {
        if (formatVersion == null || formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw new IOException(
                    "Snapshot bundle format version " + formatVersion
                            + " is not supported. Supported version: " + SUPPORTED_FORMAT_VERSION + "."
            );
        }
    }

    private static String requireNonBlank(
            String value,
            String fieldName,
            int entryIndex
    ) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(
                    "Snapshot bundle entry at index " + entryIndex + " has a blank " + fieldName + "."
            );
        }
        return value;
    }

    private static void requireUnique(
            Set<String> values,
            String value,
            String valueType
    ) throws IOException {
        if (!values.add(value)) {
            throw new IOException(
                    "Snapshot bundle contains duplicate " + valueType + " '" + value + "'."
            );
        }
    }

    private static Path resolveSnapshotFile(
            Path bundleDirectory,
            Path realBundleDirectory,
            String chainName,
            String snapshotPath
    ) throws IOException {
        Path relativePath;
        try {
            relativePath = Path.of(snapshotPath);
        } catch (InvalidPathException exception) {
            throw new IOException(
                    "Snapshot path '" + snapshotPath + "' for chain '" + chainName + "' is invalid.",
                    exception
            );
        }
        if (relativePath.isAbsolute() || snapshotPath.contains("\\")) {
            throw invalidSnapshotPath(chainName, snapshotPath);
        }
        for (Path segment : relativePath) {
            if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                throw invalidSnapshotPath(chainName, snapshotPath);
            }
        }

        Path snapshotFile = bundleDirectory.resolve(relativePath).normalize();
        if (!snapshotFile.startsWith(bundleDirectory)) {
            throw invalidSnapshotPath(chainName, snapshotPath);
        }
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException(
                    "Snapshot XML is missing for chain '" + chainName + "': " + snapshotFile
            );
        }
        if (!snapshotFile.toRealPath().startsWith(realBundleDirectory)) {
            throw invalidSnapshotPath(chainName, snapshotPath);
        }
        return snapshotFile;
    }

    private static IOException invalidSnapshotPath(String chainName, String snapshotPath) {
        return new IOException(
                "Snapshot path '" + snapshotPath + "' for chain '" + chainName
                        + "' escapes the snapshot bundle directory."
        );
    }

    private static Map<String, String> validateNodeIds(
            String chainName,
            Map<String, String> rawNodeIds
    ) throws IOException {
        if (rawNodeIds == null || rawNodeIds.isEmpty()) {
            throw new IOException(
                    "Snapshot bundle chain '" + chainName + "' does not contain source element mappings."
            );
        }

        Map<String, String> nodeIds = new LinkedHashMap<>();
        Set<String> snapshotNodeIds = new HashSet<>();
        for (Map.Entry<String, String> nodeId : rawNodeIds.entrySet()) {
            String sourceElementId = nodeId.getKey();
            String snapshotNodeId = nodeId.getValue();
            if (sourceElementId == null || sourceElementId.isBlank()) {
                throw new IOException(
                        "Snapshot bundle chain '" + chainName + "' contains a blank source element ID."
                );
            }
            if (snapshotNodeId == null || snapshotNodeId.isBlank()) {
                throw new IOException(
                        "Snapshot bundle chain '" + chainName + "' maps source element '"
                                + sourceElementId + "' to a blank snapshot node ID."
                );
            }
            if (!snapshotNodeIds.add(snapshotNodeId)) {
                throw new IOException(
                        "Snapshot bundle chain '" + chainName + "' contains duplicate snapshot node ID '"
                                + snapshotNodeId + "'."
                );
            }
            nodeIds.put(sourceElementId, snapshotNodeId);
        }
        return nodeIds;
    }

    private record RawSnapshotBundleIndex(
            Integer formatVersion,
            List<RawSnapshotBundleEntry> snapshots
    ) {
    }

    private record RawSnapshotBundleEntry(
            String chainName,
            String chainId,
            String snapshotId,
            String snapshotPath,
            Map<String, String> snapshotNodeIdsBySourceElementId
    ) {
    }
}
