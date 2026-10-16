package org.qubership.integration.platform.ai.plan.workdocument.task;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;

/**
 * One model call. The handler selects the task identity, the prompt, and the response schema.
 * Adapters forward this request and do not replace the schema.
 */
public record WorkTaskRequest(
    String taskId,
    String taskKey,
    WorkTaskKind kind,
    String prompt,
    JsonObjectSchema responseSchema) {}
