package doccycle

import (
	"context"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func resumeSeed(t *testing.T) workcycle.Report {
	t.Helper()
	r, e, _ := run(t, assess("needed"), write("b"), review(), verify())
	if e != nil {
		t.Fatal(e)
	}
	r.Status = "rejected"
	r.Error = "response candidate ID is stale or missing"
	r.Observations[len(r.Observations)-1].Declaration.CandidateID = "truncated"
	return r
}
func TestResumeVerificationOnly(t *testing.T) {
	seed := resumeSeed(t)
	v := verify()
	v.response.Documentation = decision("needed")
	d := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{v}}
	r, e := ResumeVerification(context.Background(), request(), d, seed)
	if e != nil || r.Status != "docs-ready-for-pr" || r.Error != "" || len(d.inputs) != 1 || d.inputs[0].Stage != VerifyStage || len(r.Observations) != len(seed.Observations)+1 {
		t.Fatalf("%+v %v", r, e)
	}
	if seed.Error == "" || len(seed.Observations) != 4 {
		t.Fatal("seed changed")
	}
}
func TestResumeRejectsInvalidCheckpoint(t *testing.T) {
	for _, kind := range []string{"request", "review", "no-write", "snapshot", "source", "missing-check", "stale-response", "changed-verification"} {
		t.Run(kind, func(t *testing.T) {
			seed := resumeSeed(t)
			req := request()
			v := verify()
			v.response.Documentation = decision("needed")
			snapshot := clone(seed.Candidate)
			switch kind {
			case "request":
				req.Context = "Another context."
			case "review":
				seed.Observations[2].Declaration.Findings = []workcycle.Finding{{ID: "unresolved"}}
			case "no-write":
				seed.Observations = append(seed.Observations[:1], seed.Observations[2:]...)
			case "snapshot":
				snapshot.ID = "other"
			case "source":
				seed.Observations[1].After.ChangedPaths = []string{"engine/a.java"}
			case "missing-check":
				v.response.Checks = nil
			case "stale-response":
				v.response.CandidateID = "old"
			case "changed-verification":
				v.next = "c"
			}
			d := &driver{t: t, snapshot: snapshot, steps: []step{v}}
			r, e := ResumeVerification(context.Background(), req, d, seed)
			if e == nil || r.Status != "rejected" || len(d.saves) == 0 {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
}
func TestResumeFailedCheckBlocksDelivery(t *testing.T) {
	seed := resumeSeed(t)
	v := verify()
	v.response.Documentation = decision("needed")
	v.response.Checks[0].Outcome = "fail"
	d := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{v}}
	r, e := ResumeVerification(context.Background(), request(), d, seed)
	if e != nil || r.Status != "verification-blocked" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestResumeRetainsIndependentFindings(t *testing.T) {
	seed := resumeSeed(t)
	seed.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	for i := range seed.Observations {
		if seed.Observations[i].Declaration.Documentation != nil {
			seed.Observations[i].Declaration.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
		}
	}
	v := verify()
	v.response.Documentation = decision("needed")
	d := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{v}}
	r, e := ResumeVerification(context.Background(), request(), d, seed)
	if e == nil || r.Status != "rejected" {
		t.Fatal(r.Status, e)
	}
}

func TestResumeRejectsUnacceptedWriterBeforeReview(t *testing.T) {
	seed := resumeSeed(t)
	seed.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	for i := range seed.Observations {
		if seed.Observations[i].Declaration.Documentation != nil {
			seed.Observations[i].Declaration.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
		}
	}
	invalid := clone(seed.Observations[1])
	invalid.Before = clone(seed.Candidate)
	invalid.Declaration.Documentation.SeparateFindings = nil
	seed.Observations = append(seed.Observations[:2], invalid, seed.Observations[2])
	d := &driver{t: t, snapshot: clone(seed.Candidate)}
	r, e := ResumeVerification(context.Background(), request(), d, seed)
	if e == nil || r.Status != "rejected" || len(d.inputs) != 0 {
		t.Fatal(r.Status, e, d.inputs)
	}
}

func TestResumePreservesClaimsAcceptedDuringVerification(t *testing.T) {
	for _, collection := range []string{"main", "separate"} {
		for _, loss := range []string{"response", "checkpoint", "retained"} {
			t.Run(collection+"/"+loss, func(t *testing.T) {
				first := verify()
				first.response.Documentation = decision("needed")
				if collection == "separate" {
					first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
				} else {
					claim := clone(first.response.Documentation.Claims[0])
					claim.ID = "newly-checked-consumer"
					first.response.Documentation.Claims = append(first.response.Documentation.Claims, claim)
				}
				first.response.Checks = nil
				seed, err, _ := run(t, assess("needed"), write("b"), review(), first)
				if err == nil || len(allClaims(seed.Documentation)) != 2 {
					t.Fatal("verification must accept the new claim before rejecting missing checks", err)
				}
				next := verify()
				next.response.Documentation = clone(seed.Documentation)
				if loss == "response" {
					next.response.Documentation = decision("needed")
				}
				if loss == "checkpoint" {
					seed.Documentation = decision("needed")
				}
				d := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{next}}
				r, err := ResumeVerification(context.Background(), request(), d, seed)
				if loss == "retained" {
					if err != nil || r.Status != "docs-ready-for-pr" || len(allClaims(r.Documentation)) != 2 {
						t.Fatal(r.Status, err)
					}
				} else if err == nil || r.Status != "rejected" {
					t.Fatal("resume lost an accepted verification claim", r.Status, err)
				}
				if loss == "checkpoint" && len(d.inputs) != 0 {
					t.Fatal("invalid checkpoint launched verification")
				}
			})
		}
	}
}
