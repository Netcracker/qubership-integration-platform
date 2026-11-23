package workcycle

import (
	"context"
	"testing"
)

func TestAuthorHandoffExcludesIndependentReview(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReproduceStage {
			r.Handoff = &Handoff{Recipes: []CommandRecipe{{Purpose: "reproduce", Argv: []string{"npm", "test"}, WorkingDirectory: "/checkout/ui", EnvironmentKeys: []string{"CIP_TARGET"}}}, Summary: "Observed the baseline", Commands: []string{"run-reproduction"}, Deployment: "candidate image", Evidence: []string{"recipe"}}
		}
		if i.Stage == ImplementStage {
			if len(i.Handoff) != 2 || i.Handoff[1].Details.Commands[0] != "run-reproduction" || i.Handoff[1].Details.Recipes[0].WorkingDirectory != "/checkout/ui" {
				t.Fatal(i.Handoff)
			}
		}
		if i.Stage == ReviewStage && len(i.Handoff) != 0 {
			t.Fatal("review received author handoff")
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" {
		t.Fatal(r, e)
	}
}

func TestObservableQuestionGetsOneAdditionalPass(t *testing.T) {
	for _, resolved := range []bool{true, false} {
		f := newFake()
		probes := 0
		f.alter = func(i Invocation, r *Response, f *fake) {
			if i.Stage != ReviewStage {
				return
			}
			probes++
			if probes == 1 || !resolved {
				r.Question = &Question{Kind: "verification-blocked", Text: "Does import reach this parser?", Needed: "Run the product import", Evidence: []string{"probe"}}
			}
			if probes == 2 && i.PendingQuestion == nil {
				t.Fatal("probe lost question")
			}
		}
		r, e := Run(context.Background(), request(), f)
		if e != nil || probes != 2 {
			t.Fatal(r, e, probes)
		}
		if resolved && r.Status != "verified" {
			t.Fatal(r)
		}
		if !resolved && r.Status != "verification-blocked" {
			t.Fatal(r)
		}
	}
}

func TestReviewReuseRequiresKnownTestOnlyDelta(t *testing.T) {
	a := Snapshot{ID: "a", FileHashes: map[string]string{"ui/src/page.ts": "1", "ui/tests/page.test.ts": "1"}}
	b := Snapshot{ID: "b", FileHashes: map[string]string{"ui/src/page.ts": "1", "ui/tests/page.test.ts": "2"}}
	if !canReuseReview("security", a, b, false) {
		t.Fatal("clean review was not retained")
	}
	if canReuseReview("correctness", a, b, false) || canReuseReview("security", a, b, true) {
		t.Fatal("required followup was skipped")
	}
	b.FileHashes["ui/src/page.ts"] = "2"
	if canReuseReview("security", a, b, false) {
		t.Fatal("production change reused review")
	}
	if canReuseReview("security", Snapshot{ID: "unknown"}, b, false) {
		t.Fatal("unknown delta reused review")
	}
}

type staticFake struct {
	*fake
	runs int
	fail bool
}

func (f *staticFake) StaticChecks(_ context.Context, s Snapshot) (StaticCheckRun, error) {
	f.runs++
	outcome := "pass"
	if f.fail && f.runs == 1 {
		outcome = "fail"
	}
	return StaticCheckRun{CandidateID: s.ID, Results: []Result{{ID: "unit", Outcome: outcome, Evidence: []string{"controller-log"}}}}, nil
}
func TestManagedChecksDoNotReplaceFreshProductScenarios(t *testing.T) {
	f := &staticFake{fake: newFake()}
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == VerifyStage {
			r.Checks = nil
			if len(i.StaticChecks) != 1 {
				t.Fatal("missing static proof")
			}
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || f.runs != 3 {
		t.Fatal(r, e, f.runs)
	}
	f = &staticFake{fake: newFake()}
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == VerifyStage {
			r.Scenarios = nil
		}
	}
	r, e = Run(context.Background(), request(), f)
	if e == nil || r.Status == "verified" {
		t.Fatal("static checks replaced product coverage")
	}
}
func TestFailedManagedCheckReturnsToAuthor(t *testing.T) {
	f := &staticFake{fake: newFake(), fail: true}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" {
		t.Fatal(r, e)
	}
	if f.calls[3].Stage != ImplementStage || len(f.calls[3].Findings) != 1 {
		t.Fatal("failed check skipped repair", f.calls)
	}
}

type questionAdvisor struct {
	*fake
	action string
}

func (f *questionAdvisor) Advise(_ context.Context, in AdviceInput) (*RoutingAdvice, error) {
	return &RoutingAdvice{Phase: in.Phase, CandidateID: in.Candidate.ID, Evidence: "advice", QuestionAction: f.action}, nil
}
func TestQuestionClassificationCannotApproveBehavior(t *testing.T) {
	for _, kind := range []string{"needs-human", "verification-blocked"} {
		f := &questionAdvisor{fake: newFake(), action: "owner_decision"}
		f.alter = func(i Invocation, r *Response, _ *fake) {
			if i.Stage == ReviewStage {
				r.Question = &Question{Kind: kind, Text: "Choose behavior", Needed: "Owner decision", Evidence: []string{"proof"}}
			}
		}
		r, e := Run(context.Background(), request(), f)
		if e != nil || r.Status != kind || f.reviews != 1 {
			t.Fatal(r, e, f.reviews)
		}
	}
}

func TestRepairRetainsCleanSpecialistOnlyForTestDelta(t *testing.T) {
	for _, productionChange := range []bool{false, true} {
		f := newFake()
		specialist := 0
		f.alter = func(i Invocation, r *Response, f *fake) {
			if i.Stage == ContractStage {
				r.Contract.Risks = []string{"security"}
			}
			if i.Stage == ImplementStage {
				source := "same"
				test := "first"
				if len(i.Findings) > 0 {
					test = "second"
					if productionChange {
						source = "changed"
					}
				}
				f.snapshot.FileHashes = map[string]string{"engine/code.go": source, "engine/code_test.go": test}
				f.snapshot.ChangedPaths = []string{"engine/code.go", "engine/code_test.go"}
			}
			if i.Stage == ReviewStage && i.Lens == "correctness" && i.Candidate.ID == "edited" {
				r.Findings = []Finding{{ID: "f", Location: "engine/code_test.go", ScenarioID: "scenario", Description: "Missing assertion", Evidence: []string{"proof"}}}
			}
			if i.Stage == ReviewStage && i.Lens == "security" {
				specialist++
			}
		}
		r, e := Run(context.Background(), request(), f)
		if e != nil || r.Status != "verified" {
			t.Fatal(r, e)
		}
		want := 1
		if productionChange {
			want = 2
		}
		if specialist != want {
			t.Fatal(productionChange, specialist)
		}
	}
}
