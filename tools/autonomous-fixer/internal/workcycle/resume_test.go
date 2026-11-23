package workcycle

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"
)

func blockedVerificationSeed(t *testing.T) Report {
	t.Helper()
	f := newFake()
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage == VerifyStage {
			r.Question = &Question{Kind: "verification-blocked", Text: "Cannot attribute the preview to this candidate.", Needed: "Restore the candidate preview and rerun fresh scenarios.", Evidence: []string{"identity-proof"}}
		}
	}
	report, err := Run(context.Background(), request(), f)
	if err != nil || report.Status != "verification-blocked" {
		t.Fatal(report, err)
	}
	return report
}
func resumeDriver(seed Report) *fake {
	f := newFake()
	f.snapshot = seed.Candidate
	f.alter = func(i Invocation, r *Response, _ *fake) {
		if i.Stage != VerifyStage {
			panic("continuation invoked an editing or review stage")
		}
		r.DataID = "C"
	}
	return f
}
func TestVerificationResumeRunsOnlyFreshVerification(t *testing.T) {
	seed := blockedVerificationSeed(t)
	before, _ := json.Marshal(seed)
	f := resumeDriver(seed)
	report, err := ResumeVerification(context.Background(), request(), f, seed)
	if err != nil || report.Status != "verified" || len(f.calls) != 1 || f.calls[0].Stage != VerifyStage {
		t.Fatal(report, err, f.calls)
	}
	if len(f.calls[0].PriorVerificationDataIDs) == 0 || f.calls[0].PriorVerificationDataIDs[0] != "B" || f.calls[0].BaselineDataID != "A" {
		t.Fatal(f.calls[0])
	}
	after, _ := json.Marshal(seed)
	if string(before) != string(after) {
		t.Fatal("original checkpoint changed")
	}
	if len(report.Observations) != len(seed.Observations)+1 {
		t.Fatal("history missing")
	}
}
func TestVerificationResumeRejectsIncompleteOrChangedSeed(t *testing.T) {
	cases := map[string]func(*Report){
		"wrong status":     func(r *Report) { r.Status = "needs-human" },
		"altered contract": func(r *Report) { r.Contract.Scenarios[0].Behavior = "different behavior" },
		"missing review":   func(r *Report) { r.Observations = append(r.Observations[:3], r.Observations[4:]...) },
		"failed review": func(r *Report) {
			r.Observations[3].Declaration.Findings = []Finding{{ID: "f", ScenarioID: "scenario", Location: "engine/code", Description: "defect", Evidence: []string{"finding"}}}
		},
		"unreviewed final candidate": func(r *Report) { r.Candidate.ID = "other" },
		"missing baseline":           func(r *Report) { r.Observations[1].Declaration.Scenarios = nil },
		"weakened required scope":    func(r *Report) { r.Request.RequiredChecks[0].Scope = "different" },
		"unreviewed routing direction": func(r *Report) {
			r.Routing = append(r.Routing, RoutingAdvice{Phase: "review", CandidateID: r.Candidate.ID, ExtraReviews: []string{"security"}, Evidence: "routing"})
		},
		"checkpoint stopped before verify": func(r *Report) { r.Observations[len(r.Observations)-1].Stage = ImplementStage },
	}
	for name, damage := range cases {
		t.Run(name, func(t *testing.T) {
			seed := blockedVerificationSeed(t)
			damage(&seed)
			f := resumeDriver(seed)
			report, err := ResumeVerification(context.Background(), request(), f, seed)
			if err == nil || report.Status == "verified" || len(f.calls) != 0 {
				t.Fatal("accepted invalid seed", report, err)
			}
		})
	}
}
func TestVerificationResumeRejectsChangedWorkspaceAndRequest(t *testing.T) {
	for _, change := range []string{"source", "paths", "context"} {
		t.Run(change, func(t *testing.T) {
			seed := blockedVerificationSeed(t)
			f := resumeDriver(seed)
			req := request()
			switch change {
			case "source":
				f.snapshot.ID = "modified"
			case "paths":
				f.snapshot.ChangedPaths = []string{"other/path"}
			case "context":
				req.Context = "different instructions"
			}
			if r, err := ResumeVerification(context.Background(), req, f, seed); err == nil || r.Status == "verified" || len(f.calls) != 0 {
				t.Fatal(r, err)
			}
		})
	}
}
func TestVerificationResumeRequiresFreshCompleteResults(t *testing.T) {
	for _, change := range []string{"baseline A", "prior B", "empty data", "environment", "missing scenario", "missing check", "source edit", "stale candidate"} {
		t.Run(change, func(t *testing.T) {
			seed := blockedVerificationSeed(t)
			f := resumeDriver(seed)
			f.alter = func(i Invocation, r *Response, f *fake) {
				r.DataID = "C"
				switch change {
				case "baseline A":
					r.DataID = "A"
				case "prior B":
					r.DataID = "B"
				case "empty data":
					r.DataID = ""
				case "environment":
					r.EnvironmentID = "other"
				case "missing scenario":
					r.Scenarios = nil
				case "missing check":
					r.Checks = nil
				case "source edit":
					f.snapshot.ID = "modified"
				case "stale candidate":
					r.CandidateID = "other"
				}
			}
			report, err := ResumeVerification(context.Background(), request(), f, seed)
			if err == nil || report.Status == "verified" || len(f.calls) != 1 {
				t.Fatal(report, err)
			}
		})
	}
}
func TestVerificationResumePreservesFailuresWithoutImplementation(t *testing.T) {
	for _, failure := range []string{"scenario", "check", "finding", "question"} {
		t.Run(failure, func(t *testing.T) {
			seed := blockedVerificationSeed(t)
			f := resumeDriver(seed)
			f.alter = func(i Invocation, r *Response, _ *fake) {
				r.DataID = "C"
				switch failure {
				case "scenario":
					r.Scenarios[0].Outcome = "fail"
				case "check":
					r.Checks[0].Outcome = "fail"
				case "finding":
					r.Findings = []Finding{{ID: "new", Evidence: []string{"fresh-finding"}}}
				case "question":
					r.Question = &Question{Kind: "needs-human", Text: "Choose a product behavior.", Needed: "Owner decision", Evidence: []string{"question"}}
				}
			}
			report, err := ResumeVerification(context.Background(), request(), f, seed)
			if err != nil || report.Status == "verified" || report.Question == nil || len(f.calls) != 1 {
				t.Fatal(report, err)
			}
		})
	}
}
func TestVerificationResumeRefreshesManagedChecks(t *testing.T) {
	seed := blockedVerificationSeed(t)
	seed.Request.ManagedChecks = []Check{{ID: "unit", Scope: "module"}}
	f := &staticFake{fake: resumeDriver(seed)}
	report, err := ResumeVerification(context.Background(), seed.Request, f, seed)
	if err != nil || report.Status != "verified" || f.runs != 2 {
		t.Fatal(report, err, f.runs)
	}
	f = &staticFake{fake: resumeDriver(seed), fail: true}
	report, err = ResumeVerification(context.Background(), seed.Request, f, seed)
	if err != nil || report.Status != "verification-blocked" || len(f.calls) != 0 || f.runs != 1 {
		t.Fatal(report, err)
	}
	if !reflect.DeepEqual(report.Candidate, seed.Candidate) {
		t.Fatal("managed failure changed candidate")
	}
	missing := resumeDriver(seed)
	if r, err := ResumeVerification(context.Background(), seed.Request, missing, seed); err == nil || r.Status == "verified" || len(missing.calls) != 0 {
		t.Fatal("missing managed checker accepted", r, err)
	}
}
