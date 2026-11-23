package main

import (
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

func TestHostedOpenRouterCostRemainsSeparateFromEstimates(t *testing.T) {
	root := t.TempDir()
	writeJSON(t, filepath.Join(root, "config.json"), localwork.Config{Workflow: "fix-issue", Hosted: true})
	before, err := collect(root, nil)
	if err != nil || before.OpenRouter != nil {
		t.Fatal(before, err)
	}
	cost := 0.004
	writeJSON(t, filepath.Join(root, openrouterusage.Filename), openrouterusage.Report{Attempts: 1, KnownCostUSD: cost, ReportedCostUSD: &cost})
	after, err := collect(root, nil)
	if err != nil || after.OpenRouter == nil || after.OpenRouter.ReportedCostUSD == nil || *after.OpenRouter.ReportedCostUSD != cost ||
		after.APIEquivalentUSD != nil || after.CombinedUSD != nil || after.CombinedKnownUSD != 0 {
		t.Fatal(after, err)
	}
}
