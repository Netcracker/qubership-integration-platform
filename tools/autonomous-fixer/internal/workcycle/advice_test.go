package workcycle

import (
	"context"
	"testing"
)

type advisingFake struct {
	*fake
	adviceCalls []AdviceInput
	mutate      bool
}

func (f *advisingFake) Advise(_ context.Context, in AdviceInput) (*RoutingAdvice, error) {
	f.adviceCalls = append(f.adviceCalls, in)
	a := &RoutingAdvice{Phase: in.Phase, CandidateID: in.Candidate.ID, Evidence: "report"}
	if in.Phase == "intake" {
		a.Readiness = "needs_info"
		a.Route = "investigate"
		a.DuplicateCandidates = []string{"other"}
	}
	if in.Phase == "review" {
		a.ExtraReviews = []string{"security", "security"}
		if f.mutate {
			a.CandidateID = "old"
		}
	}
	return a, nil
}
func TestAdviceAddsReviewsWithoutBlockingOrClosing(t *testing.T) {
	f := &advisingFake{fake: newFake()}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || f.reviews != 2 || len(r.Routing) != 3 {
		t.Fatalf("%+v %v calls=%d", r, e, f.reviews)
	}
	if len(f.calls[0].Routing) != 1 || f.calls[0].Stage != ContractStage {
		t.Fatal("intake did not reach contract author")
	}
	if f.calls[3].Lens != "correctness" || f.calls[4].Lens != "security" {
		t.Fatal(f.calls)
	}
}
func TestAdviceRecomputedAfterCandidateRepair(t *testing.T) {
	f := &advisingFake{fake: newFake()}
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage && f.reviews == 1 {
			r.Findings = []Finding{{ID: "f", Location: "engine/code.go", ScenarioID: "scenario", Description: "defect", Evidence: []string{"proof"}}}
		}
	}
	_, e := Run(context.Background(), request(), f)
	if e != nil {
		t.Fatal(e)
	}
	if len(f.adviceCalls) != 4 || f.adviceCalls[2].Candidate.ID == f.adviceCalls[3].Candidate.ID {
		t.Fatal(f.adviceCalls)
	}
}
func TestStaleAdviceCannotSelectReviewers(t *testing.T) {
	f := &advisingFake{fake: newFake(), mutate: true}
	r, e := Run(context.Background(), request(), f)
	if e == nil || r.Status == "verified" || f.reviews != 0 {
		t.Fatalf("%+v %v", r, e)
	}
	if f.adviceCalls[len(f.adviceCalls)-1].Phase != "failure" {
		t.Fatal("failed cycle lacked failure triage")
	}
}

func TestOldCandidateAdviceStaysOutOfFreshReview(t *testing.T) {
	a := currentAdvice([]RoutingAdvice{{Phase: "intake", CandidateID: "base"}, {Phase: "review", CandidateID: "old"}, {Phase: "review", CandidateID: "new"}}, "new")
	if len(a) != 2 || a[1].CandidateID != "new" {
		t.Fatal(a)
	}
}

type weakAdvisor struct{ *fake }

func (f *weakAdvisor) Advise(_ context.Context, in AdviceInput) (*RoutingAdvice, error) {
	return &RoutingAdvice{Phase: in.Phase, CandidateID: in.Candidate.ID, Evidence: "fixture", ReviewCandidates: []string{"security"}}, nil
}
func TestWeakReviewSuggestionRemainsAdvice(t *testing.T) {
	f := &weakAdvisor{newFake()}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || f.reviews != 1 {
		t.Fatalf("%+v %v reviews=%d", r, e, f.reviews)
	}
	if len(f.calls[3].Routing) == 0 {
		t.Fatal("reviewer did not receive the hint")
	}
}
