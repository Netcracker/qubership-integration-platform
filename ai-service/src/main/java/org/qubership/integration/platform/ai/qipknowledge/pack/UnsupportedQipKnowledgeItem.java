package org.qubership.integration.platform.ai.qipknowledge.pack;

/** One capability that the backend cannot execute in the current integration model. */
public record UnsupportedQipKnowledgeItem(String id, String sourcePath, String reason) {}
