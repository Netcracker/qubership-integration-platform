package localwork

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAgentFinalVerificationBlocksOmittedEntry(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	cfg.VerificationScenarios = []workcycle.VerificationScenario{
		{Coverage: cfg.RequiredCoverage[0], ID: "from-graph", Description: "Open the entity from the graph and exercise Back."},
		{Coverage: cfg.RequiredCoverage[0], ID: "from-sessions", Description: "Open the entity from Sessions and exercise Back."},
	}
	cfg.Environment["QIP_AGENT_VERIFY_OMIT"] = "from-sessions"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verification-blocked" || !strings.Contains(report.Summary, "missing verification cases from-sessions") {
		t.Fatal(report.Status, report.Summary, err)
	}
	last := report.Observations[len(report.Observations)-1]
	if last.Stage != workcycle.VerifyStage || last.Declaration.Assessment.Decision != "verification-blocked" {
		t.Fatal(last)
	}
	authors, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-author"))
	if len(authors) != 1 {
		t.Fatal("incomplete verification triggered author repair", authors)
	}
}

func TestAgentVerificationRequiresFreshEvidenceAndUnmanagedChecks(t *testing.T) {
	cfg := agentFixture(t)
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	space := t.TempDir()
	evidence := filepath.Join(d.Config.Workspace, "observed.json")
	started := time.Now().Add(-time.Second)
	content := []byte(`{"status":200,"data":"fresh entity"}`)
	if err = os.WriteFile(evidence, content, 0600); err != nil {
		t.Fatal(err)
	}
	request := workcycle.AgentAssessmentRequest{Request: d.Request}
	coverage := func(ref string) []workcycle.VerificationResult {
		return []workcycle.VerificationResult{{CaseID: "literal@classic", Observed: "Fresh entity responded with 200", Evidence: []string{ref}}}
	}
	if _, err = d.validateVerification(request, coverage("observed.json"), space, started); err == nil || !strings.Contains(err.Error(), "missing verification cases check:module") {
		t.Fatal(err)
	}
	request.Request.ManagedChecks = request.Request.RequiredChecks
	if _, err = d.validateVerification(request, coverage("missing.json"), space, started); err == nil || !strings.Contains(err.Error(), "cannot resolve verification evidence") {
		t.Fatal(err)
	}
	if _, err = d.validateVerification(request, coverage(evidence), space, time.Now().Add(time.Second)); err == nil || !strings.Contains(err.Error(), "from this verification attempt") {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "unrelated.json")
	if err = os.WriteFile(outside, content, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(space, "unrelated.json")
	if err = os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	for _, ref := range []string{outside, link} {
		if _, err = d.validateVerification(request, coverage(ref), space, started); err == nil || !strings.Contains(err.Error(), "must be in the checkout or current evidence directory") {
			t.Fatal(err)
		}
	}
	rows := coverage("observed.json")
	hashes, err := d.validateVerification(request, rows, space, started)
	if err != nil || hashes[evidence] != bytesDigest(content) {
		t.Fatal(hashes, err)
	}
	archived, err := d.archiveVerificationEvidence(space, rows, hashes)
	if err != nil || len(archived) != 1 || archived[rows[0].Evidence[0]] != bytesDigest(content) || !inside(space, rows[0].Evidence[0]) {
		t.Fatal(rows, archived, err)
	}
}

func TestVerificationAcceptsTaskReferenceBesideFreshProof(t *testing.T) {
	cfg := agentFixture(t)
	attachment := "attachments/03.png"
	cfg.Context.Attachments = append(cfg.Context.Attachments, attachment)
	cfg.Context.AttachmentsAsFiles = true
	if err := os.MkdirAll(filepath.Join(cfg.TaskDirectory, "attachments"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg.TaskDirectory, attachment), []byte("Issue reference image"), 0600); err != nil {
		t.Fatal(err)
	}
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	request := workcycle.AgentAssessmentRequest{Request: d.Request}
	request.Request.ManagedChecks = request.Request.RequiredChecks
	space := t.TempDir()
	started := time.Now().Add(-time.Second)
	proof := filepath.Join(space, "direct-and-visual.json")
	if err = os.WriteFile(proof, []byte(`{"breadcrumb":"folder"}`), 0600); err != nil {
		t.Fatal(err)
	}
	wrongPrefix := filepath.Join(space, attachment)
	rows := []workcycle.VerificationResult{{CaseID: "literal@classic", Observed: "The parent breadcrumb appeared and led to its folder.", Evidence: []string{proof, wrongPrefix}}}
	hashes, err := d.validateVerification(request, rows, space, started)
	if err != nil || len(hashes) != 1 || hashes[proof] == "" || len(rows[0].References) != 1 || rows[0].References[0] != filepath.Join(cfg.TaskDirectory, attachment) {
		t.Fatal(hashes, err)
	}
	rows[0].Evidence = []string{wrongPrefix}
	if _, err = d.validateVerification(request, rows, space, started); err == nil {
		t.Fatal("task attachment alone established fresh coverage")
	}
}

func TestVerificationValidatesCaptureSidecarWhenCitedAsProof(t *testing.T) {
	space := t.TempDir()
	started := time.Now().Add(-time.Second)
	capture := productCapture(t, space, "pass")
	request := workcycle.AgentAssessmentRequest{Request: workcycle.Request{
		RequiredCoverage: []workcycle.Coverage{{CriterionID: "first", Consumer: "classic"}, {CriterionID: "second", Consumer: "classic"}},
	}}
	d := &Driver{Config: Config{Workspace: t.TempDir(), TaskDirectory: t.TempDir()}}
	rows := []workcycle.VerificationResult{
		{CaseID: "first@classic", Observed: "The product test ran.", Evidence: []string{capture}},
		{CaseID: "second@classic", Observed: "The healthy peer ran.", Evidence: []string{filepath.Join(filepath.Dir(capture), "stderr.log")}},
	}
	if _, err := d.validateVerification(request, rows, space, started); err == nil || !strings.Contains(err.Error(), "must be a nonempty file") {
		t.Fatal(err)
	}
}

func TestVerificationRejectsFailedCaptureBesidePassingEvidence(t *testing.T) {
	space := t.TempDir()
	failed := filepath.Join(space, "failed")
	if err := os.Mkdir(failed, 0700); err != nil {
		t.Fatal(err)
	}
	started := time.Now().Add(-time.Second)
	request := workcycle.AgentAssessmentRequest{Request: workcycle.Request{
		RequiredCoverage: []workcycle.Coverage{{CriterionID: "literal", Consumer: "classic"}},
	}}
	d := &Driver{Config: Config{Workspace: t.TempDir(), TaskDirectory: t.TempDir()}}
	rows := []workcycle.VerificationResult{{CaseID: "literal@classic", Observed: "The assertion passed.", Evidence: []string{
		productCapture(t, space, "pass"), productCapture(t, failed, "fail"),
	}}}
	if _, err := d.validateVerification(request, rows, space, started); err == nil || !strings.Contains(err.Error(), "did not pass") {
		t.Fatal(err)
	}
}

func TestVerifierPromptKeepsManagedCheckScope(t *testing.T) {
	check := workcycle.Check{ID: "help-links", Scope: "help"}
	input := workcycle.AgentAssessmentRequest{Stage: workcycle.VerifyStage, Request: workcycle.Request{
		RequiredCoverage: []workcycle.Coverage{{CriterionID: "literal", Consumer: "classic"}},
		RequiredChecks:   []workcycle.Check{check},
		ManagedChecks:    []workcycle.Check{check},
	}}
	prompt := agentAssessmentPrompt(input, "/evidence", "base", "/task", "")
	if strings.Contains(prompt, `"id": "check:help-links"`) || !strings.Contains(prompt, "Treat the listed verification cases as the required scope.") {
		t.Fatal("prompt permits the verifier to add a broader managed check")
	}
}

func TestVerificationRejectsMalformedJSONEvidence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "captured-test-report.json")
	started := time.Now().Add(-time.Second)
	if err := os.WriteFile(path, []byte(`{"suites": [`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := readVerificationEvidence(path, started, map[string]string{}); err == nil {
		t.Fatal("truncated report accepted")
	}
}

func TestVerificationNormalizesCurrentAttemptPrefixAndCheckoutReferences(t *testing.T) {
	cfg := agentFixture(t)
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	space := filepath.Join(cfg.OutputDirectory, "evidence", "step-0001-verify")
	if err = os.Mkdir(space, 0700); err != nil {
		t.Fatal(err)
	}
	started := time.Now().Add(-time.Second)
	proof := filepath.Join(space, "product-identity.json")
	if err = os.WriteFile(proof, []byte("{\"deployments\": []}\n{\"pods\": []}"), 0600); err != nil {
		t.Fatal(err)
	}
	badPrefix := filepath.Join(t.TempDir(), filepath.Base(cfg.OutputDirectory), "evidence", filepath.Base(space), filepath.Base(proof))
	request := workcycle.AgentAssessmentRequest{Request: d.Request}
	request.Request.ManagedChecks = request.Request.RequiredChecks
	rows := []workcycle.VerificationResult{{CaseID: "literal@classic", Observed: "Observed the allocated product identity and checked its fixture.", Evidence: []string{badPrefix, "engine/code.txt"}}}
	hashes, err := d.validateVerification(request, rows, space, started)
	if err != nil || len(hashes) != 1 || hashes[proof] == "" || len(rows[0].References) != 1 || len(rows[0].Corrections) != 1 || rows[0].Corrections[0].Resolved != proof {
		t.Fatal(hashes, rows, err)
	}
	for _, bad := range []string{
		filepath.Join(t.TempDir(), "other-run", "evidence", filepath.Base(space), filepath.Base(proof)),
		filepath.Join(filepath.Dir(badPrefix), "unrelated", filepath.Base(proof)),
		"engine/code.txt",
	} {
		rows[0].Evidence = []string{bad}
		if _, err = d.validateVerification(request, rows, space, started); err == nil {
			t.Fatal("accepted unestablished proof", bad)
		}
	}
}

func TestManagedReceiptAllowsOneIndependentSupplementalReassessment(t *testing.T) {
	for _, tc := range []struct {
		name, expect, omit, recheck string
		turns                       int
	}{
		{name: "supplemental", expect: "verified", turns: 1},
		{name: "incomplete coverage", expect: "verification-blocked", omit: "literal@classic"},
		{name: "genuine required blocker", expect: "verification-blocked", recheck: "1", turns: 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Workflow = "agent"
			cfg.RequiredChecks = []workcycle.Check{{ID: "help-links", Scope: "help"}}
			cfg.StaticChecks = []checkrunner.Recipe{{ID: "help-links", Scope: "help", Command: []string{"true"}, CWD: ".", ToolchainCommands: [][]string{{"true"}}}}
			cfg.Environment["QIP_AGENT_VERIFY_SUPPLEMENTAL_BLOCK"] = "1"
			cfg.Environment["QIP_AGENT_VERIFY_OMIT"] = tc.omit
			cfg.Environment["QIP_AGENT_REASSESS_BLOCK"] = tc.recheck
			report, err := Execute(context.Background(), cfg)
			if err != nil || report.Status != tc.expect {
				t.Fatal(report.Status, report.Error, report.Summary, err)
			}
			turns, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-verify-reassessment"))
			if len(turns) != tc.turns {
				t.Fatal(turns)
			}
			if tc.turns == 0 {
				return
			}
			var in workcycle.AgentAssessmentRequest
			readAgentJSON(t, filepath.Join(turns[0], "invocation.json"), &in)
			if in.PreviousVerification == nil || in.PreviousVerification.Decision != "verification-blocked" || !in.PreviousVerification.CoverageValidated ||
				len(in.ManagedResults) != 1 || in.ManagedResults[0].ID != "help-links" || in.ManagedResults[0].Outcome != "pass" {
				t.Fatal("reassessment lost original verdict or check receipt", in.PreviousVerification, in.ManagedResults)
			}
			last := report.Observations[len(report.Observations)-1].Declaration.Assessment
			if last.Reassessment == nil || len(last.Coverage) != 1 || len(last.Coverage[0].Evidence) != 1 {
				t.Fatal(last)
			}
		})
	}
}

