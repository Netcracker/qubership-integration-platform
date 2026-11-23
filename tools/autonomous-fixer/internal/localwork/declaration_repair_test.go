package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func summaryRepairFixture(t *testing.T) (*Driver, string, string, workcycle.Response) {
	t.Helper()
	cfg := fixture(t)
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	step := filepath.Join(cfg.OutputDirectory, "step-0001-implement")
	evidence := filepath.Join(d.evidence, filepath.Base(step))
	for _, p := range []string{step, evidence} {
		if err := os.Mkdir(p, 0700); err != nil {
			t.Fatal(err)
		}
	}
	proof := filepath.Join(evidence, "ui-focused-pages-final.log")
	if err := os.WriteFile(proof, []byte("52 tests passed on the current candidate\n"), 0600); err != nil {
		t.Fatal(err)
	}
	missing := filepath.Join(evidence, "implementation-observations.json")
	snapshot, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	response := workcycle.Response{CandidateID: snapshot.ID, Evidence: []string{missing, proof}, Checks: []workcycle.Result{{ID: "ui-focused-pages", Outcome: "pass", Evidence: []string{proof}}}, Handoff: &workcycle.Handoff{Summary: "Updated stale test expectations; focused tests passed.", Commands: []string{"npm test -- --runTestsByPath tests/pages/Chains.test.tsx"}, Deployment: "The product deployment did not change.", Evidence: []string{missing, proof}}}
	return d, step, evidence, response
}

