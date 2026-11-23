package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestLocalManagedCheckRunsOncePerCandidate(t *testing.T) {
	cfg := fixture(t)
	cfg.StaticChecks = []checkrunner.Recipe{{ID: "module", Scope: "engine", CWD: ".", Command: []string{"sh", "-c", "echo checked"}, ToolchainCommands: [][]string{{"sh", "-c", "printf fixture-version"}}}}
	report, e := Execute(context.Background(), cfg)
	if e != nil || report.Status != "verified" {
		t.Fatal(report, e)
	}
	executed, reused := 0, 0
	for _, r := range report.StaticCheckRuns {
		if len(r.Reused) > 0 {
			reused++
		} else {
			executed++
		}
	}
	if executed != 2 || reused != 2 {
		t.Fatal("initial and repaired candidate require their own check", executed, reused, report.StaticCheckRuns)
	}
	var receipt struct {
		Evidence map[string]string `json:"evidence_sha256"`
	}
	f, e := os.Open(filepath.Join(cfg.OutputDirectory, "receipt.json"))
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	if e = json.NewDecoder(f).Decode(&receipt); e != nil {
		t.Fatal(e)
	}
	for _, run := range report.StaticCheckRuns {
		for _, result := range run.Results {
			for _, path := range result.Evidence {
				if receipt.Evidence[path] == "" {
					t.Fatal("unbound static evidence", path)
				}
			}
		}
	}
}
func TestStaticRecipeMustMatchRequiredCheck(t *testing.T) {
	cfg := fixture(t)
	cfg.StaticChecks = []checkrunner.Recipe{{ID: "browser-regression", Scope: "e2e", Command: []string{"true"}, ToolchainCommands: [][]string{{"true"}}}}
	if _, e := Prepare(context.Background(), cfg); e == nil {
		t.Fatal("unknown recipe accepted")
	}
}
func TestStaticResultCannotHideSourceMutation(t *testing.T) {
	cfg := fixture(t)
	cfg.StaticChecks = []checkrunner.Recipe{{ID: "module", Scope: "engine", CWD: ".", Command: []string{"sh", "-c", "echo edited > engine/code.txt"}, ToolchainCommands: [][]string{{"true"}}}}
	d, e := Prepare(context.Background(), cfg)
	if e != nil {
		t.Fatal(e)
	}
	snap, e := d.Snapshot(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if _, e = d.StaticChecks(context.Background(), workcycle.Snapshot{ID: snap.ID}); e == nil {
		t.Fatal("mutating check accepted")
	}
}

func TestManagedFailureUsesCheckDiagnostics(t *testing.T) {
	cfg := fixture(t)
	cfg.StaticChecks = []checkrunner.Recipe{{ID: "module", Scope: "engine", CWD: ".", Command: []string{"sh", "-c", "echo 'error managed check failed'; exit 1"}, ToolchainCommands: [][]string{{"true"}}}}
	d, e := Prepare(context.Background(), cfg)
	if e != nil {
		t.Fatal(e)
	}
	snap, e := d.Snapshot(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if _, e = d.StaticChecks(context.Background(), snap); e != nil {
		t.Fatal(e)
	}
	failure := d.engineeringFailure(workcycle.AdviceInput{Stage: workcycle.Stage("check"), Failure: "check failed"})
	if failure.Stage != "check:module" || failure.ProcessExit == nil || *failure.ProcessExit != 1 || !strings.Contains(failure.Log, "error managed check failed") {
		t.Fatal(failure)
	}
}
