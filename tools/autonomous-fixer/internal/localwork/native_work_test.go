package localwork

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestDefaultNativeWorkflowRunsOneAuthorWithReviewersAndNoControllerStages(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = ""
	cfg.Profile = experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "high"}
	catalog := `{"models":[{"slug":"gpt-6-luna","context_window":200000,"supported_reasoning_levels":[{"effort":"high"},{"effort":"xhigh"}]}]}`
	if err := os.WriteFile(cfg.ModelCatalog, []byte(catalog), 0600); err != nil {
		t.Fatal(err)
	}
	cfg.RequiredCoverage = []workcycle.Coverage{{CriterionID: "signed-response", Consumer: "desktop client"}}
	cfg.RequiredCriteria = []string{"signed-response"}
	cfg.RequiredChecks = []workcycle.Check{{ID: "signature-regression", Scope: "wire adapter"}}
	cfg.StaticChecks = []checkrunner.Recipe{{ID: "signature-regression", Scope: "wire adapter", Command: []string{"check-signature", "--offline"}, CWD: "checks/wire"}}
	cfg.RequiredConsumers = []string{"desktop client"}
	cfg.VerificationScenarios = []workcycle.VerificationScenario{{ID: "via-global-list", Coverage: cfg.RequiredCoverage[0], Description: "Open the saved item from the global list."}}
	cfg.ReviewParallelism = 2
	cfg.AuthorHelperProfile = &cfg.Profile
	cfg.EngineeringTriage = &EngineeringTriage{CoverageAudit: "recheck"}
	cfg.Context.Instructions = []string{"absent-frozen-instructions.md"}
	cfg.Environment["QIP_AGENT_OUTCOME"] = "fixed"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Workflow != "fix-issue" || report.Status != "author-reported-fixed" || report.Successful() {
		t.Fatal(report, err)
	}
	if len(report.Observations) != 1 || report.Observations[0].Stage != workcycle.AuthorStage ||
		len(report.StaticCheckRuns) != 0 || len(report.ReviewDecisions) != 0 || len(report.Routing) != 0 {
		t.Fatal("the controller scheduled extra work", report)
	}
	if !strings.Contains(report.Summary, "Fixed the consumer") || len(report.Candidate.ChangedPaths) == 0 {
		t.Fatal("original author result or source change is missing", report)
	}
	steps, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if err != nil || len(steps) != 1 || filepath.Base(steps[0]) != "step-0001-author" {
		t.Fatal(steps, err)
	}
	dir := steps[0]
	for _, expected := range []string{"stdout.jsonl", "stderr.log", "progress.json", "session.json", "process.json", "response-raw.txt", "home/.codex/sessions/author.jsonl"} {
		if _, err := os.Stat(filepath.Join(dir, expected)); err != nil {
			t.Fatalf("missing native record %s: %v", expected, err)
		}
	}
	for _, absent := range []string{"context.json", "receipt.json"} {
		if _, err := os.Stat(filepath.Join(cfg.OutputDirectory, absent)); !os.IsNotExist(err) {
			t.Fatalf("native work generated %s: %v", absent, err)
		}
	}
	prompt, err := os.ReadFile(filepath.Join(dir, "prompt.txt"))
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"--offline", "checks/wire", "signed-response", "desktop client", "signature-regression", "wire adapter", "via-global-list", "Open the saved item from the global list.", nativeFixIssueSkill, filepath.Join(cfg.TaskDirectory, "task.md"), "Codex's native subagents for the review with model gpt-6-luna and xhigh reasoning effort", "choose two small inputs", "Do not commit, push, publish, write to GitHub"} {
		if !strings.Contains(string(prompt), required) {
			t.Fatalf("native prompt omitted %q", required)
		}
	}
	if strings.Contains(string(prompt), "Project conventions.") || strings.Contains(string(prompt), "Controller input:") {
		t.Fatal("native prompt packed the project's instructions")
	}
	var command experimentruntime.Command
	readAgentJSON(t, filepath.Join(dir, "command.json"), &command)
	args := strings.Join(command.Args, " ")
	for _, required := range []string{"--enable multi_agent ", "--disable apps ", "--model gpt-6-luna ", `model_reasoning_effort="high"`, `agents.default_subagent_model="gpt-6-luna"`, `agents.default_subagent_reasoning_effort="xhigh"`} {
		if !strings.Contains(args, required) {
			t.Fatalf("native command omitted %q", required)
		}
	}
}

func TestNativeWorkflowPreservesFailedRuntimeResponse(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "fix-issue"
	cfg.Environment["QIP_AGENT_FAILED"] = "1"
	report, err := Execute(context.Background(), cfg)
	if err == nil || report.Status != "execution-failed" || !strings.Contains(report.Summary, "Fixed the consumer") {
		t.Fatal(report, err)
	}
	var saved workcycle.Report
	readAgentJSON(t, filepath.Join(cfg.OutputDirectory, "report.json"), &saved)
	if saved.Status != report.Status || saved.Summary != report.Summary || saved.Error == "" {
		t.Fatal("saved failure lost the runtime report", saved)
	}
}

func TestNativeDispositionRequiresExplicitFinalLine(t *testing.T) {
	for text, want := range map[string]string{
		"Completed review.\nOutcome: fixed\n":           "author-reported-fixed",
		"Need owner choice.\nOutcome: needs-human":      "needs-human",
		"Cannot reach the preview.\nOutcome: blocked":   "blocked",
		"Outcome: fixed\nI could not run verification.": "author-reported",
		"The runtime completed.":                        "author-reported",
	} {
		if got := authorReportedStatus(text); got != want {
			t.Errorf("status for %q = %q, want %q", text, got, want)
		}
	}
}
