package fixer

import (
	"fmt"
	"regexp"
	"strings"
)

var commitPattern = regexp.MustCompile(`^[a-fA-F0-9]{40}$`)
var digestPattern = regexp.MustCompile(`^[a-fA-F0-9]{64}$`)

func ValidateManifest(m *Manifest, expected ManifestExpected) error {
	if m == nil {
		return fmt.Errorf("manifest is missing")
	}
	if m.SchemaVersion != SchemaVersion || m.Generation <= 0 || m.ContractVersion <= 0 {
		return fmt.Errorf("invalid manifest version")
	}
	if m.TaskID == "" || m.AttemptID == "" || m.ExecutionID == "" || m.Repository == "" ||
		m.TaskID != expected.TaskID || m.AttemptID != expected.AttemptID || m.ExecutionID != expected.ExecutionID ||
		m.Repository != expected.Repository || m.Generation != expected.Generation || m.ContractVersion != expected.ContractVersion {
		return fmt.Errorf("manifest does not match the active task")
	}
	for _, pair := range [][2]string{{m.BaseSHA, expected.BaseSHA}, {m.CandidateSHA, expected.CandidateSHA}, {m.ParentSHA, expected.ParentSHA}} {
		if !commitPattern.MatchString(pair[0]) || pair[0] != pair[1] {
			return fmt.Errorf("manifest commit does not match the expected context")
		}
	}
	if m.Run != expected.Run || m.Run.RunID <= 0 || m.Run.RunAttempt <= 0 || m.Run.Workflow != "agent-work" || !commitPattern.MatchString(m.Run.TrustedSHA) {
		return fmt.Errorf("manifest run does not match the expected context")
	}
	if len(m.Files) == 0 {
		return fmt.Errorf("manifest files are missing")
	}
	seen := make(map[string]bool)
	var paths []string
	for _, file := range m.Files {
		if seen[file.Path] || !digestPattern.MatchString(file.SHA256) {
			return fmt.Errorf("duplicate path or invalid file digest")
		}
		seen[file.Path] = true
		paths = append(paths, file.Path)
	}
	if violations := CheckPaths(paths, expected.ScopePaths, expected.AllowCIChanges); len(violations) != 0 {
		return fmt.Errorf("candidate includes a path outside the task policy")
	}
	if m.Bundle.ArtifactID <= 0 || m.Bundle.Format != "git-bundle" || !digestPattern.MatchString(m.Bundle.SHA256) || !digestPattern.MatchString(m.TestSetSHA256) {
		return fmt.Errorf("invalid bundle or test-set identity")
	}
	for _, pair := range [][2]string{{m.TestSetSHA256, expected.TestSetSHA256}, {m.ContextSHA256, expected.ContextSHA256}, {m.ProcessReportSHA256, expected.ProcessReportSHA256}} {
		if !digestPattern.MatchString(pair[1]) || pair[0] != pair[1] {
			return fmt.Errorf("test set, context, or process report does not match the expected evidence")
		}
	}
	lenses, err := requiredIDs("review lenses", expected.RequiredReviewLenses)
	if err != nil {
		return err
	}
	reviewIDs := make(map[string]bool)
	for _, review := range m.Reviews {
		if !lenses[review.Lens] || reviewIDs[review.WorkItemID] || strings.TrimSpace(review.WorkItemID) == "" || review.CandidateSHA != m.CandidateSHA || review.ContextSHA256 != m.ContextSHA256 || review.TestSetSHA256 != m.TestSetSHA256 || review.Verdict != "passed" || review.ReportArtifactID <= 0 {
			return fmt.Errorf("review does not accept this candidate")
		}
		reviewIDs[review.WorkItemID] = true
		delete(lenses, review.Lens)
	}
	if len(lenses) != 0 {
		return fmt.Errorf("required candidate reviews are missing")
	}
	v := m.Verification
	if v.CandidateSHA != m.CandidateSHA || v.ContractVersion != m.ContractVersion || v.Status != "passed" || !v.FullScenarioRepeated || v.ReportArtifactID <= 0 {
		return fmt.Errorf("verification does not accept this candidate")
	}
	if strings.TrimSpace(expected.EnvironmentID) == "" || v.EnvironmentID != expected.EnvironmentID || v.EnvironmentID != v.BaselineEnvironmentID || strings.TrimSpace(v.DataAID) == "" || strings.TrimSpace(v.DataBID) == "" || v.DataAID == v.DataBID {
		return fmt.Errorf("verification must repeat the scenario in the same environment with independent data")
	}
	if len(expected.RequiredScenarios) == 0 {
		return fmt.Errorf("required scenarios are missing from the expected policy")
	}
	scenarios := make(map[string]ScenarioExpected)
	for _, scenario := range expected.RequiredScenarios {
		if strings.TrimSpace(scenario.ID) == "" || strings.TrimSpace(scenario.CriterionID) == "" || strings.TrimSpace(scenario.Consumer) == "" || scenarios[scenario.ID].ID != "" {
			return fmt.Errorf("expected scenarios require unique IDs, criteria, and consumers")
		}
		scenarios[scenario.ID] = scenario
	}
	for _, scenario := range v.Scenarios {
		required, exists := scenarios[scenario.ID]
		if !exists || scenario.CriterionID != required.CriterionID || scenario.Consumer != required.Consumer || scenario.CandidateSHA != m.CandidateSHA || scenario.TestSetSHA256 != m.TestSetSHA256 || scenario.Status != "passed" || scenario.ReportArtifactID <= 0 {
			return fmt.Errorf("scenario does not accept this candidate and required coverage")
		}
		if scenario.EnvironmentID != expected.EnvironmentID || scenario.BaselineEnvironmentID != expected.EnvironmentID || strings.TrimSpace(scenario.DataAID) == "" || strings.TrimSpace(scenario.DataBID) == "" || scenario.DataAID == scenario.DataBID {
			return fmt.Errorf("scenario must repeat in the baseline environment with independent data")
		}
		delete(scenarios, scenario.ID)
	}
	if len(scenarios) != 0 {
		return fmt.Errorf("required scenarios are missing")
	}
	checks, err := requiredIDs("module checks", expected.RequiredChecks)
	if err != nil {
		return err
	}
	for _, check := range v.Checks {
		if !checks[check.ID] || check.CandidateSHA != m.CandidateSHA || check.TestSetSHA256 != m.TestSetSHA256 || check.Status != "passed" || check.ReportArtifactID <= 0 {
			return fmt.Errorf("module check does not accept this candidate")
		}
		delete(checks, check.ID)
	}
	if len(checks) != 0 {
		return fmt.Errorf("required module checks are missing")
	}
	return nil
}

func requiredIDs(kind string, ids []string) (map[string]bool, error) {
	if len(ids) == 0 {
		return nil, fmt.Errorf("required %s are missing from the expected policy", kind)
	}
	result := make(map[string]bool)
	for _, id := range ids {
		if strings.TrimSpace(id) == "" || result[id] {
			return nil, fmt.Errorf("expected %s require unique, nonempty IDs", kind)
		}
		result[id] = true
	}
	return result, nil
}
