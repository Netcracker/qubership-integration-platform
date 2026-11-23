package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docfeedback"
)

func hash(b []byte) string { s := sha256.Sum256(b); return hex.EncodeToString(s[:]) }
func pending(t *testing.T) string {
	t.Helper()
	r := docfeedback.Record{Version: docfeedback.Version, TaskID: "o/r#12:documentation", PacketSHA256: "packet", RequestSHA256: "request", AssessmentStatus: "pending"}
	r.Artifacts = map[string]docfeedback.Reference{"report.json": {SHA256: hash([]byte("native report"))}}
	r.CaseID = hash([]byte(r.TaskID + "\n" + r.PacketSHA256 + "\n" + r.RequestSHA256))
	r.ObservationID = hash([]byte(r.CaseID + "\n\n" + r.Artifacts["report.json"].SHA256))
	b, _ := json.Marshal(r)
	r.ID = hash(b)
	path := filepath.Join(t.TempDir(), "pending.json")
	if e := docfeedback.WriteNew(path, r); e != nil {
		t.Fatal(e)
	}
	return path
}
func TestAdjudicateAndSummaryCommands(t *testing.T) {
	source := pending(t)
	output := filepath.Join(t.TempDir(), "labeled.json")
	args := []string{"adjudicate", "--record", source, "--output", output, "--kind", "context_gap", "--actor", "reviewer", "--actor-type", "human", "--basis", "Required source was absent.", "--at", "2026-11-13T10:00:00Z", "--evidence", "https://github.com/o/r/issues/12#issuecomment-1"}
	if e := run(args); e != nil {
		t.Fatal(e)
	}
	if e := run(args); e == nil {
		t.Fatal("overwritten adjudication")
	}
	if e := run([]string{"summary", source, output}); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(source)
	if e != nil {
		t.Fatal(e)
	}
	var r docfeedback.Record
	if e = json.Unmarshal(b, &r); e != nil || r.Adjudication != nil {
		t.Fatal("original record mutated", e)
	}
}
func TestCommandsRequireExplicitJudgmentAndProvenance(t *testing.T) {
	for _, args := range [][]string{
		{}, {"unknown"}, {"summary"}, {"import", "--run", "missing", "--output", filepath.Join(t.TempDir(), "record.json")},
		{"adjudicate", "--record", pending(t), "--output", filepath.Join(t.TempDir(), "record.json")},
		{"adjudicate", "--record", pending(t), "--output", filepath.Join(t.TempDir(), "record.json"), "--kind", "context_gap", "--actor", "reviewer"},
		{"import", "--run", "missing", "--output", filepath.Join(t.TempDir(), "record.json"), "--actor", "reviewer"},
	} {
		if e := run(args); e == nil {
			t.Fatal("invalid command accepted", args)
		}
	}
}
