package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Logical-design response. Transfers, rules, and retained values are not properties. */
record LogicalDesignCapture(
    WorkOutcome outcome,
    List<CapturedRequirement> requirements,
    List<CapturedStep> steps,
    List<CapturedConnection> connections,
    List<CapturedSequenceGroup> sequenceGroups,
    List<CapturedConditionGroup> conditionGroups,
    List<CapturedSplitGroup> splitGroups,
    List<CapturedLoopGroup> loopGroups,
    List<CapturedRetryGroup> retryGroups,
    List<CapturedErrorScopeGroup> errorScopeGroups,
    List<CapturedDelete> deletes,
    String question,
    String unresolvedChoice,
    List<String> clarificationEvidenceIds,
    String defectRecordRef,
    String contradiction,
    List<String> defectEvidenceIds,
    String issueCategory) {}
