package fixer

import (
	"strings"
	"testing"
)

func manifestFixture() (*Manifest, ManifestExpected) {
	base, candidate, trusted := strings.Repeat("a", 40), strings.Repeat("b", 40), strings.Repeat("c", 40)
	run := ManifestRun{RunID: 12, RunAttempt: 1, Workflow: "agent-work", TrustedSHA: trusted}
	m := &Manifest{SchemaVersion: 1, TaskID: "task-1", AttemptID: "attempt-1", ExecutionID: "execution-1", Generation: 1, ContractVersion: 2, Repository: "owner/repo", BaseSHA: base, CandidateSHA: candidate, ParentSHA: base, Run: run,
		Files: []ManifestFile{{Path: "ui/src/save.ts", SHA256: strings.Repeat("d", 64)}}, Bundle: Bundle{ArtifactID: 31, Format: "git-bundle", SHA256: strings.Repeat("e", 64)}, TestSetSHA256: strings.Repeat("f", 64),
		Reviews: []Review{{WorkItemID: "review-1", CandidateSHA: candidate, Verdict: "passed"}}, Verification: Verification{CandidateSHA: candidate, ContractVersion: 2, Status: "passed", EnvironmentID: "env-1", BaselineEnvironmentID: "env-1", DataAID: "data-a", DataBID: "data-b", FullScenarioRepeated: true, ReportArtifactID: 32}}
	expected := ManifestExpected{TaskID: m.TaskID, AttemptID: m.AttemptID, ExecutionID: m.ExecutionID, Generation: 1, ContractVersion: 2, Repository: m.Repository, BaseSHA: base, CandidateSHA: candidate, ParentSHA: base, Run: run, ScopePaths: []string{"ui"}}
	m.ContextSHA256 = strings.Repeat("1", 64)
	m.ProcessReportSHA256 = strings.Repeat("2", 64)
	m.Reviews[0].Lens = "correctness"
	m.Reviews[0].ContextSHA256 = m.ContextSHA256
	m.Reviews[0].TestSetSHA256 = m.TestSetSHA256
	m.Reviews[0].ReportArtifactID = 33
	m.Verification.Scenarios = []ScenarioVerification{{ID: "save-browser", CriterionID: "save", Consumer: "browser", CandidateSHA: candidate, TestSetSHA256: m.TestSetSHA256, Status: "passed", EnvironmentID: "env-1", BaselineEnvironmentID: "env-1", DataAID: "chain-a", DataBID: "chain-b", ReportArtifactID: 34}}
	m.Verification.Checks = []ModuleCheck{{ID: "ui-check-types", CandidateSHA: candidate, TestSetSHA256: m.TestSetSHA256, Status: "passed", ReportArtifactID: 35}}
	expected.TestSetSHA256 = m.TestSetSHA256
	expected.ContextSHA256 = m.ContextSHA256
	expected.ProcessReportSHA256 = m.ProcessReportSHA256
	expected.EnvironmentID = "env-1"
	expected.RequiredReviewLenses = []string{"correctness"}
	expected.RequiredScenarios = []ScenarioExpected{{ID: "save-browser", CriterionID: "save", Consumer: "browser"}}
	expected.RequiredChecks = []string{"ui-check-types"}
	return m, expected
}

