package workcycle

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
)

// ResumeVerification accepts only a reviewed engineering candidate blocked during final verification.
func ResumeVerification(ctx context.Context, req Request, d Driver, seed Report) (report Report, err error) {
	data, err := json.Marshal(seed)
	if err != nil {
		return Report{}, err
	}
	if err = json.Unmarshal(data, &report); err != nil {
		return Report{}, err
	}
	report.Status, report.Error, report.Question = "rejected", "", nil
	defer func() {
		if err != nil {
			report.Status, report.Error = "rejected", err.Error()
		}
		if saveErr := d.Save(context.WithoutCancel(ctx), report); saveErr != nil {
			err = errors.Join(err, saveErr)
			report.Status, report.Error = "rejected", err.Error()
		}
	}()
	baseline, priorData, err := verificationSeed(req, seed)
	if err != nil {
		return report, err
	}
	before, err := d.Snapshot(ctx)
	if err != nil {
		return report, err
	}
	if !sameSource(before, seed.Candidate) {
		return report, errors.New("resume workspace differs from the reviewed candidate")
	}
	static, err := collectStaticChecks(ctx, d, &report, before)
	if err != nil {
		return report, err
	}
	if err := requireManagedResults(req, static); err != nil {
		return report, err
	}
	if failures := failedResultEvidence(static); len(failures) > 0 {
		report.Status = "verification-blocked"
		report.Question = &Question{Kind: report.Status, Text: "Fresh controller checks failed for the resumed candidate.", Needed: "Resolve the failed checks before a new verification attempt; this continuation does not edit the candidate.", Evidence: failures}
		return report, nil
	}
	var upstream []Observation
	for _, observation := range report.Observations {
		if observation.Stage != VerifyStage {
			upstream = append(upstream, observation)
		}
	}
	response, runErr := d.Run(ctx, Invocation{Request: cloneRequest(req), Stage: VerifyStage, Contract: cloneContract(report.Contract), Candidate: before, Routing: currentAdvice(report.Routing, before.ID), Handoff: authorHandoff(upstream, VerifyStage), StaticChecks: static, BaselineDataID: baseline, PriorVerificationDataIDs: priorData})
	after, snapshotErr := d.Snapshot(ctx)
	report.Observations = append(report.Observations, Observation{Stage: VerifyStage, Before: before, After: after, Declaration: response})
	report.Candidate = after
	if runErr != nil || snapshotErr != nil {
		return report, errors.Join(runErr, snapshotErr)
	}
	if !sameSource(before, after) || response.CandidateID != before.ID {
		return report, errors.New("resumed verification changed the candidate or declared a stale candidate")
	}
	if response.Question != nil {
		if !validResumeQuestion(response.Question) {
			return report, errors.New("invalid resumed verification question")
		}
		report.Question = response.Question
		report.Status = response.Question.Kind
		return report, nil
	}
	if response.EnvironmentID != req.EnvironmentID || response.DataID == "" || response.DataID == baseline {
		return report, errors.New("resumed verification requires the same environment and fresh data B")
	}
	for _, prior := range priorData {
		if prior == response.DataID {
			return report, errors.New("resumed verification reused a prior verification dataset")
		}
	}
	static, err = collectStaticChecks(ctx, d, &report, after)
	if err != nil {
		return report, err
	}
	if err := requireManagedResults(req, static); err != nil {
		return report, err
	}
	response.Checks, err = mergeStaticResults(response.Checks, static)
	if err != nil {
		return report, err
	}
	findings, err := verificationFindings(*report.Contract, response)
	if err != nil {
		return report, err
	}
	findings = append(findings, response.Findings...)
	if len(findings) > 0 {
		var refs []string
		for _, finding := range findings {
			refs = append(refs, finding.Evidence...)
		}
		if !evidence(refs) {
			return report, errors.New("resumed verification findings have no evidence")
		}
		report.Status = "verification-blocked"
		report.Question = &Question{Kind: report.Status, Text: "Fresh verification reported failed scenarios, checks, or findings.", Needed: "Resolve the reported failures before a new verification attempt; this continuation does not edit the candidate.", Evidence: refs}
		return report, nil
	}
	report.Status = "verified"
	return report, nil
}

