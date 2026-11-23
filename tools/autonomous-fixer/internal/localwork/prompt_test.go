package localwork

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestLongContextNeverUsesOneCommandArgument(t *testing.T) {
	prompt := strings.Repeat("complete instructions\n", 20000) + "MANDATORY FINAL RULE"
	path := filepath.Join(t.TempDir(), "prompt.txt")
	if err := os.WriteFile(path, []byte(prompt), 0600); err != nil {
		t.Fatal(err)
	}
	for _, profile := range []experimentruntime.Profile{{Runtime: "codex", Model: "fixture", Effort: "medium"}, {Runtime: "opencode", Model: "fixture/model", Effort: "high"}} {
		c, err := runtimeCommand(Config{Workspace: t.TempDir(), RuntimeBinary: "/fixture", Profile: profile}, prompt, path)
		if err != nil {
			t.Fatal(err)
		}
		for _, arg := range c.Args {
			if len(arg) > 1024 {
				t.Fatal("full context placed in argv")
			}
		}
		if profile.Runtime == "codex" && c.Stdin != prompt {
			t.Fatal("stdin context changed")
		}
		if profile.Runtime == "opencode" {
			found := false
			for i, a := range c.Args {
				if a == "--file" && i+1 < len(c.Args) && c.Args[i+1] == path {
					found = true
				}
			}
			if !found {
				t.Fatal("context attachment omitted")
			}
			data, _ := os.ReadFile(path)
			if string(data) != prompt {
				t.Fatal("attachment truncated")
			}
		}
	}
}

func TestPromptKeepsInternalFileDigestsOutOfModelInput(t *testing.T) {
	for _, kind := range []string{"", "documentation"} {
		inv := workcycle.Invocation{Request: workcycle.Request{Kind: kind}, Candidate: workcycle.Snapshot{ID: "opaque-candidate", ChangedPaths: []string{"ui/src/page.tsx"}, FileHashes: map[string]string{"ui/src/page.tsx": "mode-prefixed-digest"}}}
		prompt := Prompt(inv, "/tmp/evidence/step-0001-verify", "base")
		if strings.Contains(prompt, "file_hashes") || strings.Contains(prompt, "mode-prefixed-digest") {
			t.Fatal("internal digest exposed", kind)
		}
		if !strings.Contains(prompt, "opaque-candidate") || !strings.Contains(prompt, "ui/src/page.tsx") || !strings.Contains(prompt, "opaque controller identifier") {
			t.Fatal("source identity guidance omitted", kind)
		}
		if inv.Candidate.FileHashes["ui/src/page.tsx"] != "mode-prefixed-digest" || inv.Request.Context != "" {
			t.Fatal("prompt changed the archived invocation")
		}
	}
}

func TestRepairPromptKeepsWorkFocusedAndFinalVerificationFresh(t *testing.T) {
	inv := workcycle.Invocation{Stage: workcycle.ImplementStage, Findings: []workcycle.Finding{{ID: "static-check:unit"}}}
	repair := Prompt(inv, "/tmp/evidence/repair", "base")
	for _, text := range []string{"focused repair", "test-expectation-only", "fresh final dataset B", "existing tests for affected files early", "working_directory", "environment_keys"} {
		if !strings.Contains(repair, text) {
			t.Fatalf("missing %q", text)
		}
	}
	reproduce := Prompt(workcycle.Invocation{Stage: workcycle.ReproduceStage}, "/tmp/evidence/baseline", "base")
	if !strings.Contains(reproduce, "permanent regression test now") || strings.Contains(reproduce, "Keep source and tests unchanged") {
		t.Fatal("reproduction still forbids the regression test")
	}
	verify := Prompt(workcycle.Invocation{Stage: workcycle.VerifyStage}, "/tmp/evidence/verify", "base")
	if !strings.Contains(verify, "Keep source and tests unchanged") || !strings.Contains(verify, "Every product scenario must run freshly") {
		t.Fatal("final verification weakened")
	}
}
