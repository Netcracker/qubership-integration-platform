package workcycle

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
)

type agentReview struct {
	candidate Snapshot
	findings  []string
}

type agentReviewPlan struct {
	delta    *AgentReviewDelta
	decision ReviewDecision
}

// RunAgent leaves the author's engineering sequence inside one native session.
func RunAgent(ctx context.Context, req Request, d AgentDriver) (report Report, err error) {
	req = cloneRequest(req)
	report = Report{Workflow: "agent", Request: req, Status: "running"}
	lastStage := AuthorStage
	defer func() {
		if err != nil {
			report.Error = err.Error()
			if ctx.Err() != nil {
				report.Status = "interrupted"
			} else {
				report.Status = "verification-blocked"
			}
		}
		if ctx.Err() == nil && (err != nil || report.Question != nil) {
			advice, adviceErr := advise(ctx, d, AdviceInput{Phase: "failure", Stage: lastStage, Request: req, Candidate: report.Candidate, Failure: report.Error, Question: report.Question})
			if adviceErr != nil {
				report.Warnings = append(report.Warnings, "Failure routing unavailable: "+adviceErr.Error())
			}
			if advice != nil {
				report.Routing = append(report.Routing, *advice)
			}
		}
		if e := d.Save(context.WithoutCancel(ctx), report); e != nil {
			err = errors.Join(err, e)
			report.Status = "verification-blocked"
			report.Error = err.Error()
		}
	}()
	if req.TaskID == "" || req.Task == "" || req.Context == "" || req.EnvironmentID == "" {
		return report, errors.New("incomplete agent work request")
	}
	if e := validateRequest(req); e != nil {
		return report, e
	}
	initial, e := d.Snapshot(ctx)
	if e != nil {
		return report, e
	}
	report.Candidate = initial
	route := func(phase string, stage Stage, lenses []string, failure string) {
		a, e := advise(ctx, d, AdviceInput{Phase: phase, Stage: stage, Request: req, Candidate: report.Candidate, MandatoryReviews: lenses, Failure: failure})
		if e != nil {
			report.Warnings = append(report.Warnings, "Routing advice unavailable: "+e.Error())
			return
		}
		if a != nil {
			report.Routing = append(report.Routing, *a)
		}
	}
	route("intake", AuthorStage, nil, "")
	var feedback, evidencePaths []string
	repeated := map[string]bool{}
	reviews := map[string]agentReview{}
	var scheduledReviews []string
	repair := func(key string, notes, proof []string) bool {
		if len(proof) > 0 {
			notes = append(notes, "Inspect the recorded evidence.\n"+strings.Join(proof, "\n"))
		}
		key = report.Candidate.ID + ":" + key
		if repeated[key] {
			report.Status = "needs-human"
			if len(proof) == 0 {
				proof = evidencePaths
			}
			report.Question = &Question{Kind: report.Status, Text: "The same candidate still needs the same correction.", Needed: strings.Join(notes, "\n"), Evidence: proof}
			return false
		}
		repeated[key] = true
		feedback = notes
		return true
	}
	save := func() error { return d.Save(ctx, report) }
	assessmentInput := func(stage Stage, lens string, delta *AgentReviewDelta, candidate Snapshot) AgentAssessmentRequest {
		return AgentAssessmentRequest{
			Request: req, Candidate: candidate, Stage: stage, Lens: lens, ReviewDelta: delta,
			Evidence: evidencePaths, Routing: currentAdvice(report.Routing, candidate.ID),
		}
	}
	recordAssessment := func(input AgentAssessmentRequest, after Snapshot, a AgentAssessment) {
		report.Observations = append(report.Observations, Observation{
			Stage: input.Stage, Lens: input.Lens, Before: input.Candidate, After: after,
			Declaration: Response{CandidateID: input.Candidate.ID, Narrative: a.Summary, Assessment: &a, Evidence: a.Evidence},
		})
	}
	assess := func(stage Stage, lens string, delta *AgentReviewDelta, managed []Result) (AgentAssessment, error) {
		lastStage = stage
		before, e := d.Snapshot(ctx)
		if e != nil {
			return AgentAssessment{}, e
		}
		if before.ID != report.Candidate.ID {
			return AgentAssessment{}, errors.New("workspace changed before independent assessment")
		}
		input := assessmentInput(stage, lens, delta, before)
		input.ManagedResults = managed
		a, runErr := d.Assess(ctx, input)
		after, snapshotErr := d.Snapshot(ctx)
		if snapshotErr == nil {
			report.Candidate = after
		}
		if runErr != nil || snapshotErr != nil {
			recordAssessment(input, after, a)
			return a, errors.Join(runErr, snapshotErr)
		}
		if before.ID != after.ID {
			recordAssessment(input, after, a)
			return a, errors.New("independent assessment changed the candidate")
		}
		a = normalizeAgentAssessment(a)
		recordAssessment(input, after, a)
		return a, save()
	}
	assessBatch := func(batcher AgentReviewBatcher, plans []agentReviewPlan) ([]AgentAssessmentRequest, []AgentAssessment, error) {
		lastStage = ReviewStage
		before, e := d.Snapshot(ctx)
		if e != nil {
			return nil, nil, e
		}
		if before.ID != report.Candidate.ID {
			return nil, nil, errors.New("workspace changed before independent review")
		}
		var inputs []AgentAssessmentRequest
		for _, plan := range plans {
			report.ReviewDecisions = append(report.ReviewDecisions, plan.decision)
			if plan.decision.ReusedFrom == "" {
				inputs = append(inputs, assessmentInput(ReviewStage, plan.decision.Lens, plan.delta, before))
			}
		}
		if len(inputs) == 0 {
			return nil, nil, nil
		}
		results, runErr := batcher.AssessReviewBatch(ctx, inputs)
		after, snapshotErr := d.Snapshot(ctx)
		if snapshotErr == nil {
			report.Candidate = after
		}
		for i := 0; i < min(len(inputs), len(results)); i++ {
			results[i] = normalizeAgentAssessment(results[i])
			recordAssessment(inputs[i], after, results[i])
		}
		if runErr != nil || snapshotErr != nil {
			return nil, nil, errors.Join(runErr, snapshotErr)
		}
		if before.ID != after.ID {
			return nil, nil, errors.New("workspace changed during independent review")
		}
		if len(inputs) != len(results) {
			return nil, nil, errors.New("incomplete independent review batch")
		}
		return inputs, results, save()
	}
	stopFor := func(a AgentAssessment) bool {
		if a.Decision != "needs-human" && a.Decision != "verification-blocked" {
			return false
		}
		report.Status = a.Decision
		report.Question = &Question{Kind: a.Decision, Text: a.Summary, Needed: strings.Join(a.Findings, "\n"), Evidence: a.Evidence}
		report.Summary = a.Summary
		return true
	}
	for {
		if e = ctx.Err(); e != nil {
			return report, e
		}
		before, e := d.Snapshot(ctx)
		if e != nil {
			return report, e
		}
		if before.ID != report.Candidate.ID {
			return report, errors.New("workspace changed before author turn")
		}
		lastStage = AuthorStage
		result, runErr := d.Author(ctx, AgentAuthorRequest{Request: req, Candidate: before, Feedback: feedback, Routing: currentAdvice(report.Routing, before.ID)})
		after, snapshotErr := d.Snapshot(ctx)
		if snapshotErr == nil {
			report.Candidate = after
		}
		report.Observations = append(report.Observations, Observation{Stage: AuthorStage, Before: before, After: after, Declaration: Response{CandidateID: before.ID, Narrative: result.Text, Evidence: result.Evidence}})
		report.Summary = result.Text
		evidencePaths = append(evidencePaths, result.Evidence...)
		if runErr != nil || snapshotErr != nil {
			return report, errors.Join(runErr, snapshotErr)
		}
		if e = save(); e != nil {
			return report, e
		}

		lastStage = Stage("check")
		checks, e := collectStaticChecks(ctx, d, &report, after)
		if e != nil {
			return report, e
		}
		if e = requireManagedResults(req, checks); e != nil {
			return report, e
		}
		failed, notes := []string{}, []string{}
		for _, check := range checks {
			if check.Outcome == "fail" {
				failed = append(failed, check.ID)
				notes = append(notes, fmt.Sprintf("Required check %s failed.", check.ID))
			}
		}
		if len(failed) > 0 {
			sort.Strings(failed)
			if !repair("checks:"+strings.Join(failed, ","), notes, failedResultEvidence(checks)) {
				return report, nil
			}
			continue
		}

		planning := Contract{}
		for _, coverage := range req.RequiredCoverage {
			planning.Scenarios = append(planning.Scenarios, Scenario{CriterionID: coverage.CriterionID, Consumer: coverage.Consumer})
		}
		lenses := mergeReviews(reviewLenses(planning, after.ChangedPaths), scheduledReviews)
		route("review", ReviewStage, lenses, "")
		for _, a := range currentAdvice(report.Routing, after.ID) {
			if a.Phase == "review" {
				lenses = mergeReviews(lenses, a.ExtraReviews)
			}
		}
		scheduledReviews = lenses
		var reviewNotes, failedLenses, reviewProof []string
		plans := make([]agentReviewPlan, 0, len(lenses))
		for _, lens := range lenses {
			delta, decision := planAgentReview(lens, reviews[lens], after)
			plans = append(plans, agentReviewPlan{delta: delta, decision: decision})
		}
		collectReview := func(lens string, a AgentAssessment) {
			result := agentReview{candidate: after}
			if a.Decision == "changes-requested" {
				result.findings = append([]string{a.Summary}, a.Findings...)
				failedLenses = append(failedLenses, lens)
				reviewNotes = append(reviewNotes, lens+": "+a.Summary)
				reviewNotes = append(reviewNotes, a.Findings...)
				reviewProof = append(reviewProof, a.Evidence...)
			}
			reviews[lens] = result
		}
		if batcher, ok := d.(AgentReviewBatcher); ok && batcher.ReviewParallelism() > 0 {
			inputs, results, e := assessBatch(batcher, plans)
			if e != nil {
				return report, e
			}
			for i, input := range inputs {
				if stopFor(results[i]) {
					return report, nil
				}
				collectReview(input.Lens, results[i])
			}
		} else {
			for _, plan := range plans {
				report.ReviewDecisions = append(report.ReviewDecisions, plan.decision)
				if plan.decision.ReusedFrom != "" {
					continue
				}
				a, e := assess(ReviewStage, plan.decision.Lens, plan.delta, nil)
				if e != nil {
					return report, e
				}
				if stopFor(a) {
					return report, nil
				}
				collectReview(plan.decision.Lens, a)
			}
		}
		if len(failedLenses) > 0 {
			sort.Strings(failedLenses)
			if !repair("review:"+strings.Join(failedLenses, ","), reviewNotes, reviewProof) {
				return report, nil
			}
			continue
		}

		a, e := assess(VerifyStage, "", nil, checks)
		if e != nil {
			return report, e
		}
		if stopFor(a) {
			return report, nil
		}
		if a.Decision == "changes-requested" {
			if !repair("verification", append([]string{a.Summary}, a.Findings...), a.Evidence) {
				return report, nil
			}
			continue
		}
		lastStage = Stage("check")
		checks, e = collectStaticChecks(ctx, d, &report, report.Candidate)
		if e != nil {
			return report, e
		}
		if e = requireManagedResults(req, checks); e != nil {
			return report, e
		}
		var lateFailures []string
		for _, check := range checks {
			if check.Outcome == "fail" {
				lateFailures = append(lateFailures, fmt.Sprintf("Final static check %s failed.", check.ID))
			}
		}
		if len(lateFailures) > 0 {
			if !repair("final-checks", lateFailures, failedResultEvidence(checks)) {
				return report, nil
			}
			continue
		}
		if report.Candidate.ID == initial.ID || len(report.Candidate.ChangedPaths) == 0 {
			report.Status = "needs-human"
			report.Summary = "The agent produced no patch. Inspect its assessment before deciding whether the issue needs a change."
			return report, nil
		}
		report.Status = "verified"
		report.Summary = a.Summary
		return report, nil
	}
}