func ValidateVerificationResume(req Request, seed Report) error {
	_, _, err := verificationSeed(req, seed)
	return err
}

func verificationSeed(req Request, seed Report) (string, []string, error) {
	if req.Kind == "documentation" || !reflect.DeepEqual(req, seed.Request) {
		return "", nil, errors.New("engineering resume request differs from checkpoint")
	}
	if err := validateRequest(req); err != nil {
		return "", nil, err
	}
	if req.TaskID == "" || req.Task == "" || req.Context == "" || req.EnvironmentID == "" || len(req.RequiredCoverage) == 0 || len(req.RequiredChecks) == 0 {
		return "", nil, errors.New("incomplete engineering resume request")
	}
	if seed.Status != "verification-blocked" || seed.Error != "" || !validResumeQuestion(seed.Question) || seed.Question.Kind != "verification-blocked" || seed.Contract == nil || len(seed.Observations) == 0 {
		return "", nil, errors.New("resume requires a final-verification blocked checkpoint")
	}
	last := seed.Observations[len(seed.Observations)-1]
	if last.Stage != VerifyStage || !reflect.DeepEqual(last.Declaration.Question, seed.Question) {
		return "", nil, errors.New("checkpoint was not blocked in final verification")
	}
	initial := seed.Observations[0].Before
	if initial.ID == "" || len(initial.ChangedPaths) != 0 || seed.Candidate.ID == initial.ID || len(seed.Candidate.ChangedPaths) == 0 {
		return "", nil, errors.New("resume requires an implemented candidate from a clean baseline")
	}
	chain := initial
	var contract *Contract
	baseline := ""
	baselineCandidateID := ""
	var priorData []string
	implemented := false
	clean := map[string]Snapshot{}
	for _, observation := range seed.Observations {
		r := observation.Declaration
		if !sameSource(chain, observation.Before) || observation.After.ID == "" || r.CandidateID != observation.Before.ID {
			return "", nil, errors.New("resume snapshot history is discontinuous or stale")
		}
		if !stageChangeAllowed(observation.Stage, observation.Before, observation.After) {
			return "", nil, errors.New("resume history contains a read-only source change")
		}
		chain = observation.After
		if r.Question != nil && observation.Stage != VerifyStage {
			return "", nil, errors.New("resume does not support unresolved questions before final verification")
		}
		switch observation.Stage {
		case ContractStage:
			if baseline != "" || implemented || r.Contract == nil {
				return "", nil, errors.New("invalid contract sequence in resume checkpoint")
			}
			contract = cloneContract(r.Contract)
			var err error
			contract.Checks, _, err = bindRequiredChecks(contract.Checks, req.RequiredChecks)
			if err != nil {
				return "", nil, err
			}
			if err = validateResumeContract(*contract, req); err != nil {
				return "", nil, err
			}
		case ReproduceStage:
			if contract == nil || baseline != "" || implemented || r.EnvironmentID != req.EnvironmentID || r.DataID == "" {
				return "", nil, errors.New("resume checkpoint has no valid measured baseline")
			}
			if _, err := reconcileBaseline(contract, r.Scenarios); err != nil {
				return "", nil, err
			}
			baseline = r.DataID
			baselineCandidateID = observation.After.ID
		case ImplementStage:
			if baseline == "" || !evidence(r.Evidence) {
				return "", nil, errors.New("resume implementation lacks baseline or evidence")
			}
			implemented = true
		case ReviewStage:
			if !implemented || !evidence(r.Evidence) {
				return "", nil, errors.New("resume review lacks an implemented candidate or evidence")
			}
			switch observation.Lens {
			case "correctness", "compatibility", "data", "security", "concurrency":
			default:
				return "", nil, errors.New("unknown resume review lens")
			}
			for _, finding := range r.Findings {
				if finding.ID == "" || finding.ScenarioID == "" || finding.Location == "" || finding.Description == "" || !evidence(finding.Evidence) {
					return "", nil, errors.New("incomplete resume review finding")
				}
				if finding.Compatibility != nil {
					if err := validateCompatibility(finding, req, *contract); err != nil {
						return "", nil, err
					}
				}
				if !knownScenario(finding.ScenarioID, contract.Scenarios) && finding.Compatibility != nil {
					contract.Scenarios = append(contract.Scenarios, finding.Compatibility.Scenario)
				}
				if !knownScenario(finding.ScenarioID, contract.Scenarios) {
					return "", nil, errors.New("resume contains an unresolved contract finding")
				}
			}
			if len(r.Findings) == 0 {
				clean[observation.Lens] = observation.After
			} else {
				delete(clean, observation.Lens)
			}
		case VerifyStage:
			if !implemented || contract == nil {
				return "", nil, errors.New("resume verification preceded implementation")
			}
			if err := requireCleanResumeReviews(*contract, observation.After, seed.Routing, clean); err != nil {
				return "", nil, err
			}
			if r.Question != nil && !validResumeQuestion(r.Question) {
				return "", nil, errors.New("invalid checkpoint verification question")
			}
			if r.DataID != "" {
				priorData = append(priorData, r.DataID)
			}
		default:
			return "", nil, errors.New("unsupported stage in engineering resume checkpoint")
		}
	}
	if seed.Candidate.ID == baselineCandidateID {
		return "", nil, errors.New("resume candidate has no implementation change after reproduction")
	}
	if !sameSource(chain, seed.Candidate) || baseline == "" || !implemented || !reflect.DeepEqual(contract, seed.Contract) {
		return "", nil, errors.New("resume contract or candidate differs from accepted stage history")
	}
	if err := requireCleanResumeReviews(*contract, seed.Candidate, seed.Routing, clean); err != nil {
		return "", nil, err
	}
	return baseline, priorData, nil
}

