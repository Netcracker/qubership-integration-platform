package localwork

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func managedChecksPassed(input workcycle.AgentAssessmentRequest) bool {
	if input.Stage != workcycle.VerifyStage || len(input.VerificationRecheck) != 0 || len(input.Request.ManagedChecks) == 0 {
		return false
	}
	passed := map[string]bool{}
	for _, result := range input.ManagedResults {
		passed[result.ID] = result.Outcome == "pass" && len(result.Evidence) > 0
	}
	for _, required := range input.Request.ManagedChecks {
		if !passed[required.ID] {
			return false
		}
	}
	return true
}

func (d *Driver) reassessVerification(ctx context.Context, input workcycle.AgentAssessmentRequest, original workcycle.AgentAssessment) (workcycle.AgentAssessment, error) {
	current, err := d.Snapshot(ctx)
	if err != nil || current.ID != input.Candidate.ID {
		return original, fmt.Errorf("cannot confirm the candidate before the verification reassessment")
	}
	if err = d.checkArtifacts(); err != nil {
		return original, err
	}
	reassessment := input
	reassessment.Stage = workcycle.VerificationReassessmentStage
	reassessment.PreviousVerification = &original
	checked, err := d.assessAgentTurn(ctx, reassessment)
	result := original
	result.Reassessment = &checked
	result.Evidence = append(append([]string(nil), result.Evidence...), checked.Evidence...)
	if err != nil {
		return result, err
	}
	result.Decision, result.Summary, result.Findings = checked.Decision, checked.Summary, checked.Findings
	if result.Decision == "approve" {
		result.CoverageAudit = d.auditCoverage(ctx, input, result.Coverage)
		if result.CoverageAudit != nil && result.CoverageAudit.Evidence != "" {
			result.Evidence = append(result.Evidence, result.CoverageAudit.Evidence)
		}
	}
	return result, nil
}

func verificationReassessmentPrompt(input workcycle.AgentAssessmentRequest) string {
	input.Candidate.FileHashes = nil
	input.Evidence = nil
	data, _ := json.MarshalIndent(input, "", "  ")
	return fmt.Sprintf(`You are an independent QIP assessor in a fresh native session. Review the earlier blocked verification without rerunning the product or any checks. Read the saved verdict, required case observations, fresh evidence, and controller managed_results. The controller verified the evidence files and successful results for its configured checks. Examine their actual scope and evidence before deciding whether the blocker applies to a requirement.

Approve only if the earlier blocker concerned supplemental work outside the required scope and the original evidence establishes every required case, candidate identity, and fresh data. Keep verification blocked if any required behavior, identity, environment, data, or evidence is unestablished. Use changes-requested for a concrete product defect or needs-human for a decision from the owner. Preserve failed observations in your assessment. A successful managed check covers its configured scope. A broader network scan outside that scope supplies supplemental information.

Use read-only inspection of the supplied materials. Do not edit files, rebuild, write to the product environment, launch another model, or use GitHub. Return one JSON object with decision, summary, and findings. Name the earlier blocker and explain whether it affects a required case. Use an empty findings array when there is no blocking finding.

Controller input:
%s`, data)
}