func normalizeAgentAssessment(a AgentAssessment) AgentAssessment {
	if a.Decision == "approve" && len(a.Findings) > 0 {
		a.Decision = "changes-requested"
	}
	if strings.TrimSpace(a.Summary) == "" {
		a.Decision = "verification-blocked"
		a.Summary = "The assessment has no usable conclusion. Inspect its saved response."
	}
	switch a.Decision {
	case "approve", "changes-requested", "needs-human", "verification-blocked":
	default:
		a.Decision = "verification-blocked"
		a.Summary = "The assessment disposition is unavailable. Inspect its saved response."
	}
	return a
}

func planAgentReview(lens string, prior agentReview, candidate Snapshot) (*AgentReviewDelta, ReviewDecision) {
	decision := ReviewDecision{CandidateID: candidate.ID, Lens: lens, Reason: "Initial assessment for this review direction."}
	if prior.candidate.ID == "" {
		return nil, decision
	}
	if len(prior.findings) == 0 {
		var reuseReason string
		switch {
		case prior.candidate.ID == candidate.ID:
			reuseReason = "The source matches the candidate from the clean review."
		case canReuseReview(lens, prior.candidate, candidate, false):
			reuseReason = "Only tests or documentation changed since the clean specialized review."
		}
		if reuseReason != "" {
			decision.Reason = reuseReason
			decision.ReusedFrom = prior.candidate.ID
			return nil, decision
		}
	}
	paths, known := changedSince(prior.candidate, candidate)
	delta := &AgentReviewDelta{
		PreviousCandidateID: prior.candidate.ID,
		ChangedPaths:        paths,
		ChangedPathsKnown:   known,
		Findings:            prior.findings,
	}
	if known {
		decision.Reason = "Review changes since the previous assessment and their affected behavior."
	} else {
		decision.Reason = "The repair delta is incomplete. Review the full scope of this direction."
	}
	return delta, decision
}
