package workcycle

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"
)

type agentFake struct {
	snapshot    Snapshot
	authors     []AgentAuthorRequest
	assessments []AgentAssessmentRequest
	saved       []Report
	edit        func(*agentFake)
	assess      func(AgentAssessmentRequest, *agentFake) AgentAssessment
}

func newAgentFake() *agentFake                                  { return &agentFake{snapshot: Snapshot{ID: "base"}} }
func (f *agentFake) Snapshot(context.Context) (Snapshot, error) { return f.snapshot, nil }
func (f *agentFake) Save(_ context.Context, r Report) error     { f.saved = append(f.saved, r); return nil }
func (f *agentFake) Author(_ context.Context, r AgentAuthorRequest) (AgentAuthorResult, error) {
	f.authors = append(f.authors, r)
	f.snapshot = Snapshot{ID: fmt.Sprintf("candidate-%d", len(f.authors)), ChangedPaths: []string{"engine/code.go"}}
	if f.edit != nil {
		f.edit(f)
	}
	return AgentAuthorResult{Text: "Implemented the issue. Sources: /task/attachments/02.png and /preflight/preview.json. See the browser checks in my notes.", Evidence: []string{"author-summary.md"}}, nil
}
func (f *agentFake) Assess(_ context.Context, r AgentAssessmentRequest) (AgentAssessment, error) {
	f.assessments = append(f.assessments, r)
	if f.assess != nil {
		return f.assess(r, f), nil
	}
	return AgentAssessment{Decision: "approve", Summary: "Required behavior and healthy peers checked on the candidate.", Evidence: []string{"assessment.md"}}, nil
}
func TestAgentCycleDoesNotRequireAuthorStageDeclarations(t *testing.T) {
	f := newAgentFake()
	r, e := RunAgent(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || r.Workflow != "agent" {
		t.Fatal(r, e)
	}
	if len(f.authors) != 1 || len(f.assessments) != 2 {
		t.Fatal(f.authors, f.assessments)
	}
	if len(r.Observations) != 3 || r.Observations[0].Stage != AuthorStage || r.Contract != nil {
		t.Fatal(r)
	}
	if !strings.Contains(r.Observations[0].Declaration.Narrative, "/task/attachments/02.png") {
		t.Fatal("source references lost")
	}
	if f.assessments[1].Stage != VerifyStage {
		t.Fatal("fresh final verification missing")
	}
}
func TestAgentReviewAndFinalFailuresReturnFeedbackToAuthor(t *testing.T) {
	for _, stage := range []Stage{ReviewStage, VerifyStage} {
		t.Run(string(stage), func(t *testing.T) {
			f := newAgentFake()
			failed := false
			f.assess = func(r AgentAssessmentRequest, _ *agentFake) AgentAssessment {
				if r.Stage == stage && !failed {
					failed = true
					return AgentAssessment{Decision: "changes-requested", Summary: "Parent folder navigation is wrong.", Findings: []string{"Fix the containing folder target."}, Evidence: []string{"failed-route.trace"}}
				}
				return AgentAssessment{Decision: "approve", Summary: "Fresh checks passed."}
			}
			r, e := RunAgent(context.Background(), request(), f)
			if e != nil || r.Status != "verified" || len(f.authors) != 2 || len(f.authors[1].Feedback) == 0 {
				t.Fatal(r, e)
			}
			if !strings.Contains(strings.Join(f.authors[1].Feedback, "\n"), "failed-route.trace") {
				t.Fatal("repair lost the assessor evidence", f.authors[1].Feedback)
			}
			if r.Observations[len(r.Observations)-1].Stage != VerifyStage {
				t.Fatal("missing final rerun")
			}
			followup := f.assessments[len(f.assessments)-2]
			if followup.Stage != ReviewStage || followup.ReviewDelta == nil || followup.ReviewDelta.ChangedPathsKnown {
				t.Fatal("a repair with missing file hashes needs a full review", followup)
			}
		})
	}
}

func TestAgentRepairReviewsOnlyTheDeltaAndRetainsCleanSpecialistForTestChanges(t *testing.T) {
	const source = "ui/src/page.tsx"
	const regression = "ui/src/page.test.tsx"
	for _, repaired := range []string{source, regression} {
		t.Run(repaired, func(t *testing.T) {
			f := newAgentFake()
			f.edit = func(f *agentFake) {
				f.snapshot.ChangedPaths = []string{source, regression}
				f.snapshot.FileHashes = map[string]string{source: "1", regression: "1"}
				if len(f.authors) == 2 {
					f.snapshot.FileHashes[repaired] = "2"
				}
			}
			f.assess = func(r AgentAssessmentRequest, _ *agentFake) AgentAssessment {
				if r.Stage == ReviewStage && r.Lens == "correctness" && r.Candidate.ID == "candidate-1" {
					return AgentAssessment{Decision: "changes-requested", Summary: "Wrong parent folder.", Findings: []string{"Check the parent of a nested folder."}}
				}
				return AgentAssessment{Decision: "approve", Summary: "Checked."}
			}

			report, err := RunAgent(context.Background(), request(), f)
			if err != nil || report.Status != "verified" {
				t.Fatal(report, err)
			}
			var followupLenses []string
			for _, call := range f.assessments {
				if call.Stage == VerifyStage {
					if call.ReviewDelta != nil || call.Candidate.ID != "candidate-2" {
						t.Fatal("final verification must receive the repaired candidate and the full scope", call)
					}
					continue
				}
				if call.Candidate.ID == "candidate-1" {
					if call.ReviewDelta != nil {
						t.Fatal("a new review needs its full scope", call)
					}
					continue
				}
				followupLenses = append(followupLenses, call.Lens)
				delta := call.ReviewDelta
				if delta == nil || !delta.ChangedPathsKnown || delta.PreviousCandidateID != "candidate-1" || !slices.Equal(delta.ChangedPaths, []string{repaired}) {
					t.Fatal("review omitted the measured repair scope", call)
				}
				if call.Lens == "correctness" && !slices.Equal(delta.Findings, []string{"Wrong parent folder.", "Check the parent of a nested folder."}) {
					t.Fatal("review lost its earlier finding", delta)
				}
				if call.Lens == "compatibility" && len(delta.Findings) != 0 {
					t.Fatal("consumer review received findings from another lens", delta)
				}
			}
			want := []string{"correctness"}
			if repaired == source {
				want = append(want, "compatibility")
			}
			if !slices.Equal(followupLenses, want) {
				t.Fatal("unexpected follow-up reviews", followupLenses)
			}
			last := report.ReviewDecisions[len(report.ReviewDecisions)-1]
			if reused := last.ReusedFrom == "candidate-1"; last.Lens != "compatibility" || reused != (repaired == regression) {
				t.Fatal("report did not record the correct consumer review decision", last)
			}
		})
	}
}

type disappearingReviewAdvice struct{ *agentFake }

func (f *disappearingReviewAdvice) Advise(_ context.Context, input AdviceInput) (*RoutingAdvice, error) {
	advice := &RoutingAdvice{Phase: input.Phase, CandidateID: input.Candidate.ID, Evidence: "routing.json"}
	if input.Phase == "review" && input.Candidate.ID == "candidate-1" {
		advice.ExtraReviews = []string{"compatibility"}
	}
	return advice, nil
}

func TestAgentRetainsTriggeredReviewAndChecksNewTriggerAfterRepair(t *testing.T) {
	f := &disappearingReviewAdvice{newAgentFake()}
	f.edit = func(f *agentFake) {
		f.snapshot.FileHashes = map[string]string{"engine/code.go": f.snapshot.ID}
		if len(f.authors) == 2 {
			authFile := "engine/auth.go"
			f.snapshot.ChangedPaths = append(f.snapshot.ChangedPaths, authFile)
			f.snapshot.FileHashes[authFile] = f.snapshot.ID
		}
	}
	f.assess = func(call AgentAssessmentRequest, _ *agentFake) AgentAssessment {
		if call.Lens == "compatibility" && call.Candidate.ID == "candidate-1" {
			return AgentAssessment{Decision: "changes-requested", Summary: "Shared caller needs its original behavior."}
		}
		return AgentAssessment{Decision: "approve", Summary: "Checked."}
	}
	report, err := RunAgent(context.Background(), request(), f)
	if err != nil || report.Status != "verified" || len(f.assessments) != 6 {
		t.Fatal(report, err, f.assessments)
	}
	security, compatibility := f.assessments[3], f.assessments[4]
	if security.Lens != "security" || security.ReviewDelta != nil {
		t.Fatal("new security trigger must receive an initial review", security)
	}
	if compatibility.Lens != "compatibility" || compatibility.ReviewDelta == nil || len(compatibility.ReviewDelta.Findings) == 0 {
		t.Fatal("an outstanding consumer finding was dropped with the advice", compatibility)
	}
}

func TestAgentReusesCleanReviewsWhenRepairLeavesSourceUnchanged(t *testing.T) {
	f := newAgentFake()
	f.edit = func(f *agentFake) { f.snapshot.ID = "unchanged-candidate" }
	f.assess = func(call AgentAssessmentRequest, f *agentFake) AgentAssessment {
		if call.Stage == VerifyStage && len(f.authors) == 1 {
			return AgentAssessment{Decision: "changes-requested", Summary: "Rebuild the deployed product."}
		}
		return AgentAssessment{Decision: "approve", Summary: "Checked."}
	}
	report, err := RunAgent(context.Background(), request(), f)
	if err != nil || report.Status != "verified" || len(f.assessments) != 3 || f.assessments[2].Stage != VerifyStage {
		t.Fatal(report, err, f.assessments)
	}
	last := report.ReviewDecisions[len(report.ReviewDecisions)-1]
	if last.Lens != "correctness" || last.ReusedFrom != "unchanged-candidate" {
		t.Fatal("report did not record reuse on unchanged source", last)
	}
}
func TestAgentAssessmentCannotApproveEditsOrUnknownDisposition(t *testing.T) {
	for _, mode := range []string{"edit", "invalid", "empty", "needs-human"} {
		t.Run(mode, func(t *testing.T) {
			f := newAgentFake()
			f.assess = func(_ AgentAssessmentRequest, f *agentFake) AgentAssessment {
				a := AgentAssessment{Decision: "approve", Summary: "Checked."}
				switch mode {
				case "edit":
					f.snapshot.ID = "review-edited"
				case "invalid":
					a.Decision = "perhaps"
				case "empty":
					a.Summary = ""
				case "needs-human":
					a.Decision = "needs-human"
					a.Summary = "Choose the intended behavior."
				}
				return a
			}
			r, _ := RunAgent(context.Background(), request(), f)
			if r.Status == "verified" || len(f.authors) != 1 || len(f.assessments) != 1 {
				t.Fatal(r)
			}
		})
	}
}

type interruptedAgent struct {
	*agentFake
	cancel context.CancelFunc
	stage  Stage
}

func (f *interruptedAgent) Snapshot(ctx context.Context) (Snapshot, error) {
	if err := ctx.Err(); err != nil {
		return Snapshot{}, err
	}
	return f.agentFake.Snapshot(ctx)
}

func (f *interruptedAgent) Author(ctx context.Context, input AgentAuthorRequest) (AgentAuthorResult, error) {
	result, err := f.agentFake.Author(ctx, input)
	if f.stage == AuthorStage {
		f.cancel()
		return result, ctx.Err()
	}
	return result, err
}

func (f *interruptedAgent) Assess(ctx context.Context, input AgentAssessmentRequest) (AgentAssessment, error) {
	result, err := f.agentFake.Assess(ctx, input)
	if f.stage == input.Stage {
		f.cancel()
		return result, ctx.Err()
	}
	return result, err
}

func TestAgentInterruptionPreservesLastObservedCandidate(t *testing.T) {
	for stage, candidate := range map[Stage]string{AuthorStage: "base", ReviewStage: "candidate-1", VerifyStage: "candidate-1"} {
		t.Run(string(stage), func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			f := &interruptedAgent{agentFake: newAgentFake(), cancel: cancel, stage: stage}
			report, err := RunAgent(ctx, request(), f)
			if !errors.Is(err, context.Canceled) || report.Status != "interrupted" || report.Candidate.ID != candidate {
				t.Fatal(report.Status, report.Candidate.ID, err)
			}
			last := report.Observations[len(report.Observations)-1]
			if last.Before.ID != candidate || last.After.ID != "" {
				t.Fatal("interrupted turn reported an unmeasured source identity", last)
			}
		})
	}
}
func TestAgentStopsOnlyWhenUnchangedCandidateStillNeedsCorrection(t *testing.T) {
	f := newAgentFake()
	f.edit = func(f *agentFake) { f.snapshot.ID = "unchanged-candidate" }
	f.assess = func(_ AgentAssessmentRequest, f *agentFake) AgentAssessment {
		return AgentAssessment{Decision: "changes-requested", Summary: fmt.Sprintf("Still wrong on pass %d", len(f.assessments))}
	}
	r, e := RunAgent(context.Background(), request(), f)
	if e != nil || r.Status != "needs-human" || len(f.authors) != 2 {
		t.Fatal(r, e)
	}
}