func TestMissingHandoffSummaryUsesControllerDeclarationIndex(t *testing.T) {
	d, step, evidence, original := summaryRepairFixture(t)
	raw, _ := json.Marshal(original)
	if err := os.WriteFile(filepath.Join(step, "response.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
	candidateBefore, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	repaired, err := d.validateOrRepairEvidence(context.Background(), original, evidence, step)
	if err != nil {
		t.Fatal("recoverable summary rejected", err)
	}
	index := filepath.Join(evidence, "controller-declaration-index.json")
	if repaired.Evidence[0] != index || repaired.Handoff.Evidence[0] != index {
		t.Fatal(repaired)
	}
	if repaired.Checks[0].Outcome != "pass" || repaired.Checks[0].Evidence[0] != original.Checks[0].Evidence[0] {
		t.Fatal("measurement declaration changed")
	}
	if _, err := os.Stat(original.Evidence[0]); !os.IsNotExist(err) {
		t.Fatal("missing original file was fabricated", err)
	}
	data, err := os.ReadFile(index)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"is_measurement": false`) {
		t.Fatal("index masquerades as measurement", string(data))
	}
	saved, err := os.ReadFile(filepath.Join(step, "response.json"))
	if err != nil || string(saved) != string(raw) {
		t.Fatal("raw response changed", err)
	}
	unchanged, _ := json.Marshal(original)
	if string(unchanged) != string(raw) {
		t.Fatal("input response mutated")
	}
	candidateAfter, err := d.Snapshot(context.Background())
	if err != nil || candidateAfter.ID != candidateBefore.ID {
		t.Fatal("candidate changed", err)
	}
	audit, err := os.ReadFile(filepath.Join(step, "declaration-repair.json"))
	if err != nil || !strings.Contains(string(audit), "implementation-observations.json") || !strings.Contains(string(audit), `"model_calls": 0`) {
		t.Fatal("repair audit missing", err)
	}
}

func TestSummaryRecoveryDoesNotPromoteFailedMeasurement(t *testing.T) {
	d, step, evidence, response := summaryRepairFixture(t)
	response.Checks[0].Outcome = "fail"
	repaired, err := d.validateOrRepairEvidence(context.Background(), response, evidence, step)
	if err != nil || repaired.Checks[0].Outcome != "fail" {
		t.Fatal(repaired, err)
	}
}

func TestSummaryRecoveryRejectsMissingMeasurementsAndAmbiguousReferences(t *testing.T) {
	for _, damage := range []string{"missing measurement", "empty measurements", "empty typed proof", "image", "unknown metadata", "outside stage", "aggregate only", "empty summary", "index collision", "prior-stage measurement"} {
		t.Run(damage, func(t *testing.T) {
			d, step, evidence, response := summaryRepairFixture(t)
			missing := response.Evidence[0]
			switch damage {
			case "missing measurement":
				response.Checks[0].Evidence = []string{missing}
			case "empty measurements":
				response.Checks = nil
			case "empty typed proof":
				response.Checks[0].Evidence = nil
			case "image":
				missing = filepath.Join(evidence, "missing-summary.png")
				response.Evidence[0] = missing
				response.Handoff.Evidence[0] = missing
			case "unknown metadata":
				missing = filepath.Join(evidence, "test-results.json")
				response.Evidence[0] = missing
				response.Handoff.Evidence[0] = missing
			case "outside stage":
				missing = filepath.Join(filepath.Dir(evidence), "implementation-observations.json")
				response.Evidence[0] = missing
				response.Handoff.Evidence[0] = missing
			case "aggregate only":
				response.Handoff.Evidence = response.Checks[0].Evidence
			case "empty summary":
				response.Handoff.Summary = ""
			case "index collision":
				if err := os.WriteFile(filepath.Join(evidence, "controller-declaration-index.json"), []byte("existing evidence"), 0600); err != nil {
					t.Fatal(err)
				}
			case "prior-stage measurement":
				prior := filepath.Join(d.evidence, "step-0000-implement")
				if err := os.Mkdir(prior, 0700); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(prior, filepath.Base(missing)), []byte("old successful measurement"), 0600); err != nil {
					t.Fatal(err)
				}
				response.Checks[0].Evidence = []string{missing}
			}
			_, err := d.validateOrRepairEvidence(context.Background(), response, evidence, step)
			if err == nil || d.failureKind != "agent_protocol" {
				t.Fatal("invalid repair accepted", err, d.failureKind)
			}
			path := filepath.Join(evidence, "controller-declaration-index.json")
			if damage == "index collision" {
				b, _ := os.ReadFile(path)
				if string(b) != "existing evidence" {
					t.Fatal("existing index overwritten")
				}
			} else if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Fatal("index created without valid measurements", err)
			}
		})
	}
}

func TestDeclarationRepairGuardRejectsSourceAndArtifactChanges(t *testing.T) {
	for _, change := range []string{"source", "evidence", "removed evidence", "new log"} {
		t.Run(change, func(t *testing.T) {
			d, _, evidence, response := summaryRepairFixture(t)
			_, err := d.withImmutableDeclarationInputs(context.Background(), evidence, func(_ workcycle.Snapshot, _ map[string]string) (workcycle.Response, map[string]string, error) {
				var err error
				switch change {
				case "source":
					err = os.WriteFile(filepath.Join(d.Config.Workspace, "engine/code.txt"), []byte("changed source"), 0600)
				case "evidence":
					err = os.WriteFile(response.Checks[0].Evidence[0], []byte("different observation"), 0600)
				case "removed evidence":
					err = os.Remove(response.Checks[0].Evidence[0])
				case "new log":
					err = os.WriteFile(filepath.Join(evidence, "invented-test.log"), []byte("pass"), 0600)
				}
				return response, nil, err
			})
			if err == nil {
				t.Fatal("repair changed immutable inputs")
			}
		})
	}
}

func TestSingleJSONFenceNormalizationIsStrict(t *testing.T) {
	valid := `{"candidate_id":"candidate","checks":[{"id":"unit","outcome":"fail","evidence":["/proof"]}]}`
	for name, tc := range map[string]struct {
		raw                  string
		accepted, normalized bool
	}{
		"plain":          {valid, true, false},
		"json fence":     {"```json\n" + valid + "\n```", true, true},
		"plain fence":    {"```\n" + valid + "\n```", true, true},
		"leading prose":  {"Result:\n```json\n" + valid + "\n```", false, false},
		"trailing prose": {"```json\n" + valid + "\n```\nDone.", false, false},
		"two objects":    {"```json\n" + valid + valid + "\n```", false, false},
		"two fences":     {"```json\n" + valid + "\n```\n```json\n" + valid + "\n```", false, false},
		"invalid JSON":   {"```json\n{broken}\n```", false, false},
		"unknown field":  {"```json\n{\"not_a_result\":true}\n```", false, false},
		"null":           {"null", false, false},
		"fenced null":    {"```json\nnull\n```", false, false},
	} {
		t.Run(name, func(t *testing.T) {
			var response workcycle.Response
			correction, err := decodeEngineeringDeclaration(tc.raw, &response)
			if (err == nil) != tc.accepted || (correction != "") != tc.normalized {
				t.Fatal(correction, err, response)
			}
			if tc.accepted && (response.CandidateID != "candidate" || response.Checks[0].Outcome != "fail") {
				t.Fatal("normalization changed declared result", response)
			}
		})
	}
}

func TestLocalCycleRepairsSummaryAndJSONWithoutExtraModelCalls(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_MISSING_SUMMARY"] = "1"
	cfg.Environment["QIP_WORK_TEST_FENCED_JSON"] = "1"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	steps, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if err != nil || len(steps) != 7 {
		t.Fatal("metadata recovery added a native call", steps, err)
	}
	indexes, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "evidence", "step-*", "controller-declaration-index.json"))
	if err != nil || len(indexes) != 2 {
		t.Fatal(indexes, err)
	}
	raw, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0003-implement", "response-raw.txt"))
	if err != nil || !strings.HasPrefix(string(raw), "```json\n") || !strings.Contains(string(raw), "implementation-observations.json") {
		t.Fatal("raw response not retained", err)
	}
	original, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0003-implement", "response.json"))
	if err != nil || !strings.Contains(string(original), "implementation-observations.json") {
		t.Fatal("original declaration not retained", err)
	}
	var receipt struct {
		Evidence map[string]string `json:"evidence_sha256"`
	}
	data, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "receipt.json"))
	if err != nil || json.Unmarshal(data, &receipt) != nil {
		t.Fatal(err)
	}
	for _, path := range indexes {
		if receipt.Evidence[path] == "" {
			t.Fatal("new index absent from evidence receipt", path)
		}
	}
}

func TestLocalCycleCannotRepairMissingMeasurement(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_MISSING_SUMMARY"] = "1"
	cfg.Environment["QIP_WORK_TEST_MISSING_MEASUREMENT"] = "1"
	report, err := Execute(context.Background(), cfg)
	if err == nil || report.Status != "rejected" {
		t.Fatal(report.Status, err)
	}
	steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(steps) != 3 {
		t.Fatal("missing measurement repeated model work", steps)
	}
	indexes, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "evidence", "step-*", "controller-declaration-index.json"))
	if len(indexes) != 0 {
		t.Fatal("fabricated measurement index", indexes)
	}
}
