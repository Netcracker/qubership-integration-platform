package main

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestWorkSummaryOutputs(t *testing.T) {
	noChange := "docs-no-change"
	assess := "docs-assess"
	write := "docs-write"
	jevCost := 0.00013482
	authorCost := 0.035465595
	attempts := 14
	attempted := true
	skipped := false
	const logURL = "https://github.com/example/repo/actions/runs/123/attempts/1"

	cases := []struct {
		name       string
		outcome    string
		report     workcycle.Report
		routing    doctriage.Routing
		classifier classifierDiagnostics
		provider   authorDiagnostics
		profile    experimentruntime.Profile
		want       []string
	}{
		{
			name:    "automatic no change",
			outcome: "success",
			report: workcycle.Report{Status: "docs-no-change",
				Request: workcycle.Request{DocumentationEntry: &workcycle.DocumentationEntry{Route: noChange}}},
			routing: doctriage.Routing{Route: noChange, Recommendation: doctriage.Recommendation{Reason: "outside_help_scope"}},
			classifier: classifierDiagnostics{Attempted: &attempted, RequestedModel: "typesafe/jev-1.14",
				Response: &jev.Response{Model: "typesafe/jev-1.14-20261014", Usage: &jev.Usage{Cost: &jevCost}}},
			want: []string{"Result** `docs-no-change`", "outside the scope of product help",
				"Jev** Served by `typesafe/jev-1.14-20261014`", "Author** Skipped.", "Total model cost** USD 0.00013482"},
		},
		{
			name:    "preflight followed by rejected author output",
			outcome: "failure",
			report: workcycle.Report{Status: "rejected", Error: "decode stage result: do-not-publish",
				Request:      workcycle.Request{DocumentationEntry: &workcycle.DocumentationEntry{Route: assess}},
				Observations: []workcycle.Observation{{Stage: doccycle.AuthorStage}}},
			classifier: classifierDiagnostics{Mode: "preflight", Attempted: &skipped},
			provider:   authorDiagnostics{Attempts: &attempts, Cost: &authorCost},
			profile:    experimentruntime.Profile{Model: "openrouter/openai/gpt-6-luna"},
			want: []string{"author returned an invalid result", "Issue evidence was incomplete", "Jev** Skipped.",
				"Attempted 14 OpenRouter requests.", "Served model unknown. Requested `openrouter/openai/gpt-6-luna`",
				"Total model cost** USD 0.035465595", logURL},
		},
		{
			name:    "documented change with partial usage",
			outcome: "success",
			report:  workcycle.Report{Status: "docs-ready-for-pr"},
			routing: doctriage.Routing{Route: write, Recommendation: doctriage.Recommendation{Reason: "stale_guidance"}},
			classifier: classifierDiagnostics{Attempted: &attempted,
				Response: &jev.Response{Model: "typesafe/jev-1.14-20261014", Usage: &jev.Usage{Cost: &jevCost}}},
			provider: authorDiagnostics{Attempts: &attempts, ReportedPortion: &authorCost,
				Generations: []openrouterusage.Generation{{ServedModel: "openai/luna-served"}, {ServedModel: "do-not-publish", Conflict: true}}},
			want: []string{"outdated product help", "Served by `openai/luna-served`. Cost unknown.",
				"Total model cost** Unknown (reported subtotal USD 0.035600415)"},
		},
		{
			name:    "failure before reports",
			outcome: "failure",
			want:    []string{"Result** `unknown` (Actions step `failure`)", "Jev** Execution unknown.", "Total model cost** Unknown", logURL},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := collectWorkDiagnostics(c.report, c.routing, c.classifier, c.provider, nil, c.outcome)
			d.RoutingReason = explainRoute(d.Route, c.routing, c.classifier)
			d.JevActivity = describeClassifier(c.classifier, d.JevCost, false)
			d.AuthorActivity = describeAuthor(d, c.provider, experimentruntime.Progress{}, c.profile, agentsignature.Identity{})
			got := formatWorkSummary(d, c.report, logURL)
			for _, wanted := range c.want {
				if !strings.Contains(got, wanted) {
					t.Errorf("summary missing %q:\n%s", wanted, got)
				}
			}
			if strings.Contains(got, "do-not-publish") {
				t.Errorf("summary exposed untrusted content:\n%s", got)
			}
		})
	}
}

func TestWorkSummaryWritesUnknownDiagnostics(t *testing.T) {
	out := t.TempDir()
	if err := writeWorkSummary("", filepath.Join(out, "missing"), out, "skipped", ""); err != nil {
		t.Fatal(err)
	}
	var summary workDiagnostics
	if err := read(filepath.Join(out, "summary.json"), &summary); err != nil {
		t.Fatal(err)
	}
	if summary.Status != nil || summary.Route != nil || summary.JevAttempted != nil || summary.AuthorAttempts != nil ||
		summary.RequiredChecks == nil || summary.Stages == nil || summary.MissingChecks == nil {
		t.Fatalf("unexpected diagnostics %+v", summary)
	}
	if summary.JevActivity.State != "skipped" || summary.AuthorActivity.State != "skipped" {
		t.Fatalf("unexpected activity %+v", summary)
	}
}
