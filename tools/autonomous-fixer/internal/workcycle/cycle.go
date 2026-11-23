package workcycle

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
)

func Run(ctx context.Context, req Request, d Driver) (report Report, err error) {
	req = cloneRequest(req)
	report = Report{Request: cloneRequest(req), Status: "rejected"}
	var lastStage Stage
	var questionAdvised bool
	var staticResults []Result
	var reviewDelta *ReviewDelta
	var pendingQuestion *Question
	probeAttempts := map[string]bool{}
	defer func() {
		if err != nil {
			report.Error = err.Error()
		}
		if (err != nil || report.Question != nil) && ctx.Err() == nil && !questionAdvised {
			advice, adviceErr := advise(ctx, d, AdviceInput{Phase: "failure", Stage: lastStage, Request: req, Candidate: report.Candidate, Contract: report.Contract, Failure: report.Error, Question: report.Question})
			if advice != nil {
				report.Routing = append(report.Routing, *advice)
			}
			if adviceErr != nil {
				err = errors.Join(err, adviceErr)
				report.Error = err.Error()
			}
		}
		if saveErr := d.Save(context.WithoutCancel(ctx), report); saveErr != nil {
			err = errors.Join(err, saveErr)
			report.Status = "rejected"
			report.Error = err.Error()
		}
	}()
	if req.TaskID == "" || req.Task == "" || req.Context == "" || req.EnvironmentID == "" || len(req.RequiredCriteria) == 0 {
		return report, errors.New("incomplete cycle request")
	}
	if e := validateRequest(req); e != nil {
		return report, e
	}
	var call func(Stage, string, []Finding, string) (Response, error)
	call = func(stage Stage, lens string, findings []Finding, data string) (Response, error) {
		lastStage = stage
		questionAdvised = false
		if e := ctx.Err(); e != nil {
			return Response{}, e
		}
		before, e := d.Snapshot(ctx)
		if e != nil {
			return Response{}, e
		}
		if report.Candidate.ID != "" && report.Candidate.ID != before.ID {
			return Response{}, errors.New("snapshot changed between stages")
		}
		if before.ID == "" {
			return Response{}, errors.New("empty snapshot ID")
		}
		response, e := d.Run(ctx, Invocation{Handoff: authorHandoff(report.Observations, stage), StaticChecks: staticResults, ReviewDelta: reviewDelta, PendingQuestion: pendingQuestion, Routing: currentAdvice(report.Routing, before.ID), Request: cloneRequest(req), Stage: stage, Contract: cloneContract(report.Contract), Candidate: before, Lens: lens, Findings: findings, BaselineDataID: data})
		after, se := d.Snapshot(ctx)
		report.Observations = append(report.Observations, Observation{Stage: stage, Lens: lens, Before: before, After: after, Declaration: response})
		report.Candidate = after
		if e != nil || se != nil {
			return response, errors.Join(e, se)
		}
		if after.ID == "" {
			return response, errors.New("empty snapshot ID after invocation")
		}
		if stage == ImplementStage && response.Question == nil && !evidence(response.Evidence) {
			return response, errors.New("implementation has no evidence")
		}
		if stage != ImplementStage && response.CandidateID != before.ID {
			return response, errors.New("response candidate ID is stale or missing")
		}
		if !stageChangeAllowed(stage, before, after) {
			return response, fmt.Errorf("%s changed snapshot %s", stage, before.ID)
		}
		if response.Question != nil {
			q := response.Question
			if (q.Kind != "needs-human" && q.Kind != "verification-blocked") || q.Text == "" || q.Needed == "" || !evidence(q.Evidence) {
				return response, errors.New("incomplete diagnostic question")
			}
			advice, adviceErr := advise(ctx, d, AdviceInput{Phase: "failure", Stage: stage, Request: req, Candidate: after, Contract: report.Contract, Question: q})
			if adviceErr != nil {
				return response, adviceErr
			}
			if advice != nil {
				report.Routing = append(report.Routing, *advice)
				questionAdvised = true
			}
			key := string(stage) + ":" + lens + ":" + after.ID
			canProbe := q.Kind == "verification-blocked" && stage != ImplementStage && (advice == nil || advice.QuestionAction == "observable_probe")
			if canProbe && !probeAttempts[key] {
				probeAttempts[key] = true
				pendingQuestion = q
				if e := d.Save(ctx, report); e != nil {
					return response, e
				}
				retry, retryErr := call(stage, lens, findings, data)
				pendingQuestion = nil
				return retry, retryErr
			}
			report.Question = q
			report.Status = q.Kind
			return response, errQuestion
		}
		if e = d.Save(ctx, report); e != nil {
			return response, e
		}
		return response, nil
	}
	initial, e := d.Snapshot(ctx)
	if e != nil {
		return report, e
	}
	intake, e := advise(ctx, d, AdviceInput{Phase: "intake", Request: req, Candidate: initial})
	if e != nil {
		return report, e
	}
	if intake != nil {
		report.Routing = append(report.Routing, *intake)
	}
	report.Candidate = initial
	response, e := call(ContractStage, "", nil, "")
	if e != nil {
		return finish(report, e)
	}
	if len(req.RequiredCoverage) == 0 {
		return report, errors.New("no required criterion and consumer coverage configured")
	}
	if len(req.RequiredChecks) == 0 {
		return report, errors.New("no required checks configured")
	}
	if response.Contract == nil {
		return report, errors.New("missing contract")
	}
	coverage, adviceErr := advise(ctx, d, AdviceInput{Phase: "contract", Stage: ContractStage, Request: req, Candidate: report.Candidate, Contract: response.Contract})
	if adviceErr != nil {
		return report, adviceErr
	}
	if coverage != nil {
		report.Routing = append(report.Routing, *coverage)
		if len(coverage.CoverageCandidates) > 0 {
			report.Contract = cloneContract(response.Contract)
			response, e = call(ContractStage, "", nil, "")
			if e != nil {
				return finish(report, e)
			}
			if response.Contract == nil {
				return report, errors.New("missing revised contract")
			}
		}
	}
	accepted := cloneContract(response.Contract)
	accepted.Checks, report.ContractAdjustments, e = bindRequiredChecks(accepted.Checks, req.RequiredChecks)
	if e != nil {
		return report, e
	}
	response.Contract = accepted
	if e = validateContract(*response.Contract, req.RequiredCriteria); e != nil {
		return report, e
	}
	for _, consumer := range req.RequiredConsumers {
		found := false
		for _, scenario := range response.Contract.Scenarios {
			if scenario.Consumer == consumer {
				found = true
			}
		}
		if !found {
			return report, fmt.Errorf("consumer %s has no scenario", consumer)
		}
	}
	for _, pair := range req.RequiredCoverage {
		found := false
		for _, scenario := range response.Contract.Scenarios {
			if scenario.CriterionID == pair.CriterionID && scenario.Consumer == pair.Consumer {
				found = true
			}
		}
		if !found {
			return report, fmt.Errorf("missing coverage for criterion %s and consumer %s", pair.CriterionID, pair.Consumer)
		}
	}
	report.Contract = cloneContract(response.Contract)
	response, e = call(ReproduceStage, "", nil, "")
	if e != nil {
		return finish(report, e)
	}
	if response.EnvironmentID != req.EnvironmentID || response.DataID == "" {
		return report, errors.New("baseline environment or data missing")
	}
	initialID := report.Candidate.ID
	baselineData := response.DataID
	adjustments, e := reconcileBaseline(report.Contract, response.Scenarios)
	if e != nil {
		return report, e
	}
	report.ContractAdjustments = append(report.ContractAdjustments, adjustments...)
	response, e = call(ImplementStage, "", nil, baselineData)
	if e != nil {
		return finish(report, e)
	}
	seenFailures := map[string]bool{}
	cleanReviews := map[string]Snapshot{}
	failedReviews := map[string]bool{}
	var previousCandidate Snapshot
	var previousFindings []Finding
	repair := func(candidate Snapshot, findings []Finding) error {
		keys := []string{}
		refs := []string{}
		for _, f := range findings {
			keys = append(keys, f.ID+":"+f.ScenarioID+":"+f.Description)
			refs = append(refs, f.Evidence...)
		}
		sort.Strings(keys)
		key := candidate.ID + "|" + strings.Join(keys, "|")
		if seenFailures[key] {
			report.Status = "needs-human"
			report.Question = &Question{Kind: "needs-human", Text: "The same candidate still has the same confirmed findings after repair.", Needed: "Decide how to resolve the repeated findings.", Evidence: refs}
			return errQuestion
		}
		seenFailures[key] = true
		previousCandidate = candidate
		previousFindings = append([]Finding(nil), findings...)
		_, e := call(ImplementStage, "", findings, baselineData)
		return e
	}
	for {
		if e := ctx.Err(); e != nil {
			return report, e
		}
		candidate := report.Candidate
		var findings []Finding
		lastStage = Stage("check")
		staticResults, e = collectStaticChecks(ctx, d, &report, candidate)
		if e != nil {
			return report, e
		}
		for _, result := range staticResults {
			if result.Outcome == "fail" {
				findings = append(findings, Finding{ID: "static-check:" + result.ID, Location: result.ID, Description: "Required static check failed: " + result.ID, Evidence: result.Evidence})
			}
		}
		if len(findings) > 0 {
			if e = repair(candidate, findings); e != nil {
				return finish(report, e)
			}
			continue
		}
		reviewDelta = nil
		if previousCandidate.ID != "" {
			paths, _ := changedSince(previousCandidate, candidate)
			reviewDelta = &ReviewDelta{PreviousCandidateID: previousCandidate.ID, ChangedPaths: paths, Findings: previousFindings}
		}
		lenses := reviewLenses(*report.Contract, candidate.ChangedPaths)
		advice, adviceErr := advise(ctx, d, AdviceInput{Phase: "review", Stage: ReviewStage, MandatoryReviews: lenses, Request: req, Candidate: candidate, Contract: report.Contract})
		if adviceErr != nil {
			return report, adviceErr
		}
		if advice != nil {
			report.Routing = append(report.Routing, *advice)
			lenses = mergeReviews(lenses, advice.ExtraReviews)
		}
		for _, lens := range lenses {
			if prior, ok := cleanReviews[lens]; ok && canReuseReview(lens, prior, candidate, failedReviews[lens]) {
				report.ReviewDecisions = append(report.ReviewDecisions, ReviewDecision{CandidateID: candidate.ID, Lens: lens, ReusedFrom: prior.ID, Reason: "Only tests or documentation changed since the clean review."})
				continue
			}
			report.ReviewDecisions = append(report.ReviewDecisions, ReviewDecision{CandidateID: candidate.ID, Lens: lens, Reason: "Review the candidate and any repair delta."})
			response, e = call(ReviewStage, lens, nil, baselineData)
			if e != nil {
				return finish(report, e)
			}
			if !evidence(response.Evidence) {
				return report, errors.New("review has no evidence")
			}
			for _, f := range response.Findings {
				if f.ID == "" || f.Description == "" || f.Location == "" || f.ScenarioID == "" || !evidence(f.Evidence) {
					return report, errors.New("incomplete review finding")
				}
				if f.Compatibility != nil {
					if e = validateCompatibility(f, req, *report.Contract); e != nil {
						return report, e
					}
				}
				if !knownScenario(f.ScenarioID, report.Contract.Scenarios) && f.Compatibility != nil {
					report.Contract.Scenarios = append(report.Contract.Scenarios, f.Compatibility.Scenario)
					refs := append([]string(nil), f.Compatibility.BaselineResult.Evidence...)
					refs = append(refs, f.Compatibility.CandidateResult.Evidence...)
					report.ContractAdjustments = append(report.ContractAdjustments, ContractAdjustment{Kind: "compatibility-regression", ID: f.ScenarioID, Before: "", After: f.Compatibility.Scenario.Behavior, Evidence: refs})
				}
				if !knownScenario(f.ScenarioID, report.Contract.Scenarios) {
					report.Status = "needs-human"
					report.Question = &Question{Kind: "needs-human", Text: fmt.Sprintf("Review at %s identified scenario %s outside the accepted contract: %s", f.Location, f.ScenarioID, f.Description), Needed: "Decide whether to revise the contract and required coverage.", Evidence: f.Evidence}
					return report, nil
				}
			}
			failedReviews[lens] = len(response.Findings) > 0
			if len(response.Findings) == 0 {
				cleanReviews[lens] = candidate
			} else {
				delete(cleanReviews, lens)
			}
			findings = append(findings, response.Findings...)
		}
		if len(findings) > 0 {
			e = repair(candidate, findings)
			if e != nil {
				return finish(report, e)
			}
			continue
		}
		lastStage = Stage("check")
		staticResults, e = collectStaticChecks(ctx, d, &report, candidate)
		if e != nil {
			return report, e
		}
		response, e = call(VerifyStage, "", nil, baselineData)
		if e != nil {
			return finish(report, e)
		}
		if candidate.ID != report.Candidate.ID {
			return report, errors.New("candidate changed after review")
		}
		if response.EnvironmentID != req.EnvironmentID || response.DataID == "" || response.DataID == baselineData {
			return report, errors.New("final verification requires the same environment and distinct data B")
		}
		lastStage = Stage("check")
		staticResults, e = collectStaticChecks(ctx, d, &report, candidate)
		if e != nil {
			return report, e
		}
		response.Checks, e = mergeStaticResults(response.Checks, staticResults)
		if e != nil {
			return report, e
		}
		findings, e = verificationFindings(*report.Contract, response)
		if e != nil {
			return report, e
		}
		if len(findings) > 0 {
			if e = repair(candidate, findings); e != nil {
				return finish(report, e)
			}
			continue
		}

		if candidate.ID == initialID || len(candidate.ChangedPaths) == 0 {
			report.Status = "needs-human"
			report.Question = &Question{Kind: "needs-human", Text: "The cycle produced no source change.", Needed: "Decide whether the baseline needs a fix or the task needs a different scope.", Evidence: response.Evidence}
			if !evidence(report.Question.Evidence) {
				for _, result := range response.Scenarios {
					report.Question.Evidence = append(report.Question.Evidence, result.Evidence...)
				}
			}
			return report, nil
		}
		report.Status = "verified"
		return report, nil
	}
}

