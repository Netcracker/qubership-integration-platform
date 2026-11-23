package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func engineeringResumeFixture(t *testing.T) (Config, workcycle.Report, string) {
	t.Helper()
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_VERIFY_BLOCKED"] = "1"
	seed, err := Execute(context.Background(), cfg)
	if err != nil || seed.Status != "verification-blocked" {
		t.Fatal(seed.Status, err)
	}
	source := cfg.OutputDirectory
	cfg.ResumeFrom = filepath.Join(source, "report.json")
	cfg.ResumeMode = "verification"
	cfg.OutputDirectory = filepath.Join(filepath.Dir(source), "continuation")
	delete(cfg.Environment, "QIP_WORK_TEST_VERIFY_BLOCKED")
	cfg.Environment["QIP_WORK_TEST_VERIFY_DATA"] = "dataset-C"
	return cfg, seed, source
}
func TestEngineeringVerificationContinuationPreservesSourceAndHistory(t *testing.T) {
	cfg, seed, source := engineeringResumeFixture(t)
	original, err := os.ReadFile(cfg.ResumeFrom)
	if err != nil {
		t.Fatal(err)
	}
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	if len(report.Observations) != len(seed.Observations)+1 {
		t.Fatal("lost history")
	}
	steps, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if err != nil || len(steps) != 1 || !strings.HasSuffix(steps[0], "-verify") {
		t.Fatal(steps, err)
	}
	after, err := os.ReadFile(cfg.ResumeFrom)
	if err != nil || string(after) != string(original) {
		t.Fatal("source report changed", err)
	}
	var inv workcycle.Invocation
	raw, err := os.ReadFile(filepath.Join(steps[0], "invocation.json"))
	if err != nil || json.Unmarshal(raw, &inv) != nil {
		t.Fatal(err)
	}
	if inv.PendingQuestion != nil {
		t.Fatal("old blocked question was forwarded")
	}
	for _, h := range inv.Handoff {
		if h.Stage == workcycle.VerifyStage {
			t.Fatal("old verification entered handoff")
		}
	}
	if len(inv.PriorVerificationDataIDs) == 0 || report.Candidate.ID != seed.Candidate.ID {
		t.Fatal(inv)
	}
	var receipt struct {
		Evidence map[string]string `json:"evidence_sha256"`
		Prior    map[string]string `json:"prior_evidence_sha256"`
	}
	raw, err = os.ReadFile(filepath.Join(cfg.OutputDirectory, "receipt.json"))
	if err != nil || json.Unmarshal(raw, &receipt) != nil || len(receipt.Prior) == 0 {
		t.Fatal("prior evidence not bound", err)
	}
	if receipt.Evidence[filepath.Join(cfg.OutputDirectory, "evidence/resume-source.json")] == "" {
		t.Fatal("resume source not bound")
	}
	for ref := range receipt.Prior {
		if !strings.HasPrefix(ref, filepath.Join(source, "evidence")) {
			t.Fatal(ref)
		}
	}
}
func TestEngineeringResumeRejectsChangedInputsBeforeNative(t *testing.T) {
	for _, damage := range []string{"source", "context", "contract", "review profile", "runtime version", "static recipes", "stage declaration", "evidence", "catalog", "missing review", "unbound routing proof", "product helper"} {
		t.Run(damage, func(t *testing.T) {
			cfg, seed, source := engineeringResumeFixture(t)
			switch damage {
			case "source":
				os.WriteFile(filepath.Join(cfg.Workspace, "engine/code.txt"), []byte("changed source"), 0600)
			case "context":
				os.WriteFile(filepath.Join(cfg.TaskDirectory, "policy.md"), []byte("changed policy"), 0600)
			case "contract":
				seed.Contract.Scenarios[0].Behavior = "different acceptance"
				writeJSON(cfg.ResumeFrom, seed)
			case "review profile":
				cfg.ReviewProfile = &experimentruntime.Profile{Runtime: "codex", Model: "different", Effort: "high"}
			case "runtime version":
				cfg.RuntimeVersion = "different version"
			case "product helper":
				cfg.ProductCheckBinary = cfg.RuntimeBinary
			case "static recipes":
				cfg.StaticChecks = append(cfg.StaticChecks, checkRecipeForResume())
			case "stage declaration":
				writeJSON(filepath.Join(source, "step-0001-contract/response-bound.json"), workcycle.Response{})
			case "evidence":
				os.WriteFile(seed.Observations[0].Declaration.Evidence[0], []byte("changed evidence"), 0600)
			case "catalog":
				os.WriteFile(cfg.ModelCatalog, []byte(`{"models":[]}`), 0600)
			case "unbound routing proof":
				path := filepath.Join(source, "evidence", "unbound-failure.json")
				if err := os.WriteFile(path, []byte(`{"reason":"late failure advice"}`), 0600); err != nil {
					t.Fatal(err)
				}
				seed.Routing = append(seed.Routing, workcycle.RoutingAdvice{Phase: "failure", CandidateID: seed.Candidate.ID, Evidence: path})
				writeJSON(cfg.ResumeFrom, seed)
			case "missing review":
				for i, o := range seed.Observations {
					if o.Stage == workcycle.ReviewStage {
						seed.Observations = append(seed.Observations[:i], seed.Observations[i+1:]...)
						break
					}
				}
				writeJSON(cfg.ResumeFrom, seed)
			}
			_, err := Prepare(context.Background(), cfg)
			if err == nil {
				t.Fatal("accepted changed resume", damage)
			}
			if damage == "unbound routing proof" && !strings.Contains(err.Error(), "no recorded identity") {
				t.Fatal("unexpected rejection", err)
			}
			steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
			if len(steps) > 0 {
				t.Fatal("launched native stage before validation")
			}
		})
	}
}

func checkRecipeForResume() checkrunner.Recipe {
	return checkrunner.Recipe{ID: "module", Scope: "engine", CWD: ".", Command: []string{"sh", "-c", "echo checked"}, ToolchainCommands: [][]string{{"sh", "-c", "echo version"}}}
}
