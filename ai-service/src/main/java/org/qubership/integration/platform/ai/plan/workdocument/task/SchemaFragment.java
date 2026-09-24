package org.qubership.integration.platform.ai.plan.workdocument.task;

/** One schema slice the server may show to a task, with its catalog provenance. */
public record SchemaFragment(
    String id, String stepId, String portName, String contentHash, String reference, String body) {}
