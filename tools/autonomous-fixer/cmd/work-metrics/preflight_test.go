package main

import (
	"encoding/json"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"path/filepath"
	"testing"
)

func preflightFixture(t *testing.T) string {
	t.Helper()
	root := assessmentFixture(t, false)
	path := filepath.Join(root, "evidence/documentation-routing.json")
	var p routingProof
	if e := readJSON(path, &p); e != nil {
		t.Fatal(e)
	}
	p.Packet.Repository = "repo"
	p.Packet.Scope = "merged implementation"
	p.Packet.Issue = json.RawMessage(`{"number":12}`)
	p.Packet.Complete = false
	p.Report = doctriage.SkipIncomplete(p.Packet, jev.DefaultModel)
	var e error
	p.Routing, e = doctriage.SelectRoute(p.Packet, p.Report, doctriage.DefaultRoutingPolicy())
	if e != nil {
		t.Fatal(e)
	}
	writeJSON(t, path, p)
	writeJSON(t, filepath.Join(root, "routing.json"), p.Routing)
	hash, e := fileDigest(path)
	if e != nil {
		t.Fatal(e)
	}
	writeJSON(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{path: hash})
	return root
}
func TestSkippedClassifierHasKnownZeroCostAndNoFailure(t *testing.T) {
	m, e := collect(preflightFixture(t), nil)
	if e != nil || m.JevReportedUSD == nil || *m.JevReportedUSD != 0 || m.CombinedUSD == nil || m.Counts.SkippedJevRequests != 1 || m.Counts.FailedJevReports != 0 || m.Counts.UnknownJevCost != 0 || m.Counts.NativeInvocations != 1 {
		t.Fatal(m, e)
	}
	if m.Jev[0]["request_attempted"] != false || m.Jev[0]["mode"] != "preflight" {
		t.Fatal(m.Jev)
	}
}
func TestForgedSkippedChargeStaysUnknown(t *testing.T) {
	for _, damage := range []string{"attempted", "complete", "hash"} {
		t.Run(damage, func(t *testing.T) {
			root := preflightFixture(t)
			path := filepath.Join(root, "evidence/documentation-routing.json")
			var p routingProof
			if e := readJSON(path, &p); e != nil {
				t.Fatal(e)
			}
			switch damage {
			case "attempted":
				p.Report.RequestAttempted = true
			case "complete":
				p.Packet.Complete = true
			case "hash":
				p.Report.InputSHA256 = "wrong"
			}
			writeJSON(t, path, p)
			h, e := fileDigest(path)
			if e != nil {
				t.Fatal(e)
			}
			writeJSON(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{path: h})
			m, e := collect(root, nil)
			if e != nil || m.JevReportedUSD != nil || m.CombinedUSD != nil || m.Counts.SkippedJevRequests != 0 || m.Counts.UnknownJevCost != 1 {
				t.Fatal(m, e)
			}
		})
	}
}
func TestUnboundCallerSkipDoesNotInventKnownCost(t *testing.T) {
	root := fixture(t)
	packet := doctriage.Packet{ID: "case", Scope: "source", Repository: "repo", Issue: json.RawMessage(`{"number":12}`)}
	report := doctriage.SkipIncomplete(packet, jev.DefaultModel)
	path := filepath.Join(t.TempDir(), "skip.json")
	writeJSON(t, path, report)
	m, e := collect(root, []string{path})
	if e != nil || m.JevReportedUSD != nil || m.Counts.SkippedJevRequests != 0 || m.Counts.UnknownJevCost != 1 || m.Counts.FailedJevReports != 0 {
		t.Fatal(m, e)
	}
}

func TestVerifiedSkipAndExplicitCopyCountOnce(t *testing.T) {
	root := preflightFixture(t)
	var p routingProof
	if e := readJSON(filepath.Join(root, "evidence/documentation-routing.json"), &p); e != nil {
		t.Fatal(e)
	}
	path := filepath.Join(t.TempDir(), "copy.json")
	writeJSON(t, path, p.Report)
	m, e := collect(root, []string{path})
	if e != nil || m.JevReportedUSD == nil || *m.JevReportedUSD != 0 || m.Counts.SkippedJevRequests != 1 || m.Counts.DuplicateJevReports != 1 || m.Counts.JevReports != 1 {
		t.Fatal(m, e)
	}
}
