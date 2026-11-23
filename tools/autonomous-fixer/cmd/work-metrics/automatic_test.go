package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func automaticFixture(t *testing.T) string {
	t.Helper()
	root := fixture(t)
	var cfg localwork.Config
	if err := readJSON(filepath.Join(root, "config.json"), &cfg); err != nil {
		t.Fatal(err)
	}
	cfg.ResumeFrom = ""
	cfg.DocumentationTriage = &localwork.DocumentationTriage{PacketPath: "packet.json", ReportPath: "jev.json"}
	writeJSON(t, filepath.Join(root, "config.json"), cfg)
	proofPath := filepath.Join(root, "evidence/documentation-routing.json")
	decision := &workcycle.DocumentationDecision{Decision: "no_change", Reason: "outside_help_scope", Summary: "The change affects build configuration.", IssueComment: "No help update is needed.", Claims: []workcycle.DocClaim{{ID: "scope", Behavior: "Build configuration", Attribution: "unchanged", Action: "No help change", SourceEvidence: []string{proofPath}, DocumentationEvidence: []string{proofPath}}}}
	report := workcycle.Report{Request: workcycle.Request{Kind: "documentation", TaskID: cfg.TaskID, DocumentationEntry: &workcycle.DocumentationEntry{Route: "docs-no-change", Decision: decision, Evidence: []string{proofPath}}}, Status: "docs-no-change", Candidate: workcycle.Snapshot{ID: "candidate"}, Documentation: decision, Observations: []workcycle.Observation{}}
	packet := doctriage.Packet{ID: "issue-12", DocsSHA: "source"}
	data, err := json.Marshal(packet)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data)
	cost := .001
	classifier := doctriage.Report{PacketID: packet.ID, PacketSHA256: hex.EncodeToString(sum[:]), Response: &jev.Response{ID: "request", Model: "typesafe/jev-1.14", Usage: &jev.Usage{Cost: &cost}}}
	routing := doctriage.Routing{Route: "docs-no-change", Recommendation: doctriage.Recommendation{Action: "no_change", Reason: "outside_help_scope", Attribution: "unchanged"}}
	writeJSON(t, proofPath, map[string]any{"origin": "jev-controller-routing", "task_id": cfg.TaskID, "source_sha": "source", "context_sha256": "context", "packet": packet, "report": classifier, "routing": routing})
	writeJSON(t, filepath.Join(root, "routing.json"), routing)
	writeJSON(t, filepath.Join(root, "context.json"), map[string]any{"sha256": "context"})
	writeJSON(t, filepath.Join(root, "report.json"), report)
	proofHash, err := fileDigest(proofPath)
	if err != nil {
		t.Fatal(err)
	}
	reportHash, err := fileDigest(filepath.Join(root, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	writeJSON(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{proofPath: proofHash})
	writeJSON(t, filepath.Join(root, "receipt.json"), map[string]any{"context_sha256": "context", "report_sha256": reportHash, "candidate_id": "candidate", "evidence_sha256": map[string]string{proofPath: proofHash}})
	return root
}

func TestCompletedAutomaticNoChangeHasKnownZeroNativeCost(t *testing.T) {
	root := automaticFixture(t)
	m, err := collect(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	if m.ExecutionRoute != "docs-no-change" || !m.AutomaticNoChangeVerified || m.APIEquivalentUSD == nil || *m.APIEquivalentUSD != 0 || m.CombinedUSD == nil || *m.CombinedUSD != .001 || m.Counts.NativeInvocations != 0 || m.Counts.StageAttempts != 0 || m.SavingsStatus != "not_measured" {
		t.Fatalf("automatic completion not recognized: %+v", m)
	}
}

func TestAutomaticCostRequiresBoundCompletionEvidence(t *testing.T) {
	for _, damage := range []string{"missing_report", "unfinished_status", "missing_receipt", "changed_report", "changed_evidence", "missing_manifest", "changed_route", "changed_context", "process_attempt"} {
		t.Run(damage, func(t *testing.T) {
			root := automaticFixture(t)
			switch damage {
			case "missing_report":
				if err := os.Remove(filepath.Join(root, "report.json")); err != nil {
					t.Fatal(err)
				}
			case "missing_receipt":
				if err := os.Remove(filepath.Join(root, "receipt.json")); err != nil {
					t.Fatal(err)
				}
			case "missing_manifest":
				if err := os.Remove(filepath.Join(root, "controller-evidence-sha256.json")); err != nil {
					t.Fatal(err)
				}
			case "changed_report", "unfinished_status":
				var report workcycle.Report
				if err := readJSON(filepath.Join(root, "report.json"), &report); err != nil {
					t.Fatal(err)
				}
				if damage == "unfinished_status" {
					report.Status = "running"
				} else {
					report.Documentation.Summary = "modified"
				}
				writeJSON(t, filepath.Join(root, "report.json"), report)
			case "changed_evidence":
				writeJSON(t, filepath.Join(root, "evidence/documentation-routing.json"), map[string]any{"origin": "changed"})
			case "changed_route":
				writeJSON(t, filepath.Join(root, "routing.json"), doctriage.Routing{Route: "docs-assess"})
			case "changed_context":
				writeJSON(t, filepath.Join(root, "context.json"), map[string]any{"sha256": "different"})
			case "process_attempt":
				process(t, root, "step-001-docs-assess", false, 0)
			}
			m, err := collect(root, nil)
			if err != nil {
				t.Fatal(err)
			}
			if m.AutomaticNoChangeVerified || m.APIEquivalentUSD != nil || m.CombinedUSD != nil {
				t.Fatalf("invalid completion became zero cost: %+v", m)
			}
		})
	}
}

func TestAssessmentRouteIsVisibleWithoutClaimingFreeExecution(t *testing.T) {
	root := fixture(t)
	m, err := collect(root, nil)
	if err != nil || m.ExecutionRoute != "docs-assess" || m.AutomaticNoChangeVerified || m.APIEquivalentUSD != nil {
		t.Fatalf("%+v %v", m, err)
	}
}

func TestAutomaticClassifierAndExplicitCopyCountOnce(t *testing.T) {
	root := automaticFixture(t)
	external := filepath.Join(t.TempDir(), "jev.json")
	jevReport(t, external, "request", .001, "")
	m, err := collect(root, []string{external})
	if err != nil || m.Counts.JevReports != 1 || m.Counts.DuplicateJevReports != 1 || m.JevReportedUSD == nil || *m.JevReportedUSD != .001 || m.Jev[0]["association"] != "controller_verified" || m.Jev[1]["counted"] != false {
		t.Fatalf("m=%+v, err=%v", m, err)
	}
	jevReport(t, external, "request", .002, "")
	if _, err := collect(root, []string{external}); err == nil {
		t.Fatal("explicit cost conflicts with the consumed classifier")
	}
}

func assessmentFixture(t *testing.T, classifierFailed bool) string {
	t.Helper()
	root := automaticFixture(t)
	proofPath := filepath.Join(root, "evidence/documentation-routing.json")
	var proof routingProof
	if err := readJSON(proofPath, &proof); err != nil {
		t.Fatal(err)
	}
	proof.Routing.Route = "docs-assess"
	if classifierFailed {
		proof.Report.Response = nil
		proof.Report.Error = "unavailable"
	}
	writeJSON(t, proofPath, proof)
	writeJSON(t, filepath.Join(root, "routing.json"), proof.Routing)
	hash, err := fileDigest(proofPath)
	if err != nil {
		t.Fatal(err)
	}
	writeJSON(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{proofPath: hash})
	var report workcycle.Report
	if err := readJSON(filepath.Join(root, "report.json"), &report); err != nil {
		t.Fatal(err)
	}
	report.Request.DocumentationEntry.Route = "docs-assess"
	report.Request.DocumentationEntry.Decision = nil
	report.Observations = []workcycle.Observation{{Stage: "docs-assess"}}
	writeJSON(t, filepath.Join(root, "report.json"), report)
	usage(t, process(t, root, "step-001-docs-assess", true, 0))
	return root
}

func TestAssessedRunIncludesConsumedClassifierCost(t *testing.T) {
	for _, failed := range []bool{false, true} {
		t.Run(map[bool]string{false: "classified", true: "classifier_failed"}[failed], func(t *testing.T) {
			root := assessmentFixture(t, failed)
			m, err := collect(root, nil)
			if err != nil || m.ExecutionRoute != "docs-assess" || m.AutomaticNoChangeVerified || m.APIEquivalentUSD == nil || m.Counts.NativeInvocations != 1 || m.Counts.JevReports != 1 || m.Jev[0]["association"] != "controller_verified" {
				t.Fatalf("m=%+v err=%v", m, err)
			}
			if failed {
				if m.CombinedUSD != nil || m.JevReportedUSD != nil || m.Counts.FailedJevReports != 1 || m.Counts.UnknownJevCost != 1 {
					t.Fatal(m)
				}
			} else if m.CombinedUSD == nil || m.JevReportedUSD == nil || *m.JevReportedUSD != .001 || *m.CombinedUSD <= .001 {
				t.Fatal(m)
			}
		})
	}
}
