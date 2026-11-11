package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.SystemType;

class RequirementPreferenceEvidenceTest {

  @Test
  void externalEndpointDoesNotAuthorizeSystemVisibilityOrIdsChoice() {
    DraftInput candidate = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(), List.of(), List.of(), new DraftSettings(false, SystemType.EXTERNAL));

    var issues = RequirementPreferenceEvidence.validate(candidate, null,
        "Create a chain that calls an external endpoint, then prepare a design for approval.",
        "/draft");

    assertEquals(2, issues.size());
    assertTrue(issues.stream().anyMatch(issue ->
        "/draft/settings/idsRequested".equals(issue.path())));
    assertTrue(issues.stream().anyMatch(issue ->
        "/draft/settings/preferredSystemType".equals(issue.path())));
    assertTrue(RequirementPreferenceEvidence.validate(candidate, null,
        "No IDS. Mark the integration system external.", "/draft").isEmpty());
  }
}
