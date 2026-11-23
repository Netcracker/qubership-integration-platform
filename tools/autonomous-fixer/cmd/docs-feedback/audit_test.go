package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docfeedback"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

func auditRecord(t *testing.T, head, docs, origin, observation string) string {
	t.Helper()
	r := docfeedback.Record{Version: docfeedback.Version, Repository: "o/r", Issue: 12, BaseSHA: "base", ImplementationHeadSHA: head, SourceSHA: docs, DocsSHA: docs, TaskID: "o/r#12:documentation", PacketSHA256: hash([]byte(head + docs)), RequestSHA256: "request", AssessmentStatus: "pending", Routing: doctriage.Routing{Route: "docs-no-change"}, Observed: docfeedback.Outcome{Status: "docs-no-change", Decision: "no_change", Origin: origin}, Prediction: docfeedback.Prediction{RequestAttempted: true, Response: &jev.Response{ID: observation, Answers: map[string]jev.Answer{"documentation_action": {Type: "choice", Choice: "no_change", Probabilities: map[string]float64{"no_change": 1}}}}}}
	r.Artifacts = map[string]docfeedback.Reference{"report.json": {SHA256: hash([]byte("native report" + observation))}}
	r.CaseID = hash([]byte(r.TaskID + "\n" + r.PacketSHA256 + "\n" + r.RequestSHA256))
	r.ObservationID = hash([]byte(r.CaseID + "\n" + observation + "\n" + r.Artifacts["report.json"].SHA256))
	b, _ := json.Marshal(r)
	r.ID = hash(b)
	path := filepath.Join(t.TempDir(), "record.json")
	if e := docfeedback.WriteNew(path, r); e != nil {
		t.Fatal(e)
	}
	return path
}
func TestAuditDeduplicatesRevisionAndIsIndependentOfInputOrder(t *testing.T) {
	a := auditRecord(t, "head", "docs", "classifier", "first")
	b := auditRecord(t, "head", "docs", "classifier", "repeat")
	c := auditRecord(t, "head", "new-docs", "classifier", "updated")
	assessedByAgent := auditRecord(t, "head", "docs", "agent", "assessor")
	result, e := auditRecords([]string{a, b, c, a, assessedByAgent}, "hosted", 1)
	if e != nil || result.EligibleRevisions != 2 || result.PendingRevisions != 2 || len(result.Candidates) != 2 {
		t.Fatal(result, e)
	}
	reversed, e := auditRecords([]string{c, b, a, assessedByAgent}, "hosted", 1)
	if e != nil || !reflect.DeepEqual(result, reversed) {
		t.Fatal("input order changed sample", result, reversed, e)
	}
	count := 0
	for _, candidate := range result.Candidates {
		count += len(candidate.ObservationIDs)
	}
	if count != 3 {
		t.Fatal("repeated observations lost", result)
	}
	none, e := auditRecords([]string{a, b, c}, "hosted", 0)
	if e != nil || len(none.Candidates) != 0 || none.PendingRevisions != 2 {
		t.Fatal(none, e)
	}
}
func TestAuditSkipsRevisionWithSuppliedJudgmentAndPreservesCorrection(t *testing.T) {
	pending := auditRecord(t, "head", "docs", "classifier", "first")
	repeated := auditRecord(t, "head", "docs", "classifier", "repeat")
	record, e := readRecord(pending)
	if e != nil {
		t.Fatal(e)
	}
	corrected, e := docfeedback.Adjudicate(record, docfeedback.Adjudication{Kind: "missed_needed_change", Actor: "Terra 6.1", ActorType: "model", Basis: "The changed workflow is absent from help.", Timestamp: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC), Evidence: []docfeedback.Reference{{Path: "https://github.com/o/r/issues/12#issuecomment-1"}}})
	if e != nil {
		t.Fatal(e)
	}
	path := filepath.Join(t.TempDir(), "judgment.json")
	if e = docfeedback.WriteNew(path, corrected); e != nil {
		t.Fatal(e)
	}
	result, e := auditRecords([]string{pending, repeated, path}, "hosted", 1)
	if e != nil || result.AlreadyAssessedRevisions != 1 || result.PendingRevisions != 0 || len(result.Candidates) != 0 {
		t.Fatal(result, e)
	}
	summary, e := docfeedback.Summarize([]docfeedback.Record{record, corrected})
	if e != nil || summary.Groups[0].ModelDeclaredJudgments["missed_needed_change"] != 1 {
		t.Fatal(summary, e)
	}
	next := auditRecord(t, "head", "new-docs", "classifier", "later")
	result, e = auditRecords([]string{pending, path, next}, "hosted", 1)
	if e != nil || len(result.Candidates) != 1 || result.Candidates[0].Sample.Identity.DocsSHA != "new-docs" {
		t.Fatal(result, e)
	}
}
func TestAuditCommandWritesOnlyNewCandidateArtifact(t *testing.T) {
	source := auditRecord(t, "head", "docs", "classifier", "first")
	before, _ := os.ReadFile(source)
	output := filepath.Join(t.TempDir(), "audit.json")
	args := []string{"audit", "--seed", "hosted", "--fraction", "1", "--output", output, source}
	if e := run(args); e != nil {
		t.Fatal(e)
	}
	if e := run(args); e == nil {
		t.Fatal("audit artifact overwritten")
	}
	b, e := os.ReadFile(output)
	var result auditResult
	if e != nil || json.Unmarshal(b, &result) != nil || len(result.Candidates) != 1 {
		t.Fatal(string(b), e)
	}
	after, _ := os.ReadFile(source)
	if string(before) != string(after) {
		t.Fatal("source feedback changed")
	}
	for _, bad := range [][]string{{"audit"}, {"audit", "--output", filepath.Join(t.TempDir(), "missing-policy.json"), source}, {"audit", "--seed", "hosted", "--fraction", "NaN", "--output", filepath.Join(t.TempDir(), "nan.json"), source}} {
		if e := run(bad); e == nil {
			t.Fatal("invalid audit command accepted", bad)
		}
	}
}
