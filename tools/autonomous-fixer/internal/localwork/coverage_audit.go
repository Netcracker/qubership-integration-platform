package localwork

import (
	"context"
	"os"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) auditCoverage(ctx context.Context, input workcycle.AgentAssessmentRequest, rows []workcycle.VerificationResult) *workcycle.CoverageAudit {
	if d.Config.EngineeringTriage == nil || d.Config.EngineeringTriage.CoverageAudit == "" {
		return nil
	}
	out := &workcycle.CoverageAudit{Mode: d.Config.EngineeringTriage.CoverageAudit}
	observed := map[string]string{}
	for _, row := range rows {
		observed[row.CaseID] = row.Observed
	}
	packet := tasktriage.Packet{TaskID: input.Request.TaskID, CandidateID: input.Candidate.ID, Phase: "coverage-evidence"}
	for _, c := range input.Request.VerificationScenarios {
		packet.CoverageEvidence = append(packet.CoverageEvidence, tasktriage.CoverageEvidence{
			ID: c.ID, Consumer: c.Consumer, Requirement: auditExcerpt(c.Description), Observation: auditExcerpt(observed[c.ID]),
		})
	}
	if len(packet.CoverageEvidence) == 0 {
		out.Error = "Coverage audit requires explicit verification_scenarios descriptions."
		return out
	}
	client := d.triageClient
	var err error
	if client == nil {
		client, err = jev.NewClient(os.Getenv("OPENROUTER_API_KEY"), jev.Options{})
		if err != nil {
			out.Error = err.Error()
			return out
		}
	}
	report, err := tasktriage.Evaluate(ctx, packet, client)
	if err == nil {
		out.Evidence, err = d.archiveEngineeringAdvice(report)
	}
	if err != nil {
		out.Error = err.Error()
		return out
	}
	out.Assessments, out.Error = report.Selection.CoverageAssessment, report.Error
	return out
}

func auditExcerpt(raw string) string {
	clean := sanitizeDiagnostic(raw)
	if len(clean) > 2000 {
		clean = clean[:2000] + " [excerpt truncated]"
	}
	return strings.ToValidUTF8(clean, " ")
}