var errQuestion = errors.New("cycle requires input")

func finish(r Report, e error) (Report, error) {
	if errors.Is(e, errQuestion) {
		return r, nil
	}
	return r, e
}
func evidence(refs []string) bool {
	if len(refs) == 0 {
		return false
	}
	for _, ref := range refs {
		if strings.TrimSpace(ref) == "" {
			return false
		}
	}
	return true
}
func cloneContract(c *Contract) *Contract {
	if c == nil {
		return nil
	}
	copy := *c
	copy.Scenarios = append([]Scenario(nil), c.Scenarios...)
	copy.Checks = append([]Check(nil), c.Checks...)
	copy.Risks = append([]string(nil), c.Risks...)
	return &copy
}
func validateContract(c Contract, criteria []string) error {
	for _, risk := range c.Risks {
		switch risk {
		case "correctness", "compatibility", "data", "security", "concurrency":
		default:
			return fmt.Errorf("unknown contract risk %s", risk)
		}
	}
	seen := map[string]bool{}
	covered := map[string]bool{}
	for _, s := range c.Scenarios {
		if s.ID == "" || seen[s.ID] || s.CriterionID == "" || s.Consumer == "" || s.Behavior == "" || s.Procedure == "" || (s.Baseline != "pass" && s.Baseline != "fail") {
			return errors.New("incomplete or duplicate scenario")
		}
		seen[s.ID] = true
		covered[s.CriterionID] = true
	}
	for _, id := range criteria {
		if id == "" || !covered[id] {
			return fmt.Errorf("criterion %s has no scenario", id)
		}
	}
	seen = map[string]bool{}
	for _, c := range c.Checks {
		if c.ID == "" || c.Scope == "" || seen[c.ID] {
			return errors.New("incomplete or duplicate check")
		}
		seen[c.ID] = true
	}
	return nil
}
func reviewLenses(c Contract, paths []string) []string {
	lenses := []string{"correctness"}
	set := map[string]bool{"correctness": true}
	consumers := map[string]bool{}
	add := func(s string) {
		if !set[s] {
			set[s] = true
			lenses = append(lenses, s)
		}
	}
	for _, s := range c.Scenarios {
		consumers[s.Consumer] = true
	}
	if len(consumers) > 1 {
		add("compatibility")
	}
	for _, r := range c.Risks {
		switch r {
		case "compatibility", "data", "security", "concurrency":
			add(r)
		}
	}
	modules := map[string]bool{}
	for _, p := range paths {
		modules[strings.Split(p, "/")[0]] = true
		p = strings.ToLower(p)
		if strings.Contains(p, "shared") || strings.Contains(p, "common") || strings.Contains(p, "schemas/") || strings.Contains(p, "ui/") {
			add("compatibility")
		}
		if strings.Contains(p, "migration") || strings.Contains(p, "/dao/") {
			add("data")
		}
		if strings.Contains(p, "auth") || strings.Contains(p, "security") {
			add("security")
		}
		if strings.Contains(p, "lock") || strings.Contains(p, "concurrent") {
			add("concurrency")
		}
	}
	if len(modules) > 1 {
		add("compatibility")
	}
	return lenses
}

