package doccycle

import (
	"context"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type step struct {
	stage    workcycle.Stage
	response workcycle.Response
	next     string
	paths    []string
}
type driver struct {
	t        *testing.T
	snapshot workcycle.Snapshot
	steps    []step
	saves    []workcycle.Report
	inputs   []workcycle.Invocation
}

func (d *driver) Snapshot(context.Context) (workcycle.Snapshot, error) { return clone(d.snapshot), nil }
func (d *driver) Run(_ context.Context, i workcycle.Invocation) (workcycle.Response, error) {
	d.t.Helper()
	d.inputs = append(d.inputs, clone(i))
	if len(d.steps) == 0 {
		d.t.Fatal("unexpected invocation")
	}
	s := d.steps[0]
	d.steps = d.steps[1:]
	if s.stage != i.Stage {
		d.t.Fatalf("stage %s, want %s", i.Stage, s.stage)
	}
	r := clone(s.response)
	if r.CandidateID == "" {
		r.CandidateID = d.snapshot.ID
	}
	if s.next != "" {
		d.snapshot = workcycle.Snapshot{ID: s.next, ChangedPaths: s.paths}
	}
	return r, nil
}
func (d *driver) Save(_ context.Context, r workcycle.Report) error {
	d.saves = append(d.saves, clone(r))
	return nil
}
func decision(kind string) *workcycle.DocumentationDecision {
	action, reason := kind, "missing_guidance"
	switch kind {
	case "already_covered", "restored_behavior", "no_explanation_needed", "internal_only", "outside_help_scope", "preexisting_only":
		action, reason = "no_change", kind
	case "investigate":
		reason = "insufficient_evidence"
	}
	d := &workcycle.DocumentationDecision{Decision: action, Reason: reason, Summary: "The source establishes the behavior.", Claims: []workcycle.DocClaim{{ID: "c1", Behavior: "The operation is supported.", Attribution: "change_related", NeedsChange: action == "needed", SourceEvidence: []string{"source.txt"}, DocumentationEvidence: []string{"help.txt"}, Action: "Document the operation."}}, PRTitle: "docs: document the operation", PRBody: "Document the supported operation.", IssueComment: "The help already describes the behavior; no documentation change is needed."}
	if reason == "preexisting_only" {
		d.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	}
	return d
}
func response() *workcycle.Response { return &workcycle.Response{Evidence: []string{"evidence.txt"}} }
func assess(kind string) step {
	r := response()
	r.Documentation = decision(kind)
	return step{stage: AssessStage, response: *r}
}
func write(id string) step {
	r := response()
	r.Documentation = decision("needed")
	return step{stage: WriteStage, response: *r, next: id, paths: []string{"help/docs/operation.md"}}
}
func review() step { return step{stage: ReviewStage, response: *response()} }
func verify() step {
	r := response()
	r.Checks = []workcycle.Result{{ID: "docs", Outcome: "pass", Evidence: []string{"check.txt"}}}
	return step{stage: VerifyStage, response: *r}
}
func request() workcycle.Request {
	return workcycle.Request{Kind: "documentation", TaskID: "1", Task: "Document the issue.", Context: "The issue and merged source are available.", RequiredChecks: []workcycle.Check{{ID: "docs", Scope: "help/docs"}}}
}
func run(t *testing.T, ss ...step) (workcycle.Report, error, *driver) {
	t.Helper()
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: ss}
	r, e := Run(context.Background(), request(), d)
	if len(d.saves) == 0 {
		t.Fatal("missing checkpoint")
	}
	return r, e, d
}
func TestBranches(t *testing.T) {
	for _, kind := range []string{"already_covered", "restored_behavior", "no_explanation_needed", "internal_only", "outside_help_scope", "preexisting_only", "investigate"} {
		t.Run(kind, func(t *testing.T) {
			r, e, d := run(t, assess(kind))
			want := "docs-no-change"
			if kind == "investigate" {
				want = "needs-human"
			}
			if e != nil || r.Status != want || len(d.steps) != 0 {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
	r, e, _ := run(t, assess("needed"), write("b"), review(), verify())
	if e != nil || r.Status != "docs-ready-for-pr" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestRepairLoop(t *testing.T) {
	rv := review()
	rv.response.Findings = []workcycle.Finding{{ID: "f1", ScenarioID: "c1", Location: "help/docs/operation.md", Description: "The procedure is incomplete.", Evidence: []string{"review.txt"}}}
	r, e, _ := run(t, assess("needed"), write("b"), rv, write("c"), review(), verify())
	if e != nil || r.Status != "docs-ready-for-pr" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestFinalFindingCannotPassWithGreenChecks(t *testing.T) {
	v := verify()
	v.response.Findings = []workcycle.Finding{{ID: "factual-gap", ScenarioID: "c1", Location: "help/docs/operation.md", Description: "The final check found a missing restriction.", Evidence: []string{"final-finding.txt"}}}
	r, e, d := run(t, assess("needed"), write("b"), review(), v, write("c"), review(), verify())
	if e != nil || r.Status != "docs-ready-for-pr" || r.Candidate.ID != "c" {
		t.Fatal(r.Status, e)
	}
	if len(d.inputs[4].Findings) != 1 || d.inputs[4].Findings[0].ID != "factual-gap" {
		t.Fatal("final finding did not reach the writer")
	}
}
func TestRepeatedFindingsNeedHuman(t *testing.T) {
	rv := review()
	rv.response.Findings = []workcycle.Finding{{ID: "f1", ScenarioID: "c1", Location: "help/docs/operation.md", Description: "The procedure is incomplete.", Evidence: []string{"review.txt"}}}
	r, e, _ := run(t, assess("needed"), write("b"), rv, write("b"), rv)
	if e != nil || r.Status != "needs-human" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestRejectInvalidStageEvidence(t *testing.T) {
	tests := map[string][]step{}
	s := assess("already_covered")
	s.next = "b"
	s.paths = []string{"engine/a.java"}
	tests["no-doc mutation"] = []step{s}
	s = write("b")
	s.paths = []string{"engine/a.java"}
	tests["source patch"] = []step{assess("needed"), s}
	s = verify()
	s.response.CandidateID = "a"
	tests["stale checks"] = []step{assess("needed"), write("b"), review(), s}
	s = verify()
	s.response.Checks = nil
	tests["missing checks"] = []step{assess("needed"), write("b"), review(), s}
	s = assess("unknown")
	tests["unknown decision"] = []step{s}
	s = assess("needed")
	s.response.Documentation.Claims = nil
	tests["missing claims"] = []step{s}
	s = review()
	s.next = "c"
	s.paths = []string{"help/docs/operation.md"}
	tests["review mutation"] = []step{assess("needed"), write("b"), s}
	s = review()
	s.response.Findings = []workcycle.Finding{{ID: "f", ScenarioID: "unknown", Location: "help/docs/operation.md", Description: "Wrong claim.", Evidence: []string{"finding.txt"}}}
	tests["unknown claim"] = []step{assess("needed"), write("b"), s}
	for name, ss := range tests {
		t.Run(name, func(t *testing.T) {
			r, e, _ := run(t, ss...)
			if e == nil || r.Status != "rejected" {
				t.Fatalf("%+v %v", r, e)
			}
		})
	}
}
func TestNoChangeNeedsNoChecks(t *testing.T) {
	d := &driver{t: t, snapshot: workcycle.Snapshot{ID: "a"}, steps: []step{assess("already_covered")}}
	req := request()
	req.RequiredChecks = nil
	r, e := Run(context.Background(), req, d)
	if e != nil || r.Status != "docs-no-change" {
		t.Fatalf("%+v %v", r, e)
	}
}
func TestQuestionPreservesCandidate(t *testing.T) {
	s := write("b")
	s.response.Question = &workcycle.Question{Kind: "needs-human", Text: "Which version needs documentation?", Needed: "Choose the supported version.", Evidence: []string{"question.txt"}}
	r, e, _ := run(t, assess("needed"), s)
	if e != nil || r.Status != "needs-human" || r.Candidate.ID != "b" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestWriterPreservesAssessedClaims(t *testing.T) {
	s := write("b")
	s.response.Documentation.Claims[0].ID = "replacement"
	r, e, _ := run(t, assess("needed"), s, s)
	if e != nil || r.Status != "needs-human" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestRepairPreservesAddedClaims(t *testing.T) {
	first := write("b")
	added := clone(first.response.Documentation.Claims[0])
	added.ID = "c2"
	first.response.Documentation.Claims = append(first.response.Documentation.Claims, added)
	rv := review()
	rv.response.Findings = []workcycle.Finding{{ID: "f", ScenarioID: "c2", Location: "help/docs/operation.md", Description: "The second claim needs a procedure.", Evidence: []string{"finding.txt"}}}
	r, e, _ := run(t, assess("needed"), first, rv, write("c"), write("c"))
	if e != nil || r.Status != "needs-human" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestVerificationUpdatesDeliveryText(t *testing.T) {
	v := verify()
	v.response.Documentation = decision("needed")
	v.response.Documentation.PRBody = "Document the operation. The documentation checks pass."
	v.response.Documentation.IssueComment = "The documentation draft is ready for review."
	r, e, _ := run(t, assess("needed"), write("b"), review(), v)
	if e != nil || r.Status != "docs-ready-for-pr" || r.Documentation.PRBody != v.response.Documentation.PRBody || r.Documentation.IssueComment != v.response.Documentation.IssueComment {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestVerificationPreservesClaims(t *testing.T) {
	v := verify()
	v.response.Documentation = decision("needed")
	v.response.Documentation.Claims[0].ID = "replacement"
	r, e, _ := run(t, assess("needed"), write("b"), review(), v)
	if e == nil || r.Status != "rejected" {
		t.Fatalf("%+v %v", r, e)
	}
}

func TestWriterRepairsDroppedClaimWithoutChangingPatch(t *testing.T) {
	s := write("b")
	s.response.Documentation.Claims[0].ID = "replacement"
	r, e, d := run(t, assess("needed"), s, write("b"), review(), verify())
	if e != nil || r.Status != "docs-ready-for-pr" {
		t.Fatalf("%+v %v", r, e)
	}
	input := d.inputs[2]
	if len(input.Findings) != 1 || input.Findings[0].ScenarioID != "c1" || input.Findings[0].Location != "documentation report" || input.Documentation.Claims[0].ID != "c1" {
		t.Fatalf("missing corrective input: %+v", input)
	}
	corrective := r.Observations[2]
	if corrective.Before.ID != corrective.After.ID || corrective.Declaration.Documentation.Claims[0].ID != "c1" {
		t.Fatalf("unexpected corrective response: %+v", corrective)
	}
}

func separateFinding() workcycle.DocClaim {
	return workcycle.DocClaim{ID: "old-defect", Behavior: "An older instruction is false independently of this issue.", Attribution: "preexisting_independent", NeedsChange: true, SourceEvidence: []string{"old-source.txt"}, DocumentationEvidence: []string{"old-help.txt"}, Action: "Record for separate triage."}
}

func TestIndependentDefectDoesNotTriggerWriter(t *testing.T) {
	s := assess("preexisting_only")
	s.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	r, e, d := run(t, s)
	if e != nil || r.Status != "docs-no-change" || len(d.inputs) != 1 || len(r.Documentation.SeparateFindings) != 1 || len(r.Candidate.ChangedPaths) != 0 {
		t.Fatal(r.Status, e, d.inputs)
	}
}

func TestNeededRequiresChangeRelatedWork(t *testing.T) {
	for _, kind := range []string{"no-required-claim", "unchanged-required", "separate-only", "missing-attribution", "duplicate-separate-id"} {
		t.Run(kind, func(t *testing.T) {
			s := assess("needed")
			d := s.response.Documentation
			switch kind {
			case "no-required-claim":
				d.Claims[0].NeedsChange = false
			case "unchanged-required":
				d.Claims[0].Attribution = "unchanged"
			case "separate-only":
				d.Claims[0].NeedsChange = false
				d.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			case "missing-attribution":
				d.Claims[0].Attribution = ""
			case "duplicate-separate-id":
				d.SeparateFindings = []workcycle.DocClaim{separateFinding()}
				d.SeparateFindings[0].ID = d.Claims[0].ID
			}
			r, e, driver := run(t, s)
			if e == nil || r.Status != "rejected" || len(driver.inputs) != 1 {
				t.Fatal(r.Status, e, driver.inputs)
			}
		})
	}
}

func TestVisibleChangeNeedsNoExplanation(t *testing.T) {
	r, e, d := run(t, assess("no_explanation_needed"))
	if e != nil || r.Status != "docs-no-change" || r.Documentation.Reason != "no_explanation_needed" || len(d.inputs) != 1 {
		t.Fatal(r.Status, e, d.inputs)
	}
}

func TestWriterCannotDropOrReclassifySeparateFindings(t *testing.T) {
	for _, kind := range []string{"drop", "move-to-main", "change-attribution", "erase-work"} {
		t.Run(kind, func(t *testing.T) {
			a := assess("needed")
			a.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			w := write("b")
			w.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
			switch kind {
			case "drop":
				w.response.Documentation.SeparateFindings = nil
			case "move-to-main":
				c := separateFinding()
				c.Attribution = "change_related"
				w.response.Documentation.Claims = append(w.response.Documentation.Claims, c)
				w.response.Documentation.SeparateFindings = nil
			case "change-attribution":
				w.response.Documentation.SeparateFindings[0].Attribution = "change_related"
			case "erase-work":
				w.response.Documentation.SeparateFindings[0].NeedsChange = false
			}
			r, e, d := run(t, a, w, w)
			if e != nil || r.Status != "needs-human" || len(d.inputs) != 3 || r.Documentation.SeparateFindings[0].Attribution != "preexisting_independent" {
				t.Fatal(r.Status, e)
			}
		})
	}
}

func TestFinalVerificationRetainsIndependentFindings(t *testing.T) {
	a := assess("needed")
	a.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	w := write("b")
	w.response.Documentation.SeparateFindings = []workcycle.DocClaim{separateFinding()}
	v := verify()
	v.response.Documentation = decision("needed")
	r, e, _ := run(t, a, w, review(), v)
	if e == nil || r.Status != "rejected" {
		t.Fatal(r.Status, e)
	}
}

func TestNoChangeDecisionCannotConcealRequiredWork(t *testing.T) {
	for _, kind := range []string{"required-main-claim", "unclear-attribution", "preexisting-in-main", "empty-preexisting", "legacy-decision"} {
		t.Run(kind, func(t *testing.T) {
			s := assess("no_explanation_needed")
			d := s.response.Documentation
			switch kind {
			case "required-main-claim":
				d.Claims[0].NeedsChange = true
			case "unclear-attribution":
				d.Claims[0].Attribution = "unclear"
			case "preexisting-in-main":
				d.Claims[0].Attribution = "preexisting_independent"
			case "empty-preexisting":
				d.Reason = "preexisting_only"
			case "legacy-decision":
				d.Decision = "already_covered"
			}
			r, e, driver := run(t, s)
			if e == nil || r.Status != "rejected" || len(driver.inputs) != 1 {
				t.Fatal(r.Status, e)
			}
		})
	}
}
