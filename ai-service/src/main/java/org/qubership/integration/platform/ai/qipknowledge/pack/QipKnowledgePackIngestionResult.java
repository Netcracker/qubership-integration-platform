package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Result of ingesting one QIP skill pack into indexes and reports. */
public record QipKnowledgePackIngestionResult(
    QipKnowledgePackManifest manifest,
    CapabilityRegistry registry,
    List<UnsupportedQipKnowledgeItem> unsupportedItems,
    String compatibilityReportMarkdown,
    List<ScannedQipKnowledgeFile> files) {}
