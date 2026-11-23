package doccycle

import (
	"context"
	"testing"
)

func TestReplayNoChangeDoesNotInvokeModel(t *testing.T) {
	for _, kind := range []string{"already_covered", "internal_only", "outside_help_scope"} {
		t.Run(kind, func(t *testing.T) {
			seed, e, _ := run(t, assess(kind))
			if e != nil {
				t.Fatal(e)
			}
			d := &driver{t: t, snapshot: clone(seed.Candidate)}
			r, e := ReplayNoChangeAssessment(context.Background(), request(), d, seed)
			if e != nil || r.Status != "docs-no-change" || len(d.inputs) != 0 || len(r.Observations) != 1 {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
}
func TestReplayRejectsInvalidSeed(t *testing.T) {
	for _, kind := range []string{"needed", "investigate", "request", "stage", "mutation", "dirty", "workspace", "comment"} {
		t.Run(kind, func(t *testing.T) {
			seed, e, _ := run(t, assess("already_covered"))
			if e != nil {
				t.Fatal(e)
			}
			req := request()
			snapshot := clone(seed.Candidate)
			switch kind {
			case "needed", "investigate":
				seed.Observations[0].Declaration.Documentation.Decision = kind
			case "request":
				req.Context = "Different context."
			case "stage":
				seed.Observations[0].Stage = ReviewStage
			case "mutation":
				seed.Observations[0].After.ID = "changed"
			case "dirty":
				seed.Observations[0].Before.ChangedPaths = []string{"engine/a.java"}
			case "workspace":
				snapshot.ID = "changed"
			case "comment":
				seed.Observations[0].Declaration.Documentation.IssueComment = ""
			}
			d := &driver{t: t, snapshot: snapshot}
			r, e := ReplayNoChangeAssessment(context.Background(), req, d, seed)
			if e == nil || r.Status != "rejected" || len(d.inputs) != 0 || len(d.saves) == 0 {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
}
