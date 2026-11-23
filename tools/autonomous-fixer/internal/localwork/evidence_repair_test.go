package localwork

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestEvidencePrefixCorrectionHasNoFallbackToBasename(t *testing.T) {
	root := t.TempDir()
	current := filepath.Join(root, "evidence", "step-0003-implement")
	if err := os.MkdirAll(filepath.Join(current, "nested"), 0700); err != nil {
		t.Fatal(err)
	}
	for _, file := range []string{"result.log", "nested/result.log"} {
		if err := os.WriteFile(filepath.Join(current, file), []byte("measured"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(current, "empty.log"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(current, "result.log"), filepath.Join(current, "link.log")); err != nil {
		t.Fatal(err)
	}
	for name, suffix := range map[string]string{
		"other invocation":    "evidence/step-0002-reproduce/result.log",
		"relative":            "relative/result.log",
		"wrong suffix":        "evidence/step-0003-implement/unknown/result.log",
		"empty file":          "evidence/step-0003-implement/empty.log",
		"symlink":             "evidence/step-0003-implement/link.log",
		"traversal":           "evidence/step-0003-implement/nested/../result.log",
		"repeated invocation": "evidence/step-0003-implement/evidence/step-0003-implement/result.log",
	} {
		t.Run(name, func(t *testing.T) {
			ref := root + "/missing/" + suffix
			if name == "relative" {
				ref = suffix
			}
			response := workcycle.Response{Evidence: []string{ref}}
			fixed, corrections, err := correctEvidencePrefixes(response, current)
			if err != nil || len(corrections) != 0 || fixed.Evidence[0] != ref {
				t.Fatalf("unexpected correction %+v %v", corrections, err)
			}
		})
	}
	ref := root + "/missing/evidence/step-0003-implement/nested/result.log"
	response := workcycle.Response{Evidence: []string{ref}, Checks: []workcycle.Result{{ID: "check", Outcome: "fail", Evidence: []string{ref}}}}
	fixed, corrections, err := correctEvidencePrefixes(response, current)
	if err != nil || len(corrections) != 1 || fixed.Evidence[0] != filepath.Join(current, "nested/result.log") {
		t.Fatalf("%+v %v", corrections, err)
	}
	if fixed.Checks[0].Outcome != "fail" || response.Evidence[0] != ref {
		t.Fatal("declaration or outcome changed")
	}
}

func TestUnrecoverableEvidenceIsProtocolFailure(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_OLD_EVIDENCE"] = "1"
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	r, err := workcycle.Run(context.Background(), d.Request, d)
	if err == nil || r.Status == "verified" || d.failureKind != "agent_protocol" {
		t.Fatalf("status=%s kind=%s err=%v", r.Status, d.failureKind, err)
	}
}

func TestCompatibilityEvidenceUsesSameArtifactValidation(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "valid.log")
	if err := os.WriteFile(file, []byte("evidence"), 0600); err != nil {
		t.Fatal(err)
	}
	d := Driver{evidence: root}
	r := workcycle.Response{Evidence: []string{file}, Findings: []workcycle.Finding{{Compatibility: &workcycle.CompatibilityRegression{BaselineResult: workcycle.Result{Evidence: []string{filepath.Join(root, "missing.log")}}, CandidateResult: workcycle.Result{Evidence: []string{file}}}}}}
	if err := d.verifyArtifacts(r, root); err == nil || !strings.Contains(err.Error(), "missing.log") {
		t.Fatal("unchecked compatibility evidence", err)
	}
}
