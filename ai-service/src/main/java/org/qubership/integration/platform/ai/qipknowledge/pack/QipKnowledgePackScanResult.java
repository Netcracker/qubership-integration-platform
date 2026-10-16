package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Path;
import java.util.List;

/** Result of scanning a QIP knowledge pack directory. */
public record QipKnowledgePackScanResult(
    Path packRoot, QipKnowledgePackVersion version, List<ScannedQipKnowledgeFile> files) {}
