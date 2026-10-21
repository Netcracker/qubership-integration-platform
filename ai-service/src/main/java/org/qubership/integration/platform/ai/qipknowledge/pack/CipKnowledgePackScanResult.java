package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Path;
import java.util.List;

/** Result of scanning a QIP knowledge pack directory. */
public record CipKnowledgePackScanResult(
    Path packRoot, CipKnowledgePackVersion version, List<ScannedCipKnowledgeFile> files) {}
