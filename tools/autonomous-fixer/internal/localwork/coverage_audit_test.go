package localwork

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type mismatchClassifier struct{ requests []jev.Request }

func (f *mismatchClassifier) Classify(_ context.Context, q jev.Request) (jev.Response, error) {
	f.requests = append(f.requests, q)
	return jev.Response{ID: "coverage-fixture", Model: jev.DefaultModel, Answers: map[string]jev.Answer{
		"case_1": {Type: "choice", Choice: "mismatch", Probabilities: map[string]float64{"mismatch": 1, "matches": 0, "insufficient": 0}},
		"case_2": {Type: "choice", Choice: "matches", Probabilities: map[string]float64{"mismatch": 0, "matches": 1, "insufficient": 0}},
	}}, nil
}

func TestCoverageAuditOptionalModes(t *testing.T) {
	for _, tc := range []struct {
		name          string
		mode          string
		providerError bool
		recheckError  bool
		status        string
		turns         int
	}{
		{name: "control", status: "verified", turns: 1},
		{name: "shadow", mode: "shadow", status: "verified", turns: 1},
		{name: "recheck", mode: "recheck", status: "verified", turns: 2},
		{name: "unavailable", mode: "recheck", providerError: true, status: "verified", turns: 1},
		{name: "recheck blocked", mode: "recheck", recheckError: true, status: "verification-blocked", turns: 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Workflow = "agent"
			if tc.mode != "" {
				cfg.EngineeringTriage = &EngineeringTriage{DisableRouting: true, CoverageAudit: tc.mode}
			}
			pair := cfg.RequiredCoverage[0]
			cfg.VerificationScenarios = []workcycle.VerificationScenario{
				{ID: "from-sessions", Coverage: pair, Description: "Open from Sessions. Secret: sk-testcredentialexample"},
				{ID: "from-folder", Coverage: pair, Description: "Open from the folder"},
			}
			if tc.recheckError {
				cfg.Environment["QIP_AGENT_RECHECK_FAIL"] = "1"
			}
			d, err := prepareHistoricalTriageFixture(cfg)
			if err != nil {
				t.Fatal(err)
			}
			client := &mismatchClassifier{}
			d.triageClient = client
			if tc.providerError {
				d.triageClient = unavailableClassifier{}
			}
			report, err := workcycle.RunAgent(context.Background(), d.Request, d)
			if err != nil || report.Status != tc.status || len(report.Routing) != 0 {
				t.Fatal(report.Status, report.Error, err, report.Routing)
			}
			turns, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-verify"))
			if len(turns) != tc.turns {
				t.Fatal(turns)
			}
			last := report.Observations[len(report.Observations)-1].Declaration.Assessment
			if tc.mode == "" {
				if last.CoverageAudit != nil || len(client.requests) != 0 {
					t.Fatal(last.CoverageAudit, client.requests)
				}
				return
			}
			audit := last.CoverageAudit
			if audit == nil || audit.Evidence == "" || (audit.Recheck != nil) != (tc.turns == 2) {
				t.Fatal(audit)
			}
			var evidence tasktriage.Report
			readAgentJSON(t, audit.Evidence, &evidence)
			if err = tasktriage.Validate(evidence); err != nil || evidence.Packet.Phase != "coverage-evidence" {
				t.Fatal(err, evidence.Packet)
			}
			if tc.providerError {
				if audit.Error == "" || !evidence.RequestAttempted || evidence.Response != nil {
					t.Fatal(audit, evidence)
				}
				return
			}
			encoded, _ := json.Marshal(client.requests)
			if len(client.requests) != 1 || strings.Contains(string(encoded), "sk-testcredentialexample") || strings.Contains(string(encoded), cfg.OutputDirectory) {
				t.Fatal(string(encoded))
			}
			if tc.turns == 2 {
				var first, second workcycle.AgentAssessmentRequest
				readAgentJSON(t, filepath.Join(turns[0], "invocation.json"), &first)
				readAgentJSON(t, filepath.Join(turns[1], "invocation.json"), &second)
				if second.VerificationToken == first.VerificationToken || len(second.VerificationRecheck) != 1 || second.VerificationRecheck[0].CaseID != "from-sessions" {
					t.Fatal(second)
				}
				if !tc.recheckError && (len(last.Coverage) != 3 || !strings.Contains(last.Coverage[0].Evidence[0], filepath.Base(turns[1])) || !strings.Contains(last.Coverage[1].Evidence[0], filepath.Base(turns[0]))) {
					t.Fatal(last.Coverage)
				}
			}
		})
	}
}
