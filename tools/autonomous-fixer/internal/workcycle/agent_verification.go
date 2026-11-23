package workcycle

import (
	"fmt"
	"strings"
)

type VerificationScenario struct {
	Coverage
	ID          string `json:"id"`
	Description string `json:"description"`
}

type VerificationResult struct {
	CaseID      string                            `json:"case_id"`
	Observed    string                            `json:"observed"`
	Evidence    []string                          `json:"evidence"`
	References  []string                          `json:"references,omitempty"`
	Corrections []VerificationReferenceCorrection `json:"reference_corrections,omitempty"`
}

type VerificationReferenceCorrection struct {
	Original string `json:"original"`
	Resolved string `json:"resolved"`
}

func VerificationCases(req Request) ([]VerificationScenario, error) {
	byPair := map[Coverage][]VerificationScenario{}
	criteria, consumers := map[string]bool{}, map[string]bool{}
	for _, pair := range req.RequiredCoverage {
		byPair[pair] = nil
		criteria[pair.CriterionID], consumers[pair.Consumer] = true, true
	}
	for _, criterion := range req.RequiredCriteria {
		if !criteria[criterion] {
			return nil, fmt.Errorf("criterion %q has no required verification coverage", criterion)
		}
	}
	for _, consumer := range req.RequiredConsumers {
		if !consumers[consumer] {
			return nil, fmt.Errorf("consumer %q has no required verification coverage", consumer)
		}
	}
	for _, scenario := range req.VerificationScenarios {
		if _, ok := byPair[scenario.Coverage]; !ok {
			return nil, fmt.Errorf("verification scenario %q has an unknown criterion and consumer pair", scenario.ID)
		}
		if strings.TrimSpace(scenario.ID) == "" || strings.TrimSpace(scenario.Description) == "" {
			return nil, fmt.Errorf("verification scenarios require an ID and description")
		}
		byPair[scenario.Coverage] = append(byPair[scenario.Coverage], scenario)
	}
	var cases []VerificationScenario
	for _, pair := range req.RequiredCoverage {
		if scenarios := byPair[pair]; len(scenarios) > 0 {
			cases = append(cases, scenarios...)
		} else {
			cases = append(cases, VerificationScenario{Coverage: pair, ID: pair.CriterionID + "@" + pair.Consumer, Description: "Exercise the required behavior from the task and its agreed entry paths."})
		}
	}
	managed := map[Check]bool{}
	for _, check := range req.ManagedChecks {
		managed[check] = true
	}
	for _, check := range req.RequiredChecks {
		if !managed[check] {
			cases = append(cases, VerificationScenario{ID: "check:" + check.ID, Description: "Run " + check.ID + " in " + check.Scope + "."})
		}
	}
	if len(cases) == 0 {
		return nil, fmt.Errorf("final verification requires at least one scenario or check")
	}
	seen := map[string]bool{}
	for _, scenario := range cases {
		if seen[scenario.ID] {
			return nil, fmt.Errorf("duplicate verification case %q", scenario.ID)
		}
		seen[scenario.ID] = true
	}
	return cases, nil
}