type agentReviewBatchFake struct {
	*agentFake
	batches [][]AgentAssessmentRequest
	mutate  bool
}

func (f *agentReviewBatchFake) ReviewParallelism() int { return 2 }

func (f *agentReviewBatchFake) AssessReviewBatch(ctx context.Context, inputs []AgentAssessmentRequest) ([]AgentAssessment, error) {
	f.batches = append(f.batches, inputs)
	results := make([]AgentAssessment, len(inputs))
	for i, input := range inputs {
		results[i], _ = f.agentFake.Assess(ctx, input)
	}
	if f.mutate {
		f.snapshot.ID = "source-changed-during-review"
	}
	return results, nil
}

func TestAgentParallelReviewKeepsRepairScopeAndRechecksSource(t *testing.T) {
	for _, mutate := range []bool{false, true} {
		t.Run(fmt.Sprintf("source-changes-%t", mutate), func(t *testing.T) {
			const source = "ui/src/page.tsx"
			const regression = "ui/src/page.test.tsx"
			f := &agentReviewBatchFake{agentFake: newAgentFake(), mutate: mutate}
			f.edit = func(f *agentFake) {
				f.snapshot.ChangedPaths = []string{source, regression}
				f.snapshot.FileHashes = map[string]string{source: "source", regression: f.snapshot.ID}
			}
			f.assess = func(input AgentAssessmentRequest, _ *agentFake) AgentAssessment {
				if input.Lens == "correctness" && input.Candidate.ID == "candidate-1" {
					return AgentAssessment{Decision: "changes-requested", Summary: "Cover the parent folder."}
				}
				return AgentAssessment{Decision: "approve", Summary: "Checked."}
			}
			report, err := RunAgent(context.Background(), request(), f)
			if mutate {
				if err == nil || report.Status != "verification-blocked" || len(f.assessments) != 2 {
					t.Fatal("a changed candidate reached the author or verifier after review", report.Status, err, f.assessments)
				}
				return
			}
			if err != nil || report.Status != "verified" || len(f.batches) != 2 || len(f.batches[0]) != 2 || len(f.batches[1]) != 1 {
				t.Fatal(report.Status, err, f.batches)
			}
			followup := f.batches[1][0]
			if followup.Lens != "correctness" || followup.ReviewDelta == nil ||
				!slices.Equal(followup.ReviewDelta.ChangedPaths, []string{regression}) {
				t.Fatal("batch lost the repair scope", followup)
			}
			if last := f.assessments[len(f.assessments)-1]; last.Stage != VerifyStage || last.ReviewDelta != nil {
				t.Fatal("batch did not hand off to a full live verification", last)
			}
		})
	}
}