func TestFreshFileCannotDisguiseStaleOrSkippedPlaywrightReport(t *testing.T) {
	path := filepath.Join(t.TempDir(), "report.json")
	started := time.Now().Add(-time.Second)
	for _, tc := range []struct {
		name              string
		time              time.Time
		expected, skipped int
		status, result    string
		pass              bool
	}{
		{name: "fresh", time: started.Add(time.Millisecond), expected: 1, status: "expected", result: "passed", pass: true},
		{name: "stale", time: started.Add(-time.Second), expected: 1, status: "expected", result: "passed"},
		{name: "skipped", time: started.Add(time.Millisecond), skipped: 1, status: "skipped", result: "skipped"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			data := []byte(fmt.Sprintf(`{"suites":[{"title":"suite","specs":[{"title":"Live case","tests":[{"status":%q,"expectedStatus":"passed","results":[{"status":%q}]}]}]}],"stats":{"startTime":%q,"expected":%d,"skipped":%d,"unexpected":0,"flaky":0}}`, tc.status, tc.result, tc.time.Format(time.RFC3339Nano), tc.expected, tc.skipped))
			if err := os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			if err := readVerificationEvidence(path, started, map[string]string{}); (err == nil) != tc.pass {
				t.Fatal(err)
			}
		})
	}
}
