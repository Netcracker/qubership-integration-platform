package doccycle

import (
	"context"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func entryRequest(route string) workcycle.Request {
	req := request()
	req.DocumentationEntry = &workcycle.DocumentationEntry{Route: route, Evidence: []string{"routing.json"}}
	return req
}

func TestAutomaticNoChangeUsesNoAgentInvocation(t *testing.T) {
	req := entryRequest("docs-no-change")
	req.RequiredChecks = nil
	req.DocumentationEntry.Decision = decision("outside_help_scope")
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "docs-no-change" || len(d.inputs) != 0 || len(r.Observations) != 0 || r.Candidate.ID != "a" || r.Documentation.Reason != "outside_help_scope" {
		t.Fatal(r, err, d.inputs)
	}
	if r.Request.DocumentationEntry.Route != "docs-no-change" || len(r.Request.DocumentationEntry.Evidence) != 1 || len(d.saves) != 1 {
		t.Fatal("automatic decision lost controller provenance", r)
	}
}

func TestAutomaticNoChangeRejectsInvalidEntry(t *testing.T) {
	for _, kind := range []string{"needed", "missing-comment", "missing-evidence", "dirty-source", "unknown-route", "decision-on-assess"} {
		t.Run(kind, func(t *testing.T) {
			req := entryRequest("docs-no-change")
			req.DocumentationEntry.Decision = decision("outside_help_scope")
			d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}}
			switch kind {
			case "needed":
				req.DocumentationEntry.Decision = decision("needed")
			case "missing-comment":
				req.DocumentationEntry.Decision.IssueComment = ""
			case "missing-evidence":
				req.DocumentationEntry.Evidence = nil
			case "dirty-source":
				d.snapshot.ChangedPaths = []string{"help/docs/operation.md"}
			case "unknown-route":
				req.DocumentationEntry.Route = "close-now"
			case "decision-on-assess":
				req.DocumentationEntry.Route = string(AssessStage)
			}
			r, err := Run(context.Background(), req, d)
			if err == nil || r.Status != "rejected" || len(d.inputs) != 0 {
				t.Fatal(r.Status, err, d.inputs)
			}
		})
	}
}

func TestDirectWriterStartsWithNoAssessment(t *testing.T) {
	req := entryRequest(string(WriteStage))
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{write("b"), review(), verify()}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "docs-ready-for-pr" || len(d.inputs) != 3 || d.inputs[0].Stage != WriteStage || d.inputs[0].Documentation != nil {
		t.Fatal(r.Status, err, d.inputs)
	}
	if d.inputs[1].Stage != ReviewStage || d.inputs[2].Stage != VerifyStage {
		t.Fatal(d.inputs)
	}
}

func TestDirectWriterCanRejectFalsePositiveWithoutPatch(t *testing.T) {
	for _, changed := range []bool{false, true} {
		t.Run(map[bool]string{false: "unchanged", true: "changed"}[changed], func(t *testing.T) {
			req := entryRequest(string(WriteStage))
			s := assess("already_covered")
			s.stage = WriteStage
			if changed {
				s.next = "b"
				s.paths = []string{"help/docs/operation.md"}
			}
			d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{s}}
			r, err := Run(context.Background(), req, d)
			if changed {
				if err == nil || r.Status != "rejected" {
					t.Fatal("changed source accepted as no change", r, err)
				}
			} else if err != nil || r.Status != "docs-no-change" || len(d.inputs) != 1 || len(r.Candidate.ChangedPaths) != 0 {
				t.Fatal(r, err)
			}
		})
	}
}

func TestDirectWriterRequiresChecksAndCleanSource(t *testing.T) {
	for _, dirty := range []bool{false, true} {
		req := entryRequest(string(WriteStage))
		d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}}
		if dirty {
			d.snapshot.ChangedPaths = []string{"help/docs/operation.md"}
		} else {
			req.RequiredChecks = nil
		}
		r, err := Run(context.Background(), req, d)
		if err == nil || r.Status != "rejected" || len(d.inputs) != 0 {
			t.Fatal(r.Status, err, d.inputs)
		}
	}
}

func TestExplicitAssessmentEntryUsesAssessor(t *testing.T) {
	req := entryRequest(string(AssessStage))
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{assess("no_explanation_needed")}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "docs-no-change" || len(d.inputs) != 1 || d.inputs[0].Stage != AssessStage {
		t.Fatal(r.Status, err, d.inputs)
	}
}

func TestDirectWriterRepairCannotDiscardAcceptedScope(t *testing.T) {
	req := entryRequest(string(WriteStage))
	first := write("b")
	first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	rv := review()
	rv.response.Findings = []workcycle.Finding{{ID: "f1", ScenarioID: "c1", Location: "help/docs/operation.md", Description: "The operation needs a prerequisite.", Evidence: []string{"finding.txt"}}}
	dismiss := assess("already_covered")
	dismiss.stage = WriteStage
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, rv, dismiss, dismiss}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "needs-human" || r.Documentation.Decision != "needed" || len(r.Documentation.SeparateFindings) != 1 {
		t.Fatal(r.Status, err, r.Documentation)
	}
}

func directResumeSeed(t *testing.T) workcycle.Report {
	t.Helper()
	req := entryRequest(string(WriteStage))
	v := verify()
	v.response.Checks = nil
	first := write("b")
	first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, review(), v}}
	seed, err := Run(context.Background(), req, d)
	if err == nil || seed.Status != "rejected" {
		t.Fatal("seed must stop at missing verification check", seed, err)
	}
	return seed
}