type agentChecks struct {
	*agentFake
	calls int
}

func (f *agentChecks) StaticChecks(_ context.Context, s Snapshot) (StaticCheckRun, error) {
	f.calls++
	outcome := "pass"
	if f.calls == 1 {
		outcome = "fail"
	}
	return StaticCheckRun{CandidateID: s.ID, Results: []Result{{ID: "unit", Outcome: outcome, Evidence: []string{"check-log"}}}}, nil
}
func TestAgentStaticFailureCannotBeOverriddenByProse(t *testing.T) {
	f := &agentChecks{agentFake: newAgentFake()}
	req := request()
	req.ManagedChecks = req.RequiredChecks
	r, e := RunAgent(context.Background(), req, f)
	if e != nil || r.Status != "verified" || len(f.authors) != 2 || !strings.Contains(strings.Join(f.authors[1].Feedback, " "), "unit") {
		t.Fatal(r, e)
	}
	for _, assessment := range f.assessments {
		if assessment.Stage != VerifyStage && len(assessment.ManagedResults) != 0 {
			t.Fatal("managed results entered review", assessment)
		}
		if assessment.Stage == VerifyStage && (assessment.Candidate.ID != "candidate-2" || len(assessment.ManagedResults) != 1 ||
			assessment.ManagedResults[0].Outcome != "pass" || !slices.Equal(assessment.ManagedResults[0].Evidence, []string{"check-log"})) {
			t.Fatal("verification did not receive managed results for the current candidate", assessment)
		}
	}
}

