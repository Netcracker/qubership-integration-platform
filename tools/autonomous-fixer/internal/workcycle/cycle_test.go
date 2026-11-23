package workcycle

import (
	"context"
	"errors"
	"testing"
)

type fake struct {
	snapshot Snapshot
	calls    []Invocation
	saved    []Report
	alter    func(Invocation, *Response, *fake)
	reviews  int
}

func (f *fake) Snapshot(context.Context) (Snapshot, error) { return f.snapshot, nil }
func (f *fake) Save(_ context.Context, r Report) error     { f.saved = append(f.saved, r); return nil }
func (f *fake) Run(_ context.Context, i Invocation) (Response, error) {
	f.calls = append(f.calls, i)
	r := Response{CandidateID: i.Candidate.ID, EnvironmentID: "env", Evidence: []string{"artifact"}}
	switch i.Stage {
	case ContractStage:
		r.Contract = &Contract{Scenarios: []Scenario{{ID: "scenario", CriterionID: "criterion", Consumer: "classic", Behavior: "preserve value", Procedure: "run scenario", Baseline: "fail"}}, Checks: []Check{{ID: "unit", Scope: "module"}}}
	case ReproduceStage:
		r.DataID = "A"
		r.Scenarios = []Result{{ID: "scenario", Outcome: "fail", Evidence: []string{"baseline"}}}
	case ImplementStage:
		f.snapshot = Snapshot{ID: "edited", ChangedPaths: []string{"engine/code.go"}}
		if len(i.Findings) > 0 {
			f.snapshot.ID = "repaired"
		}
	case ReviewStage:
		f.reviews++
	case VerifyStage:
		r.DataID = "B"
		r.Scenarios = []Result{{ID: "scenario", Outcome: "pass", Evidence: []string{"final"}}}
		r.Checks = []Result{{ID: "unit", Outcome: "pass", Evidence: []string{"unit-log"}}}
	}
	if f.alter != nil {
		f.alter(i, &r, f)
	}
	return r, nil
}
func request() Request {
	return Request{RequiredCoverage: []Coverage{{CriterionID: "criterion", Consumer: "classic"}}, TaskID: "task", Task: "fix", Context: "immutable instructions", EnvironmentID: "env", RequiredCriteria: []string{"criterion"}, RequiredChecks: []Check{{ID: "unit", Scope: "module"}}, RequiredConsumers: []string{"classic"}}
}
func newFake() *fake { return &fake{snapshot: Snapshot{ID: "initial"}} }
func TestControllerOwnsSequence(t *testing.T) {
	f := newFake()
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" {
		t.Fatalf("%+v %v", r, e)
	}
	want := []Stage{ContractStage, ReproduceStage, ImplementStage, ReviewStage, VerifyStage}
	for n, stage := range want {
		if f.calls[n].Stage != stage {
			t.Fatal(f.calls)
		}
	}
	if f.calls[1].Candidate.ID != "initial" {
		t.Fatal("baseline followed an edit")
	}
}
func TestFindingsRequireRepairAndRepeatReview(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage && f.reviews == 1 {
			r.Findings = []Finding{{ID: "f", Location: "engine/code.go", ScenarioID: "scenario", Description: "broken", Evidence: []string{"proof"}}}
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || f.reviews != 2 || r.Candidate.ID != "repaired" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestRejectsIncompleteOrStaleEvidence(t *testing.T) {
	cases := map[string]func(Invocation, *Response, *fake){
		"contract": func(i Invocation, r *Response, f *fake) {
			if i.Stage == ContractStage {
				r.Contract = nil
			}
		},
		"criterion": func(i Invocation, r *Response, f *fake) {
			if i.Stage == ContractStage {
				r.Contract.Scenarios[0].CriterionID = "other"
			}
		},
		"consumer": func(i Invocation, r *Response, f *fake) {
			if i.Stage == ContractStage {
				r.Contract.Scenarios[0].Consumer = "other"
			}
		},
		"review": func(i Invocation, r *Response, f *fake) {
			if i.Stage == ReviewStage {
				r.Evidence = nil
			}
		},
		"scenario": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				r.Scenarios = nil
			}
		},
		"check": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				r.Checks = nil
			}
		},
		"fresh B": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				r.DataID = "A"
			}
		},
		"environment": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				r.EnvironmentID = "different"
			}
		},
		"late edit": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				f.snapshot.ID = "late"
			}
		},
		"stale declaration": func(i Invocation, r *Response, f *fake) {
			if i.Stage == VerifyStage {
				r.CandidateID = "initial"
			}
		},
	}
	for name, alter := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFake()
			f.alter = alter
			r, e := Run(context.Background(), request(), f)
			if e == nil || r.Status == "verified" || len(f.saved) == 0 || f.saved[len(f.saved)-1].Error == "" {
				t.Fatalf("accepted incomplete evidence: %+v %v", r, e)
			}
		})
	}
}
func TestQuestionPreservesDiagnosticWithoutPatch(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ContractStage {
			r.Question = &Question{Kind: "needs-human", Text: "Which consumer owns this behavior?", Needed: "consumer decision", Evidence: []string{"local-investigation"}}
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "needs-human" || r.Question == nil || r.Candidate.ID != "initial" || len(f.calls) != 1 {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestLensPolicyAddsConsumersAndPathRisks(t *testing.T) {
	lenses := reviewLenses(Contract{Scenarios: []Scenario{{Consumer: "a"}, {Consumer: "b"}}, Risks: []string{"concurrency"}}, []string{"shared/auth/migration.go"})
	for _, want := range []string{"correctness", "compatibility", "concurrency", "security", "data"} {
		found := false
		for _, v := range lenses {
			if v == want {
				found = true
			}
		}
		if !found {
			t.Fatal(want, lenses)
		}
	}
}

type failedSave struct{ *fake }

func (f failedSave) Save(context.Context, Report) error { return errors.New("archive unavailable") }
func TestSaveFailureCannotSucceed(t *testing.T) {
	r, e := Run(context.Background(), request(), failedSave{newFake()})
	if e == nil || r.Status == "verified" || r.Error == "" {
		t.Fatal("save failure ignored")
	}
}

func TestVerificationFailureRepairsAndReviewsAgain(t *testing.T) {
	for _, kind := range []string{"scenario", "check"} {
		t.Run(kind, func(t *testing.T) {
			f := newFake()
			failed := false
			f.alter = func(i Invocation, r *Response, f *fake) {
				if i.Stage == VerifyStage && !failed {
					failed = true
					if kind == "scenario" {
						r.Scenarios[0].Outcome = "fail"
					} else {
						r.Checks[0].Outcome = "fail"
					}
				}
			}
			r, e := Run(context.Background(), request(), f)
			if e != nil || r.Status != "verified" || f.reviews != 2 {
				t.Fatalf("%+v %v", r, e)
			}
			found := false
			for _, call := range f.calls {
				if call.Stage == ImplementStage && len(call.Findings) > 0 {
					found = true
				}
			}
			if !found {
				t.Fatal("failed verification did not reach repair")
			}
		})
	}
}
func TestRepeatedUnchangedFindingsNeedHuman(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage {
			r.Findings = []Finding{{ID: "f", Location: "engine/code.go", ScenarioID: "scenario", Description: "broken", Evidence: []string{"proof"}}}
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "needs-human" || r.Question == nil || len(r.Question.Evidence) == 0 {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestSameCodeCanResolveDisputedFinding(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage && f.reviews == 1 {
			r.Findings = []Finding{{ID: "f", Location: "engine/code.go", ScenarioID: "scenario", Description: "broken", Evidence: []string{"proof"}}}
		}
		if i.Stage == ImplementStage && len(i.Findings) > 0 {
			f.snapshot.ID = i.Candidate.ID
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "verified" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestCancellationStopsBeforeInvocation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	f := newFake()
	_, e := Run(ctx, request(), f)
	if !errors.Is(e, context.Canceled) || len(f.calls) != 0 || len(f.saved) != 1 {
		t.Fatal(e, f.calls, f.saved)
	}
}
func TestRejectsMalformedRequirementsAndResults(t *testing.T) {
	for _, kind := range []string{"risk", "location", "duplicate result", "unexpected result", "implement evidence", "after snapshot"} {
		t.Run(kind, func(t *testing.T) {
			f := newFake()
			f.alter = func(i Invocation, r *Response, f *fake) {
				switch kind {
				case "risk":
					if i.Stage == ContractStage {
						r.Contract.Risks = []string{"unrecognized"}
					}
				case "location", "scenario link":
					if i.Stage == ReviewStage {
						r.Findings = []Finding{{ID: "f", Location: "code", ScenarioID: "scenario", Description: "broken", Evidence: []string{"proof"}}}
						if kind == "location" {
							r.Findings[0].Location = ""
						} else {
							r.Findings[0].ScenarioID = "unknown"
						}
					}
				case "duplicate result":
					if i.Stage == VerifyStage {
						r.Scenarios = append(r.Scenarios, r.Scenarios[0])
					}
				case "unexpected result":
					if i.Stage == VerifyStage {
						r.Checks = append(r.Checks, Result{ID: "other", Outcome: "pass", Evidence: []string{"proof"}})
					}
				case "implement evidence":
					if i.Stage == ImplementStage {
						r.Evidence = nil
					}
				case "after snapshot":
					if i.Stage == ImplementStage {
						f.snapshot.ID = ""
					}
				}
			}
			r, e := Run(context.Background(), request(), f)
			if e == nil || r.Status == "verified" {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
	for _, kind := range []string{"criterion", "consumer", "check"} {
		t.Run("duplicate request "+kind, func(t *testing.T) {
			req := request()
			switch kind {
			case "criterion":
				req.RequiredCriteria = append(req.RequiredCriteria, req.RequiredCriteria[0])
			case "consumer":
				req.RequiredConsumers = append(req.RequiredConsumers, req.RequiredConsumers[0])
			case "check":
				req.RequiredChecks = append(req.RequiredChecks, req.RequiredChecks[0])
			}
			f := newFake()
			_, e := Run(context.Background(), req, f)
			if e == nil || len(f.calls) != 0 {
				t.Fatal(e, f.calls)
			}
		})
	}
}

func TestNoChangeCannotVerify(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ImplementStage {
			f.snapshot = i.Candidate
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "needs-human" || r.Question == nil {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestNoRequiredChecksRejectsAfterDiagnosticOpportunity(t *testing.T) {
	req := request()
	req.RequiredChecks = nil
	f := newFake()
	r, e := Run(context.Background(), req, f)
	if e == nil || r.Status == "verified" {
		t.Fatalf("%+v %v", r, e)
	}
	f = newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ContractStage {
			r.Question = &Question{Kind: "needs-human", Text: "Which check?", Needed: "Required check", Evidence: []string{"diagnostic"}}
		}
	}
	r, e = Run(context.Background(), req, f)
	if e != nil || r.Status != "needs-human" {
		t.Fatalf("%+v %v", r, e)
	}
}

type finalSaveFailure struct{ *fake }

func (f finalSaveFailure) Save(_ context.Context, r Report) error {
	if r.Status == "verified" {
		return errors.New("final archive unavailable")
	}
	return nil
}
func TestFinalSaveFailureDowngradesVerifiedReport(t *testing.T) {
	r, e := Run(context.Background(), request(), finalSaveFailure{newFake()})
	if e == nil || r.Status != "rejected" || r.Error == "" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestCoverageRequiresExactCriterionConsumerPairs(t *testing.T) {
	req := request()
	req.RequiredCriteria = []string{"literal", "defaults"}
	req.RequiredConsumers = []string{"classic", "micro"}
	req.RequiredCoverage = []Coverage{{CriterionID: "literal", Consumer: "classic"}, {CriterionID: "literal", Consumer: "micro"}, {CriterionID: "defaults", Consumer: "classic"}, {CriterionID: "defaults", Consumer: "micro"}}
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ContractStage {
			a := r.Contract.Scenarios[0]
			a.CriterionID = "literal"
			b := a
			b.ID = "defaults"
			b.CriterionID = "defaults"
			b.Consumer = "micro"
			r.Contract.Scenarios = []Scenario{a, b}
		}
	}
	r, e := Run(context.Background(), req, f)
	if e == nil || r.Status == "verified" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestCoverageRejectsUnknownAndDuplicatePairs(t *testing.T) {
	for _, kind := range []string{"duplicate", "unknown criterion", "unknown consumer", "empty"} {
		t.Run(kind, func(t *testing.T) {
			req := request()
			switch kind {
			case "duplicate":
				req.RequiredCoverage = append(req.RequiredCoverage, req.RequiredCoverage[0])
			case "unknown criterion":
				req.RequiredCoverage[0].CriterionID = "unknown"
			case "unknown consumer":
				req.RequiredCoverage[0].Consumer = "unknown"
			case "empty":
				req.RequiredCoverage = nil
			}
			r, e := Run(context.Background(), req, newFake())
			if e == nil || r.Status == "verified" {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
}
func TestUnknownReviewScenarioRequestsContractRevision(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage {
			r.Findings = []Finding{{ID: "missing", Location: "engine/code.go:10", ScenarioID: "missing-consumer-case", Description: "Missing scenario", Evidence: []string{"independent-review"}}}
		}
	}
	r, e := Run(context.Background(), request(), f)
	if e != nil || r.Status != "needs-human" || r.Question == nil || r.Question.Evidence[0] != "independent-review" {
		t.Fatalf("%+v %v", r, e)
	}
	if f.calls[len(f.calls)-1].Stage != ReviewStage {
		t.Fatal("scope changed without decision")
	}
}
