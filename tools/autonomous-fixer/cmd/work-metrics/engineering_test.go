package main

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
)

type engineeringClient struct{ fail bool }

func (c engineeringClient) Classify(_ context.Context, q jev.Request) (jev.Response, error) {
	if c.fail {
		return jev.Response{}, errors.New("network unavailable")
	}
	cost := .003
	r := jev.Response{ID: "engineering", Model: jev.DefaultModel, Usage: &jev.Usage{Cost: &cost}, Answers: map[string]jev.Answer{}}
	for id, v := range q.Questions {
		p := map[string]float64{}
		for k := range v.Criteria {
			p[k] = 0
		}
		p["uncertain"] = 1
		r.Answers[id] = jev.Answer{Type: "choice", Choice: "uncertain", Probabilities: p}
	}
	return r, nil
}
func TestEngineeringCostsIncludeRepeatedEvidenceOnlyOnce(t *testing.T) {
	root := fixture(t)
	p := tasktriage.Packet{TaskID: "repo#12:documentation", Phase: "intake", Task: "fix"}
	r, e := tasktriage.Evaluate(context.Background(), p, engineeringClient{})
	if e != nil {
		t.Fatal(e)
	}
	for _, n := range []string{"1", "2"} {
		writeJSON(t, filepath.Join(root, "evidence", "engineering-triage-"+n+".json"), r)
	}
	m, e := collect(root, nil)
	if e != nil || m.JevKnownUSD != .003 || m.Counts.DuplicateJevReports != 1 {
		t.Fatalf("%+v %v", m, e)
	}
}
func TestEngineeringFailedRequestsRemainUnknown(t *testing.T) {
	root := fixture(t)
	p := tasktriage.Packet{TaskID: "repo#12:documentation", Phase: "intake", Task: "fix"}
	r, _ := tasktriage.Evaluate(context.Background(), p, engineeringClient{fail: true})
	writeJSON(t, filepath.Join(root, "evidence", "engineering-triage-1.json"), r)
	m, e := collect(root, nil)
	if e != nil || m.JevReportedUSD != nil || m.Counts.UnknownJevCost != 1 {
		t.Fatalf("%+v %v", m, e)
	}
}
func TestEngineeringRuleHasZeroInferenceCost(t *testing.T) {
	root := fixture(t)
	p := tasktriage.Packet{TaskID: "repo#12:documentation", Phase: "failure", Task: "fix", Failure: &tasktriage.Failure{Kind: "agent_protocol", Message: "invalid JSON"}}
	r, _ := tasktriage.Evaluate(context.Background(), p, nil)
	writeJSON(t, filepath.Join(root, "evidence", "engineering-triage-1.json"), r)
	m, e := collect(root, nil)
	if e != nil || m.JevReportedUSD == nil || *m.JevReportedUSD != 0 || m.Counts.SkippedJevRequests != 1 {
		t.Fatalf("%+v %v", m, e)
	}
}