func TestResumeDirectWriterRetainsReviewAndClaims(t *testing.T) {
	for _, kind := range []string{"valid", "missing-review", "dropped-finding", "changed-entry"} {
		t.Run(kind, func(t *testing.T) {
			seed := directResumeSeed(t)
			req := clone(seed.Request)
			v := verify()
			v.response.Documentation = clone(seed.Documentation)
			switch kind {
			case "missing-review":
				seed.Observations = append(seed.Observations[:1], seed.Observations[2:]...)
			case "dropped-finding":
				v.response.Documentation.SeparateFindings = nil
			case "changed-entry":
				req.DocumentationEntry.Route = string(AssessStage)
			}
			d := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{v}}
			r, err := ResumeVerification(context.Background(), req, d, seed)
			if kind == "valid" {
				if err != nil || r.Status != "docs-ready-for-pr" || len(d.inputs) != 1 || d.inputs[0].Stage != VerifyStage || len(r.Documentation.SeparateFindings) != 1 {
					t.Fatal(r.Status, err, d.inputs)
				}
			} else if err == nil || r.Status != "rejected" {
				t.Fatal(r.Status, err)
			}
		})
	}
}

func TestNonAssessmentEntriesCannotReplayAssessment(t *testing.T) {
	for _, route := range []string{"docs-no-change", string(WriteStage)} {
		req := entryRequest(route)
		if route == "docs-no-change" {
			req.DocumentationEntry.Decision = decision("already_covered")
		}
		seed := workcycle.Report{Request: req, Candidate: workcycle.Snapshot{ID: "a"}, Documentation: decision("already_covered")}
		d := &driver{t: t, snapshot: clone(seed.Candidate)}
		r, err := ReplayNoChangeAssessment(context.Background(), req, d, seed)
		if err == nil || r.Status != "rejected" || len(d.inputs) != 0 {
			t.Fatal(r.Status, err, d.inputs)
		}
	}
}

func TestCancelledAutomaticEntryCannotComplete(t *testing.T) {
	req := entryRequest("docs-no-change")
	req.DocumentationEntry.Decision = decision("outside_help_scope")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}}
	r, err := Run(ctx, req, d)
	if err != context.Canceled || r.Status != "rejected" || len(d.inputs) != 0 {
		t.Fatal(r.Status, err, d.inputs)
	}
}

func TestDirectWriterCorrectsMissingDeliveryMetadata(t *testing.T) {
	for _, field := range []string{"title", "body"} {
		t.Run(field, func(t *testing.T) {
			req := entryRequest(string(WriteStage))
			first := write("b")
			first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			if field == "title" {
				first.response.Documentation.PRTitle = ""
			} else {
				first.response.Documentation.PRBody = ""
			}
			corrected := write("b")
			corrected.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, corrected, review(), verify()}}
			r, err := Run(context.Background(), req, d)
			if err != nil || r.Status != "docs-ready-for-pr" || len(d.inputs) != 4 {
				t.Fatal(r.Status, err, d.inputs)
			}
			repair := d.inputs[1]
			if repair.Stage != WriteStage || len(repair.Findings) != 1 || repair.Findings[0].ID != "documentation-declaration" || repair.Documentation == nil || len(repair.Documentation.SeparateFindings) != 1 {
				t.Fatal("initial writer did not receive a correction with its accepted claims", repair)
			}
			if r.Observations[1].Before.ID != r.Observations[1].After.ID || len(r.Documentation.SeparateFindings) != 1 {
				t.Fatal("metadata correction changed or discarded the candidate", r)
			}
		})
	}
}

func TestDirectWriterRepeatedDeliveryErrorNeedsHuman(t *testing.T) {
	req := entryRequest(string(WriteStage))
	first := write("b")
	first.response.Documentation.PRTitle = ""
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, first}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "needs-human" || len(d.inputs) != 2 || r.Question == nil {
		t.Fatal(r.Status, err, d.inputs)
	}
}

func TestDirectWriterCorrectionCannotDropInitialSeparateFinding(t *testing.T) {
	req := entryRequest(string(WriteStage))
	first := write("b")
	first.response.Documentation.PRTitle = ""
	first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	corrected := write("b")
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, corrected, corrected}}
	r, err := Run(context.Background(), req, d)
	if err != nil || r.Status != "needs-human" || len(r.Documentation.SeparateFindings) != 1 {
		t.Fatal(r.Status, err, r.Documentation)
	}
}

func TestDirectWriterResumeRetainsClaimsBeforeMetadataCorrection(t *testing.T) {
	for _, drop := range []bool{false, true} {
		t.Run(map[bool]string{false: "retained", true: "dropped"}[drop], func(t *testing.T) {
			req := entryRequest(string(WriteStage))
			first := write("b")
			first.response.Documentation.PRTitle = ""
			first.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			corrected := write("b")
			corrected.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			v := verify()
			v.response.Checks = nil
			d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{first, corrected, review(), v}}
			seed, err := Run(context.Background(), req, d)
			if err == nil || seed.Status != "rejected" {
				t.Fatal(seed.Status, err)
			}
			next := verify()
			next.response.Documentation = clone(seed.Documentation)
			if drop {
				seed.Observations[1].Declaration.Documentation.SeparateFindings = nil
				seed.Documentation.SeparateFindings = nil
				next.response.Documentation.SeparateFindings = nil
			}
			resumed := &driver{t: t, snapshot: clone(seed.Candidate), steps: []step{next}}
			result, err := ResumeVerification(context.Background(), req, resumed, seed)
			if drop {
				if err == nil || result.Status != "rejected" || len(resumed.inputs) != 0 {
					t.Fatal("resume discarded initially accepted claims", result.Status, err, resumed.inputs)
				}
			} else if err != nil || result.Status != "docs-ready-for-pr" || len(resumed.inputs) != 1 {
				t.Fatal(result.Status, err, resumed.inputs)
			}
		})
	}
}
