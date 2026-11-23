package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func resumedFixture(t *testing.T, route string) (string, string) {
	t.Helper()
	seedRoot := assessmentFixture(t, false)
	var seed workcycle.Report
	if err := readJSON(filepath.Join(seedRoot, "report.json"), &seed); err != nil {
		t.Fatal(err)
	}
	seed.Status = "rejected"
	seed.Error = "final verification declaration rejected"
	seed.Request.DocumentationEntry.Route = route
	seed.Documentation.Decision = "needed"
	seed.Documentation.Reason = "missing_guidance"
	seed.Candidate = workcycle.Snapshot{ID: "patched", ChangedPaths: []string{"help/docs/readme.md"}}
	seed.Observations = []workcycle.Observation{{Stage: "docs-write"}, {Stage: "docs-review"}, {Stage: "docs-verify"}}
	if route == "docs-assess" {
		seed.Observations = append([]workcycle.Observation{{Stage: "docs-assess"}}, seed.Observations...)
	}
	writeJSON(t, filepath.Join(seedRoot, "report.json"), seed)
	if err := os.Remove(filepath.Join(seedRoot, "receipt.json")); err != nil {
		t.Fatal(err)
	}
	var proof routingProof
	seedProof := filepath.Join(seedRoot, "evidence/documentation-routing.json")
	if err := readJSON(seedProof, &proof); err != nil {
		t.Fatal(err)
	}
	proof.Routing.Route = route
	writeJSON(t, seedProof, proof)
	writeJSON(t, filepath.Join(seedRoot, "routing.json"), proof.Routing)
	hash, err := fileDigest(seedProof)
	if err != nil {
		t.Fatal(err)
	}
	writeJSON(t, filepath.Join(seedRoot, "controller-evidence-sha256.json"), map[string]string{seedProof: hash})
	root := t.TempDir()
	var cfg localwork.Config
	if err := readJSON(filepath.Join(seedRoot, "config.json"), &cfg); err != nil {
		t.Fatal(err)
	}
	cfg.ResumeFrom = filepath.Join(seedRoot, "report.json")
	cfg.ResumeMode = "verification"
	writeJSON(t, filepath.Join(root, "config.json"), cfg)
	context, err := os.ReadFile(filepath.Join(seedRoot, "context.json"))
	if err != nil {
		t.Fatal(err)
	}
	writeBytes(t, filepath.Join(root, "context.json"), context)
	currentProof := filepath.Join(root, "evidence/documentation-routing.json")
	writeJSON(t, currentProof, proof)
	writeJSON(t, filepath.Join(root, "routing.json"), proof.Routing)
	writeJSON(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{currentProof: hash})
	seed.Status = "docs-ready-for-pr"
	seed.Error = ""
	seed.Observations = append(seed.Observations, workcycle.Observation{Stage: "docs-verify"})
	writeJSON(t, filepath.Join(root, "report.json"), seed)
	reportHash, err := fileDigest(filepath.Join(root, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	writeJSON(t, filepath.Join(root, "receipt.json"), map[string]any{"report_sha256": reportHash, "context_sha256": "context", "candidate_id": "patched", "evidence_sha256": map[string]string{currentProof: hash}, "prior_evidence_sha256": map[string]string{seedProof: hash}})
	usage(t, process(t, root, "step-001-docs-verify", true, 0))
	return root, seedRoot
}

func TestResumeRetainsOriginalEntryEvidenceAndClassifierCost(t *testing.T) {
	for _, route := range []string{"docs-assess", "docs-write"} {
		t.Run(route, func(t *testing.T) {
			root, seedRoot := resumedFixture(t, route)
			m, err := collect(root, nil)
			if err != nil || m.ExecutionRoute != route || m.Counts.JevReports != 1 || m.JevReportedUSD == nil || *m.JevReportedUSD != .001 || m.CombinedUSD == nil || m.Counts.NativeInvocations != 1 || m.Jev[0]["reused_from_resume"] != true {
				t.Fatalf("m=%+v err=%v (seed %s)", m, err, seedRoot)
			}
		})
	}
}

func TestResumedRoutingRejectsUnverifiedProvenance(t *testing.T) {
	for _, damage := range []string{"no_resume", "other_source", "changed_seed_context", "changed_seed_proof", "changed_seed_manifest", "missing_receipt", "changed_prior_hash"} {
		t.Run(damage, func(t *testing.T) {
			root, seedRoot := resumedFixture(t, "docs-write")
			switch damage {
			case "no_resume", "other_source":
				var cfg localwork.Config
				if err := readJSON(filepath.Join(root, "config.json"), &cfg); err != nil {
					t.Fatal(err)
				}
				if damage == "no_resume" {
					cfg.ResumeFrom = ""
				} else {
					cfg.ResumeFrom = filepath.Join(t.TempDir(), "report.json")
				}
				writeJSON(t, filepath.Join(root, "config.json"), cfg)
			case "changed_seed_context":
				writeJSON(t, filepath.Join(seedRoot, "context.json"), map[string]any{"sha256": "different"})
			case "changed_seed_proof":
				writeJSON(t, filepath.Join(seedRoot, "evidence/documentation-routing.json"), map[string]any{"origin": "different"})
			case "changed_seed_manifest":
				writeJSON(t, filepath.Join(seedRoot, "controller-evidence-sha256.json"), map[string]string{})
			case "missing_receipt":
				if err := os.Remove(filepath.Join(root, "receipt.json")); err != nil {
					t.Fatal(err)
				}
			case "changed_prior_hash":
				var receipt map[string]any
				if err := readJSON(filepath.Join(root, "receipt.json"), &receipt); err != nil {
					t.Fatal(err)
				}
				receipt["prior_evidence_sha256"] = map[string]string{filepath.Join(seedRoot, "evidence/documentation-routing.json"): "wrong"}
				writeJSON(t, filepath.Join(root, "receipt.json"), receipt)
			}
			m, err := collect(root, nil)
			if err != nil || m.JevReportedUSD != nil || m.CombinedUSD != nil || m.Counts.UnknownJevCost != 1 {
				t.Fatalf("m=%+v err=%v", m, err)
			}
		})
	}
}