func sameSource(a, b Snapshot) bool {
	return a.ID == b.ID && reflect.DeepEqual(a.ChangedPaths, b.ChangedPaths)
}
func validResumeQuestion(q *Question) bool {
	return q != nil && (q.Kind == "needs-human" || q.Kind == "verification-blocked") && q.Text != "" && q.Needed != "" && evidence(q.Evidence)
}
func validateResumeContract(c Contract, req Request) error {
	if err := validateContract(c, req.RequiredCriteria); err != nil {
		return err
	}
	for _, consumer := range req.RequiredConsumers {
		found := false
		for _, s := range c.Scenarios {
			found = found || s.Consumer == consumer
		}
		if !found {
			return fmt.Errorf("resume consumer %s has no scenario", consumer)
		}
	}
	for _, pair := range req.RequiredCoverage {
		found := false
		for _, s := range c.Scenarios {
			found = found || (s.CriterionID == pair.CriterionID && s.Consumer == pair.Consumer)
		}
		if !found {
			return fmt.Errorf("resume is missing coverage for %s/%s", pair.CriterionID, pair.Consumer)
		}
	}
	return nil
}
func requireCleanResumeReviews(contract Contract, candidate Snapshot, routing []RoutingAdvice, clean map[string]Snapshot) error {
	lenses := reviewLenses(contract, candidate.ChangedPaths)
	for _, advice := range routing {
		if advice.Phase == "review" && advice.CandidateID == candidate.ID {
			lenses = mergeReviews(lenses, advice.ExtraReviews)
		}
	}
	for _, lens := range lenses {
		if prior, ok := clean[lens]; !ok || !sameSource(prior, candidate) {
			return fmt.Errorf("resume requires a clean %s review for the current candidate", lens)
		}
	}
	return nil
}
func requireManagedResults(req Request, results []Result) error {
	for _, required := range req.ManagedChecks {
		found := false
		for _, result := range results {
			found = found || result.ID == required.ID
		}
		if !found {
			return fmt.Errorf("resumed controller check %s is missing", required.ID)
		}
	}
	return nil
}
func failedResultEvidence(results []Result) []string {
	var refs []string
	for _, result := range results {
		if result.Outcome == "fail" {
			refs = append(refs, result.Evidence...)
		}
	}
	return refs
}
