package workcycle

import (
	"context"
	"testing"
)

func TestRequiredCheckScopeIsControllerOwned(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage == ContractStage {
			r.Contract.Checks[0].Scope = "Spring Boot engine unit tests"
		}
	}
	r, err := Run(context.Background(), request(), f)
	if err != nil || r.Status != "verified" {
		t.Fatalf("%+v: %v", r, err)
	}
	if r.Contract.Checks[0].Scope != "module" {
		t.Fatal(r.Contract.Checks)
	}
	if r.Observations[0].Declaration.Contract.Checks[0].Scope != "Spring Boot engine unit tests" {
		t.Fatal("raw declaration changed")
	}
}

func TestBaselinePredictionDoesNotOverrideMeasurements(t *testing.T) {
	for _, observed := range []string{"pass", "fail"} {
		t.Run(observed, func(t *testing.T) {
			f := newFake()
			predicted := "pass"
			if observed == "pass" {
				predicted = "fail"
			}
			f.alter = func(i Invocation, r *Response, _ *fake) {
				if i.Stage == ContractStage {
					r.Contract.Scenarios[0].Baseline = predicted
				}
				if i.Stage == ReproduceStage {
					r.Scenarios[0].Outcome = observed
				}
			}
			r, err := Run(context.Background(), request(), f)
			if err != nil || r.Status != "verified" {
				t.Fatalf("%+v: %v", r, err)
			}
			if r.Contract.Scenarios[0].Baseline != observed {
				t.Fatal(r.Contract.Scenarios)
			}
			if r.Observations[0].Declaration.Contract.Scenarios[0].Baseline != predicted {
				t.Fatal("raw prediction changed")
			}
			for _, i := range f.calls {
				if i.Stage == ImplementStage && (i.Contract.Scenarios[0].Behavior != "preserve value" || i.Contract.Scenarios[0].Baseline != observed) {
					t.Fatal(i.Contract)
				}
			}
		})
	}
}

func compatibilityFinding() Finding {
	return Finding{ID: "default-regression", ScenarioID: "default-placeholder", Location: "engine/service:110", Description: "The candidate rejects a default-valued placeholder.", Evidence: []string{"comparison"}, Compatibility: &CompatibilityRegression{
		Scenario:        Scenario{ID: "default-placeholder", CriterionID: "criterion", Consumer: "classic", Behavior: "preserve default placeholder", Procedure: "deploy the same placeholder and call its route", Baseline: "pass"},
		BaselineResult:  Result{ID: "default-placeholder", Outcome: "pass", Evidence: []string{"baseline-default"}},
		CandidateResult: Result{ID: "default-placeholder", Outcome: "fail", Evidence: []string{"candidate-default"}},
	}}
}

func TestConfirmedCompatibilityRegressionAddsCoverageAndRepairs(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage && f.reviews == 1 {
			r.Findings = []Finding{compatibilityFinding()}
		}
		if i.Stage == VerifyStage {
			r.Scenarios = append(r.Scenarios, Result{ID: "default-placeholder", Outcome: "pass", Evidence: []string{"fresh-default"}})
		}
	}
	r, err := Run(context.Background(), request(), f)
	if err != nil || r.Status != "verified" || r.Candidate.ID != "repaired" {
		t.Fatalf("%+v: %v", r, err)
	}
	if len(r.Contract.Scenarios) != 2 || len(r.ContractAdjustments) != 1 || r.ContractAdjustments[0].Kind != "compatibility-regression" {
		t.Fatal(r.Contract, r.ContractAdjustments)
	}
	if f.reviews != 2 {
		t.Fatal("repaired candidate was not reviewed again")
	}
	for _, inv := range f.calls {
		if inv.Stage == ImplementStage && len(inv.Findings) > 0 && len(inv.Contract.Scenarios) != 2 {
			t.Fatal("repair missed new scenario")
		}
	}
}