type brokenAgentAdvice struct{ *agentFake }

func (f *brokenAgentAdvice) Advise(context.Context, AdviceInput) (*RoutingAdvice, error) {
	return nil, errors.New("classifier unavailable")
}
func TestAgentCanContinueWithoutOptionalRoutingAdvice(t *testing.T) {
	f := &brokenAgentAdvice{newAgentFake()}
	r, e := RunAgent(context.Background(), request(), f)
	if e != nil || r.Status != "verified" || len(r.Warnings) == 0 {
		t.Fatal(r, e)
	}
}
func TestAgentReviewCountFollowsConsumersAndChangedPaths(t *testing.T) {
	f := newAgentFake()
	f.edit = func(f *agentFake) {
		f.snapshot.ChangedPaths = []string{"runtime-catalog/src/main/resources/db/migration/fix.sql", "ui/src/auth.ts"}
	}
	r, e := RunAgent(context.Background(), request(), f)
	if e != nil || r.Status != "verified" {
		t.Fatal(r, e)
	}
	lenses := map[string]bool{}
	for _, a := range f.assessments {
		if a.Stage == ReviewStage {
			lenses[a.Lens] = true
		}
	}
	for _, lens := range []string{"correctness", "compatibility", "data", "security"} {
		if !lenses[lens] {
			t.Fatal(lenses)
		}
	}
}

type agentSaveFailure struct{ *agentFake }

func (f *agentSaveFailure) Save(_ context.Context, r Report) error {
	if r.Status == "verified" {
		return errors.New("cannot persist final report")
	}
	return nil
}
func TestAgentCannotReportVerifiedWhenFinalSaveFails(t *testing.T) {
	f := &agentSaveFailure{newAgentFake()}
	r, e := RunAgent(context.Background(), request(), f)
	if e == nil || r.Status == "verified" || r.Error == "" {
		t.Fatal(r, e)
	}
}
