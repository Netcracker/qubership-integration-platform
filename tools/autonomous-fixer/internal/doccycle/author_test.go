package doccycle

import (
	"context"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAuthorRejectsUnpublishableCandidate(t *testing.T) {
	for _, tc := range []struct {
		name   string
		damage func(*step)
	}{
		{"failed check", func(s *step) { s.response.Checks[0].Outcome = "fail" }},
		{"out-of-scope change", func(s *step) { s.paths = []string{"ui/package.json"} }},
		{"wrong invocation", func(s *step) { s.response.CandidateID = s.next }},
		{"no-change result with edits", func(s *step) { s.response.Documentation = decision("already_covered") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := write("b")
			s.stage = AuthorStage
			s.response.Checks = verify().response.Checks
			tc.damage(&s)
			d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{s}}
			report, err := RunAuthor(context.Background(), request(), d)
			if err == nil || report.Status != "rejected" || len(d.inputs) != 1 {
				t.Fatalf("status %s after %d invocations: %v", report.Status, len(d.inputs), err)
			}
		})
	}
}

func TestAuthorReportsVerificationBlockerWithoutAnotherInvocation(t *testing.T) {
	s := write("b")
	s.stage = AuthorStage
	s.response.Question = &workcycle.Question{
		Kind: "verification-blocked", Text: "The help index could not build.",
		Needed: "Restore access to the required package.", Evidence: []string{"index.log"},
	}
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{s}}
	report, err := RunAuthor(context.Background(), request(), d)
	if err != nil || report.Status != "verification-blocked" || report.Question == nil || report.Successful() || len(d.inputs) != 1 {
		t.Fatalf("status %s after %d invocations: %v", report.Status, len(d.inputs), err)
	}
}
