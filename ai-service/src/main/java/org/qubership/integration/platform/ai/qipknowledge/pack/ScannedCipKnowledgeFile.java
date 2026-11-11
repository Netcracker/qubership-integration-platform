package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Path;

/** One file discovered during QIP knowledge pack scanning. */
public record ScannedCipKnowledgeFile(
    Path absolutePath,
    String relativePath,
    CipKnowledgePackFileKind kind,
    String sha256,
    String content) {}