func cloneRequest(r Request) Request {
	r.ManagedChecks = append([]Check(nil), r.ManagedChecks...)
	r.RequiredCoverage = append([]Coverage(nil), r.RequiredCoverage...)
	r.VerificationScenarios = append([]VerificationScenario(nil), r.VerificationScenarios...)
	r.RequiredCriteria = append([]string(nil), r.RequiredCriteria...)
	r.RequiredChecks = append([]Check(nil), r.RequiredChecks...)
	r.RequiredConsumers = append([]string(nil), r.RequiredConsumers...)
	return r
}

func knownScenario(id string, ss []Scenario) bool {
	for _, s := range ss {
		if s.ID == id {
			return true
		}
	}
	return false
}
func validateResults(ids []string, rr []Result) error {
	allowed := map[string]bool{}
	for _, id := range ids {
		allowed[id] = true
	}
	seen := map[string]bool{}
	for _, r := range rr {
		if !allowed[r.ID] || seen[r.ID] || !evidence(r.Evidence) || (r.Outcome != "pass" && r.Outcome != "fail") {
			return errors.New("unexpected, duplicate, or malformed result")
		}
		seen[r.ID] = true
	}
	if len(seen) != len(allowed) {
		return errors.New("missing result")
	}
	return nil
}
func verificationFindings(c Contract, r Response) ([]Finding, error) {
	ids := []string{}
	for _, s := range c.Scenarios {
		ids = append(ids, s.ID)
	}
	if e := validateResults(ids, r.Scenarios); e != nil {
		return nil, e
	}
	ids = nil
	for _, c := range c.Checks {
		ids = append(ids, c.ID)
	}
	if e := validateResults(ids, r.Checks); e != nil {
		return nil, e
	}
	var ff []Finding
	for _, result := range r.Scenarios {
		if result.Outcome == "fail" {
			for _, s := range c.Scenarios {
				if s.ID == result.ID {
					ff = append(ff, Finding{ID: "verification-scenario:" + result.ID, ScenarioID: result.ID, Location: s.Consumer, Description: "Scenario failed: " + s.Behavior, Evidence: result.Evidence})
				}
			}
		}
	}
	for _, result := range r.Checks {
		if result.Outcome == "fail" {
			for _, check := range c.Checks {
				if check.ID == result.ID {
					ff = append(ff, Finding{ID: "verification-check:" + result.ID, Location: check.Scope, Description: "Required check failed: " + check.ID, Evidence: result.Evidence})
				}
			}
		}
	}
	return ff, nil
}
func validateRequest(r Request) error {
	pairs := map[Coverage]bool{}
	for _, pair := range r.RequiredCoverage {
		criterion, consumer := false, false
		for _, id := range r.RequiredCriteria {
			if id == pair.CriterionID {
				criterion = true
			}
		}
		for _, id := range r.RequiredConsumers {
			if id == pair.Consumer {
				consumer = true
			}
		}
		if !criterion || !consumer || pairs[pair] {
			return errors.New("unknown or duplicate required coverage pair")
		}
		pairs[pair] = true
	}
	for _, ids := range [][]string{r.RequiredCriteria, r.RequiredConsumers} {
		seen := map[string]bool{}
		for _, id := range ids {
			if strings.TrimSpace(id) == "" || seen[id] {
				return errors.New("empty or duplicate request requirement")
			}
			seen[id] = true
		}
	}
	seen := map[string]bool{}
	for _, check := range r.RequiredChecks {
		if check.ID == "" || check.Scope == "" || seen[check.ID] {
			return errors.New("incomplete or duplicate request check")
		}
		seen[check.ID] = true
	}
	return nil
}