func TestManifestRequiresCurrentCompleteEvidence(t *testing.T) {
	cases := map[string]func(*Manifest, *ManifestExpected){
		"missing expected reviews":        func(m *Manifest, e *ManifestExpected) { e.RequiredReviewLenses = nil },
		"missing expected scenarios":      func(m *Manifest, e *ManifestExpected) { e.RequiredScenarios = nil },
		"missing expected checks":         func(m *Manifest, e *ManifestExpected) { e.RequiredChecks = nil },
		"missing expected context":        func(m *Manifest, e *ManifestExpected) { e.ContextSHA256 = "" },
		"missing expected process report": func(m *Manifest, e *ManifestExpected) { e.ProcessReportSHA256 = "" },
		"missing expected test set":       func(m *Manifest, e *ManifestExpected) { e.TestSetSHA256 = "" },
		"missing expected environment":    func(m *Manifest, e *ManifestExpected) { e.EnvironmentID = "" },
		"duplicate expected lens": func(m *Manifest, e *ManifestExpected) {
			e.RequiredReviewLenses = []string{"correctness", "correctness"}
		},
		"empty expected lens": func(m *Manifest, e *ManifestExpected) { e.RequiredReviewLenses = []string{" "} },
		"duplicate expected scenario": func(m *Manifest, e *ManifestExpected) {
			e.RequiredScenarios = append(e.RequiredScenarios, e.RequiredScenarios[0])
		},
		"missing expected consumer":  func(m *Manifest, e *ManifestExpected) { e.RequiredScenarios[0].Consumer = "" },
		"missing expected criterion": func(m *Manifest, e *ManifestExpected) { e.RequiredScenarios[0].CriterionID = "" },
		"duplicate expected check": func(m *Manifest, e *ManifestExpected) {
			e.RequiredChecks = append(e.RequiredChecks, e.RequiredChecks[0])
		},
		"missing lens": func(m *Manifest, e *ManifestExpected) {
			e.RequiredReviewLenses = append(e.RequiredReviewLenses, "regression")
		},
		"duplicate lens": func(m *Manifest, e *ManifestExpected) {
			r := m.Reviews[0]
			r.WorkItemID = "review-2"
			m.Reviews = append(m.Reviews, r)
		},
		"duplicate review identity": func(m *Manifest, e *ManifestExpected) {
			r := m.Reviews[0]
			r.Lens = "regression"
			m.Reviews = append(m.Reviews, r)
			e.RequiredReviewLenses = append(e.RequiredReviewLenses, r.Lens)
		},
		"unknown lens":              func(m *Manifest, e *ManifestExpected) { m.Reviews[0].Lens = "other" },
		"review context mismatch":   func(m *Manifest, e *ManifestExpected) { m.Reviews[0].ContextSHA256 = strings.Repeat("3", 64) },
		"review test set mismatch":  func(m *Manifest, e *ManifestExpected) { m.Reviews[0].TestSetSHA256 = strings.Repeat("3", 64) },
		"missing review artifact":   func(m *Manifest, e *ManifestExpected) { m.Reviews[0].ReportArtifactID = 0 },
		"context mismatch":          func(m *Manifest, e *ManifestExpected) { m.ContextSHA256 = strings.Repeat("3", 64) },
		"process report mismatch":   func(m *Manifest, e *ManifestExpected) { m.ProcessReportSHA256 = strings.Repeat("3", 64) },
		"boolean without scenarios": func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios = nil },
		"missing consumer scenario": func(m *Manifest, e *ManifestExpected) {
			e.RequiredScenarios = append(e.RequiredScenarios, ScenarioExpected{ID: "save-vscode", CriterionID: "save", Consumer: "vscode"})
		},
		"wrong scenario consumer":  func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].Consumer = "vscode" },
		"wrong scenario criterion": func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].CriterionID = "load" },
		"duplicate scenario": func(m *Manifest, e *ManifestExpected) {
			m.Verification.Scenarios = append(m.Verification.Scenarios, m.Verification.Scenarios[0])
		},
		"unknown scenario result": func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].Status = "unknown" },
		"reused scenario data B": func(m *Manifest, e *ManifestExpected) {
			m.Verification.Scenarios[0].DataBID = m.Verification.Scenarios[0].DataAID
		},
		"scenario environment mismatch": func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].EnvironmentID = "env-2" },
		"scenario baseline mismatch":    func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].BaselineEnvironmentID = "env-2" },
		"stale scenario candidate":      func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].CandidateSHA = m.BaseSHA },
		"stale scenario test set": func(m *Manifest, e *ManifestExpected) {
			m.Verification.Scenarios[0].TestSetSHA256 = strings.Repeat("3", 64)
		},
		"missing scenario artifact": func(m *Manifest, e *ManifestExpected) { m.Verification.Scenarios[0].ReportArtifactID = 0 },
		"missing checks":            func(m *Manifest, e *ManifestExpected) { m.Verification.Checks = nil },
		"duplicate check": func(m *Manifest, e *ManifestExpected) {
			m.Verification.Checks = append(m.Verification.Checks, m.Verification.Checks[0])
		},
		"stale check candidate": func(m *Manifest, e *ManifestExpected) { m.Verification.Checks[0].CandidateSHA = m.BaseSHA },
		"stale check test set": func(m *Manifest, e *ManifestExpected) {
			m.Verification.Checks[0].TestSetSHA256 = strings.Repeat("3", 64)
		},
		"unknown check result":   func(m *Manifest, e *ManifestExpected) { m.Verification.Checks[0].Status = "unknown" },
		"missing check artifact": func(m *Manifest, e *ManifestExpected) { m.Verification.Checks[0].ReportArtifactID = 0 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			m, expected := manifestFixture()
			change(m, &expected)
			if ValidateManifest(m, expected) == nil {
				t.Fatal("accepted incomplete or stale evidence")
			}
		})
	}
}