func TestCompatibilityCoverageCannotBeOmittedAtVerification(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReviewStage && f.reviews == 1 {
			r.Findings = []Finding{compatibilityFinding()}
		}
	}
	r, err := Run(context.Background(), request(), f)
	if err == nil || r.Status == "verified" {
		t.Fatal("new coverage was not required")
	}
}

func TestCompatibilityProofCannotExpandScopeOrReuseEvidence(t *testing.T) {
	cases := map[string]func(*CompatibilityRegression){
		"new criterion":     func(p *CompatibilityRegression) { p.Scenario.CriterionID = "new-requirement" },
		"new consumer":      func(p *CompatibilityRegression) { p.Scenario.Consumer = "new-consumer" },
		"missing procedure": func(p *CompatibilityRegression) { p.Scenario.Procedure = "" },
		"same evidence":     func(p *CompatibilityRegression) { p.CandidateResult.Evidence = p.BaselineResult.Evidence },
		"missing evidence":  func(p *CompatibilityRegression) { p.BaselineResult.Evidence = nil },
		"baseline failure":  func(p *CompatibilityRegression) { p.BaselineResult.Outcome = "fail" },
		"candidate pass":    func(p *CompatibilityRegression) { p.CandidateResult.Outcome = "pass" },
		"wrong scenario":    func(p *CompatibilityRegression) { p.CandidateResult.ID = "other" },
	}
	for name, alter := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFake()
			finding := compatibilityFinding()
			alter(finding.Compatibility)
			f.alter = func(i Invocation, r *Response, _ *fake) {
				if i.Stage == ReviewStage {
					r.Findings = []Finding{finding}
				}
			}
			r, err := Run(context.Background(), request(), f)
			if err == nil || r.Status == "verified" {
				t.Fatalf("accepted %s: %+v", name, r)
			}
			if len(r.Contract.Scenarios) != 1 {
				t.Fatal("invalid proof changed contract")
			}
		})
	}
}

func TestControllerRetainsOmittedChecksAndRejectsDuplicateIDs(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage == ContractStage {
			r.Contract.Checks = nil
		}
	}
	r, err := Run(context.Background(), request(), f)
	if err != nil || r.Status != "verified" || len(r.Contract.Checks) != 1 {
		t.Fatalf("%+v: %v", r, err)
	}
	f = newFake()
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage == ContractStage {
			r.Contract.Checks = append(r.Contract.Checks, r.Contract.Checks[0])
		}
	}
	if _, err := Run(context.Background(), request(), f); err == nil {
		t.Fatal("duplicate check accepted")
	}
}

func TestBaselineReconciliationCannotPassFailingFinalScenario(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage == ContractStage {
			r.Contract.Scenarios[0].Baseline = "pass"
		}
		if i.Stage == VerifyStage {
			r.Scenarios[0].Outcome = "fail"
		}
	}
	r, err := Run(context.Background(), request(), f)
	if err != nil || r.Status != "needs-human" || r.Question == nil {
		t.Fatalf("%+v: %v", r, err)
	}
	if r.Contract.Scenarios[0].Behavior != "preserve value" {
		t.Fatal("expected behavior changed")
	}
}

func TestCompatibilityRequiresAnAcceptedCriterionConsumerPair(t *testing.T) {
	req := request()
	req.RequiredCriteria = append(req.RequiredCriteria, "other-criterion")
	req.RequiredConsumers = append(req.RequiredConsumers, "other-consumer")
	contract := Contract{Scenarios: []Scenario{
		{ID: "first", CriterionID: "criterion", Consumer: "other-consumer", Behavior: "first", Procedure: "first", Baseline: "fail"},
		{ID: "second", CriterionID: "other-criterion", Consumer: "classic", Behavior: "second", Procedure: "second", Baseline: "pass"},
	}}
	if err := validateCompatibility(compatibilityFinding(), req, contract); err == nil {
		t.Fatal("accepted a new criterion/consumer pair")
	}
}
