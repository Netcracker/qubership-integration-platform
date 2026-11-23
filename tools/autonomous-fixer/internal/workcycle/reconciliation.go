package workcycle

import "fmt"

func bindRequiredChecks(declared, required []Check) ([]Check, []ContractAdjustment, error) {
	checks := append([]Check(nil), declared...)
	positions := map[string]int{}
	for i, check := range checks {
		if _, exists := positions[check.ID]; exists {
			return nil, nil, fmt.Errorf("duplicate check %s", check.ID)
		}
		positions[check.ID] = i
	}
	var adjustments []ContractAdjustment
	for _, check := range required {
		before := ""
		if i, exists := positions[check.ID]; exists {
			before = checks[i].Scope
			checks[i] = check
		} else {
			checks = append(checks, check)
		}
		if before != check.Scope {
			adjustments = append(adjustments, ContractAdjustment{Kind: "required-check", ID: check.ID, Before: before, After: check.Scope})
		}
	}
	return checks, adjustments, nil
}

func reconcileBaseline(contract *Contract, observed []Result) ([]ContractAdjustment, error) {
	ids := make([]string, 0, len(contract.Scenarios))
	for _, scenario := range contract.Scenarios {
		ids = append(ids, scenario.ID)
	}
	if err := validateResults(ids, observed); err != nil {
		return nil, err
	}
	var adjustments []ContractAdjustment
	for i := range contract.Scenarios {
		scenario := &contract.Scenarios[i]
		for _, result := range observed {
			if result.ID == scenario.ID && result.Outcome != scenario.Baseline {
				adjustments = append(adjustments, ContractAdjustment{Kind: "baseline-observation", ID: scenario.ID, Before: scenario.Baseline, After: result.Outcome, Evidence: append([]string(nil), result.Evidence...)})
				scenario.Baseline = result.Outcome
			}
		}
	}
	return adjustments, nil
}

func validateCompatibility(f Finding, req Request, contract Contract) error {
	proof := f.Compatibility
	s := proof.Scenario
	if s.ID != f.ScenarioID || s.Baseline != "pass" || proof.BaselineResult.ID != s.ID || proof.CandidateResult.ID != s.ID || proof.BaselineResult.Outcome != "pass" || proof.CandidateResult.Outcome != "fail" {
		return fmt.Errorf("compatibility finding %s requires a baseline pass and candidate failure for the same scenario", f.ID)
	}
	if err := validateContract(Contract{Scenarios: []Scenario{s}}, []string{s.CriterionID}); err != nil {
		return err
	}
	criterion, coveredPair := false, false
	for _, id := range req.RequiredCriteria {
		if id == s.CriterionID {
			criterion = true
		}
	}
	for _, previous := range contract.Scenarios {
		if previous.Consumer == s.Consumer && previous.CriterionID == s.CriterionID {
			coveredPair = true
		}
		if previous.ID == s.ID && previous != s {
			return fmt.Errorf("compatibility finding %s changes an accepted scenario", f.ID)
		}
	}
	if !criterion || !coveredPair {
		return fmt.Errorf("compatibility finding %s expands the accepted criteria or consumers", f.ID)
	}
	if !evidence(proof.BaselineResult.Evidence) || !evidence(proof.CandidateResult.Evidence) {
		return fmt.Errorf("compatibility finding %s lacks comparison evidence", f.ID)
	}
	for _, baseline := range proof.BaselineResult.Evidence {
		for _, candidate := range proof.CandidateResult.Evidence {
			if baseline == candidate {
				return fmt.Errorf("compatibility finding %s reuses baseline evidence for the candidate", f.ID)
			}
		}
	}
	return nil
}