func TestManifestAcceptsSeparateConsumerScenarios(t *testing.T) {
	m, expected := manifestFixture()
	scenario := m.Verification.Scenarios[0]
	scenario.ID, scenario.Consumer = "save-vscode", "vscode"
	scenario.DataAID, scenario.DataBID = "file-a", "file-b"
	scenario.ReportArtifactID = 36
	m.Verification.Scenarios = append(m.Verification.Scenarios, scenario)
	expected.RequiredScenarios = append(expected.RequiredScenarios, ScenarioExpected{ID: scenario.ID, CriterionID: scenario.CriterionID, Consumer: scenario.Consumer})
	if err := ValidateManifest(m, expected); err != nil {
		t.Fatal(err)
	}
}
func TestManifestDeclarations(t *testing.T) {
	m, expected := manifestFixture()
	if err := ValidateManifest(m, expected); err != nil {
		t.Fatal(err)
	}
	cases := map[string]func(*Manifest){
		"foreign task":          func(m *Manifest) { m.TaskID = "other" },
		"old criteria":          func(m *Manifest) { m.ContractVersion = 1 },
		"foreign run":           func(m *Manifest) { m.Run.RunID++ },
		"rerun":                 func(m *Manifest) { m.Run.RunAttempt++ },
		"different parent":      func(m *Manifest) { m.ParentSHA = strings.Repeat("d", 40) },
		"protected path":        func(m *Manifest) { m.Files[0].Path = "tools/autonomous-fixer/main.go" },
		"path traversal":        func(m *Manifest) { m.Files[0].Path = "ui/../engine/file" },
		"duplicate path":        func(m *Manifest) { m.Files = append(m.Files, m.Files[0]) },
		"missing review":        func(m *Manifest) { m.Reviews = nil },
		"old review":            func(m *Manifest) { m.Reviews[0].CandidateSHA = m.BaseSHA },
		"failed review":         func(m *Manifest) { m.Reviews[0].Verdict = "failed" },
		"new environment":       func(m *Manifest) { m.Verification.EnvironmentID = "env-2" },
		"reused data":           func(m *Manifest) { m.Verification.DataBID = m.Verification.DataAID },
		"scenario not repeated": func(m *Manifest) { m.Verification.FullScenarioRepeated = false },
		"unverified candidate":  func(m *Manifest) { m.Verification.CandidateSHA = m.BaseSHA },
		"missing artifact":      func(m *Manifest) { m.Bundle.ArtifactID = 0 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			m, expected := manifestFixture()
			change(m)
			if ValidateManifest(m, expected) == nil {
				t.Fatal("invalid manifest accepted")
			}
		})
	}
}
func TestDecodeJSONRejectsUnknownFieldsAndTrailingValues(t *testing.T) {
	for _, data := range []string{`{"schemaVersion":1,"unexpected":true}`, `{"schemaVersion":1} {}`} {
		var manifest Manifest
		if DecodeJSON([]byte(data), &manifest) == nil {
			t.Fatal("invalid payload accepted")
		}
	}
}
