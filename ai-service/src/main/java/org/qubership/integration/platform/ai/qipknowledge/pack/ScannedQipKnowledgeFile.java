package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Path;

/** One file discovered during QIP knowledge pack scanning. */
public record ScannedQipKnowledgeFile(
    Path absolutePath,
    String relativePath,
    QipKnowledgePackFileKind kind,
    String sha256,
    String content) {}
