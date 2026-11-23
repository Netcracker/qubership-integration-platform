package main

import (
	"bytes"
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
)

func writeJSON(t *testing.T, path string, value any) {
	t.Helper()
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	writeBytes(t, path, b)
}
func writeBytes(t *testing.T, path string, b []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
}
func fixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	writeJSON(t, filepath.Join(dir, "config.json"), localwork.Config{TaskID: "repo#12:documentation", TaskKind: "documentation", ResumeFrom: "/earlier-run/report.json", Profile: experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "high"}})
	writeJSON(t, filepath.Join(dir, "report.json"), map[string]any{"status": "docs-no-change"})
	return dir
}
func process(t *testing.T, root, stage string, finished bool, exitCode int) string {
	t.Helper()
	dir := filepath.Join(root, stage)
	p := experimentruntime.Result{StartedAt: time.Date(2026, 11, 13, 1, 0, 0, 0, time.UTC), Duration: 3 * time.Second, ExitCode: exitCode}
	if finished {
		p.FinishedAt = p.StartedAt.Add(p.Duration)
	}
	writeJSON(t, filepath.Join(dir, "process.json"), p)
	return dir
}
func usage(t *testing.T, dir string) {
	t.Helper()
	writeBytes(t, filepath.Join(dir, "home/.codex/sessions/test.jsonl"), []byte(`{"type":"session_meta","payload":{"id":"session"}}
{"type":"turn_context","payload":{"model":"gpt-6.1-terra"}}
{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"cached_input_tokens":40,"cache_write_input_tokens":0,"output_tokens":10,"reasoning_output_tokens":3},"last_token_usage":{"input_tokens":100,"cached_input_tokens":40,"cache_write_input_tokens":0,"output_tokens":10,"reasoning_output_tokens":3}}}}
`))
}
func jevReport(t *testing.T, path, id string, cost any, failure string) {
	t.Helper()
	writeJSON(t, path, map[string]any{"policy_version": "docs-impact-v4", "packet_id": "issue-12", "error": failure, "response": map[string]any{"id": id, "model": "typesafe/jev-1.14", "usage": map[string]any{"input_tokens": 100, "output_tokens": 10, "cost": cost}}})
}

func TestFinishedFailuresAndJevCostsRemainInRunTotal(t *testing.T) {
	root := fixture(t)
	usage(t, process(t, root, "step-001-docs-assess", true, 1))
	usage(t, process(t, root, "step-002-docs-assess", true, 0))
	first, second := filepath.Join(t.TempDir(), "first.json"), filepath.Join(t.TempDir(), "second.json")
	jevReport(t, first, "jev-first", 0.002, "")
	jevReport(t, second, "jev-second", 0.001, "invalid prediction")
	m, err := collect(root, []string{first, second})
	if err != nil {
		t.Fatal(err)
	}
	if m.TaskID != "repo#12:documentation" || m.Outcome != "docs-no-change" || m.ResumeFrom != "/earlier-run/report.json" || m.SavingsStatus != "not_measured" {
		t.Fatalf("identity or scope lost: %+v", m)
	}
	if m.Counts.Completed != 2 || m.Counts.Failed != 1 || m.Counts.NativeInvocations != 2 || m.Counts.ObservedRequests != 2 || m.Counts.FailedJevReports != 1 || m.Elapsed != 6 {
		t.Fatalf("counts = %+v, elapsed = %v", m.Counts, m.Elapsed)
	}
	if m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-0.000224) > 1e-12 || m.JevReportedUSD == nil || *m.JevReportedUSD != .003 || m.CombinedUSD == nil || math.Abs(*m.CombinedUSD-.003224) > 1e-12 {
		t.Fatalf("costs = %+v", m)
	}
}

func TestUnfinishedAndMissingProcessesCannotBecomeZeroCost(t *testing.T) {
	for _, state := range []string{"unfinished", "missing", "no_stages"} {
		t.Run(state, func(t *testing.T) {
			root := fixture(t)
			switch state {
			case "unfinished":
				process(t, root, "step-001-docs-assess", false, 0)
			case "missing":
				writeBytes(t, filepath.Join(root, "step-001-docs-assess/prompt.txt"), []byte("task"))
			}
			m, err := collect(root, nil)
			if err != nil || m.APIEquivalentUSD != nil || m.CombinedUSD != nil || m.Counts.Completed != 0 {
				t.Fatalf("m=%+v, err=%v", m, err)
			}
			if state == "unfinished" && m.Counts.Unfinished != 1 || state == "missing" && m.Counts.MissingProcess != 1 {
				t.Fatal(m.Counts)
			}
		})
	}
	root := fixture(t)
	usage(t, process(t, root, "step-001-docs-assess", true, 0))
	process(t, root, "step-002-docs-write", false, 0)
	m, err := collect(root, nil)
	if err != nil || m.APIEquivalentUSD != nil || m.KnownCostUSD <= 0 || m.Counts.Unfinished != 1 {
		t.Fatalf("partial known cost was lost or declared complete: %+v, %v", m, err)
	}
}

func TestUsageErrorsPreserveKnownStages(t *testing.T) {
	for _, state := range []string{"missing", "malformed", "unknown"} {
		t.Run(state, func(t *testing.T) {
			root := fixture(t)
			usage(t, process(t, root, "step-001-docs-assess", true, 0))
			dir := process(t, root, "step-002-docs-write", true, 1)
			switch state {
			case "malformed":
				writeBytes(t, filepath.Join(dir, "stdout.jsonl"), []byte("not json\n"))
			case "unknown":
				writeBytes(t, filepath.Join(dir, "stdout.jsonl"), []byte("{\"type\":\"turn.completed\",\"usage\":{}}\n"))
			}
			m, err := collect(root, nil)
			if err != nil || m.APIEquivalentUSD != nil || m.KnownCostUSD <= 0 || m.Counts.UnknownUsage != 1 || m.Counts.Failed != 1 {
				t.Fatalf("m=%+v, err=%v", m, err)
			}
		})
	}
}

func TestJevUnknownCostsAreNotFree(t *testing.T) {
	for _, state := range []string{"failed", "missing_usage", "missing_cost", "null_cost", "negative_cost"} {
		t.Run(state, func(t *testing.T) {
			root := fixture(t)
			usage(t, process(t, root, "step-001-docs-assess", true, 0))
			path := filepath.Join(t.TempDir(), "jev.json")
			switch state {
			case "failed":
				writeJSON(t, path, map[string]any{"error": "unavailable"})
			case "missing_usage":
				writeJSON(t, path, map[string]any{"response": map[string]any{"id": "test"}})
			case "missing_cost":
				writeJSON(t, path, map[string]any{"response": map[string]any{"id": "test", "usage": map[string]any{"input_tokens": 100}}})
			case "null_cost":
				jevReport(t, path, "test", nil, "")
			case "negative_cost":
				jevReport(t, path, "test", -1, "")
			}
			m, err := collect(root, []string{path})
			if err != nil || m.APIEquivalentUSD == nil || m.JevReportedUSD != nil || m.CombinedUSD != nil || m.Counts.UnknownJevCost != 1 || m.CombinedKnownUSD <= 0 {
				t.Fatalf("m=%+v, err=%v", m, err)
			}
		})
	}
}

func TestDuplicateJevChargesCountOnceAndConflictsFail(t *testing.T) {
	root := fixture(t)
	usage(t, process(t, root, "step-001-docs-assess", true, 0))
	first, second := filepath.Join(t.TempDir(), "first.json"), filepath.Join(t.TempDir(), "copy.json")
	jevReport(t, first, "same-request", .001, "")
	jevReport(t, second, "same-request", .001, "")
	for _, paths := range [][]string{{first, first}, {first, second}} {
		m, err := collect(root, paths)
		if err != nil || m.Counts.JevReports != 1 || m.Counts.DuplicateJevReports != 1 || m.JevReportedUSD == nil || *m.JevReportedUSD != .001 || m.Jev[1]["counted"] != false {
			t.Fatalf("m=%+v err=%v", m, err)
		}
	}
	jevReport(t, second, "same-request", .002, "")
	if _, err := collect(root, []string{first, second}); err == nil {
		t.Fatal("conflicting duplicate costs were accepted")
	}
}

func TestExplicitOutputPreservesArchivedMetrics(t *testing.T) {
	root := fixture(t)
	usage(t, process(t, root, "step-001-docs-assess", true, 0))
	archived := filepath.Join(root, "metrics.json")
	writeBytes(t, archived, []byte("archive"))
	output := filepath.Join(t.TempDir(), "new.json")
	jevPath := filepath.Join(t.TempDir(), "jev.json")
	jevReport(t, jevPath, "test", .001, "")
	if err := run([]string{"--jev-report", jevPath, "--output", output, root}); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(archived)
	if err != nil || string(b) != "archive" {
		t.Fatal("archived metrics changed")
	}
	if err := run([]string{"--output", output, root}); err == nil {
		t.Fatal("explicit output overwrote an existing file")
	}
	var m metrics
	if err := readJSON(output, &m); err != nil || m.Counts.JevReports != 1 {
		t.Fatalf("m=%+v, err=%v", m, err)
	}
}

func TestMixedStageProfilesUseTheirObservedModels(t *testing.T) {
	root := fixture(t)
	writeJSON(t, filepath.Join(root, "config.json"), localwork.Config{TaskID: "t", Profile: experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "xhigh"}, ReviewProfile: &experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "high"}})
	author := process(t, root, "step-001-implement", true, 0)
	review := process(t, root, "step-002-review", true, 0)
	usage(t, author)
	usage(t, review)
	path := filepath.Join(author, "home/.codex/sessions/test.jsonl")
	b, _ := os.ReadFile(path)
	b = bytes.ReplaceAll(b, []byte("gpt-6.1-terra"), []byte("gpt-6-luna"))
	writeBytes(t, path, b)
	writeJSON(t, filepath.Join(author, "profile.json"), experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "xhigh"})
	writeJSON(t, filepath.Join(review, "profile.json"), experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "high"})
	m, e := collect(root, nil)
	if e != nil || m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-.0001234) > 1e-12 || m.ReviewProfile == nil {
		t.Fatalf("%+v %v", m, e)
	}
}

func TestDeclarationCorrectionCountsUsageWithoutRepeatingWorkStage(t *testing.T) {
	root := fixture(t)
	usage(t, process(t, root, "step-001-reproduce", true, 0))
	usage(t, process(t, root, "step-001-reproduce/declaration-correction", true, 0))
	m, err := collect(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	if m.Counts.StageAttempts != 1 || m.Counts.NativeInvocations != 2 || m.Counts.ObservedRequests != 2 || m.Elapsed != 6 {
		t.Fatalf("correction cost or original stage identity lost: %+v elapsed %v", m.Counts, m.Elapsed)
	}
	if m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-.000224) > 1e-12 {
		t.Fatal(m.APIEquivalentUSD)
	}
}

func TestAuthorHelperCountsUsageWithoutRepeatingWorkStage(t *testing.T) {
	root := fixture(t)
	helperProfile := experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "medium"}
	writeJSON(t, filepath.Join(root, "config.json"), localwork.Config{Workflow: "agent", TaskID: "t", Profile: experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "high"}, AuthorHelperProfile: &helperProfile})
	author := process(t, root, "step-0001-author", true, 0)
	helper := process(t, root, "step-0001-author/helper-0001", true, 0)
	usage(t, author)
	usage(t, helper)
	writeJSON(t, filepath.Join(helper, "profile.json"), helperProfile)
	m, err := collect(root, nil)
	if err != nil || m.AuthorHelperProfile == nil || *m.AuthorHelperProfile != helperProfile {
		t.Fatalf("helper profile missing: %+v %v", m, err)
	}
	if m.Counts.StageAttempts != 1 || m.Counts.AuthorHelpers != 1 || m.Counts.NativeInvocations != 2 || m.Counts.ObservedRequests != 2 || m.Stages[1]["parent_stage"] != "step-0001-author" {
		t.Fatalf("helper accounting: %+v %+v", m.Counts, m.Stages)
	}
	if m.APIEquivalentUSD == nil || math.Abs(*m.APIEquivalentUSD-.000224) > 1e-12 {
		t.Fatal(m.APIEquivalentUSD)
	}
	helperProfile.Effort = "high"
	writeJSON(t, filepath.Join(helper, "profile.json"), helperProfile)
	m, err = collect(root, nil)
	if err != nil || m.APIEquivalentUSD != nil || m.Counts.UnknownUsage != 1 || m.KnownCostUSD <= 0 {
		t.Fatalf("mismatched helper profile hid known costs or counted as complete: %+v %v", m, err)
	}
}

func TestUnfinishedDeclarationCorrectionPreservesKnownUsage(t *testing.T) {
	root := fixture(t)
	usage(t, process(t, root, "step-001-reproduce", true, 0))
	process(t, root, "step-001-reproduce/declaration-correction", false, 0)
	m, err := collect(root, nil)
	if err != nil || m.Counts.StageAttempts != 1 || m.Counts.Unfinished != 1 || m.APIEquivalentUSD != nil || m.KnownCostUSD <= 0 {
		t.Fatalf("unfinished correction was excluded: %+v %v", m, err)
	}
}

func TestInterruptedLogsKeepKnownCostsAndSetupOnlyIsNotAnInvocation(t *testing.T) {
	root := fixture(t)
	dir := process(t, root, "step-0001-author", false, 0)
	usage(t, dir)
	writeJSON(t, filepath.Join(dir, "helper-0001", "helper-setup-failure.json"), map[string]any{"stage": "author-helper", "native_process_started": false, "error": "snapshot unavailable"})
	for _, missing := range []bool{false, true} {
		if missing {
			if err := os.Remove(filepath.Join(dir, "process.json")); err != nil {
				t.Fatal(err)
			}
		}
		m, err := collect(root, nil)
		if err != nil || m.Counts.HelperSetupFailures != 1 || m.Counts.ObservedRequests != 1 || m.KnownCostUSD <= 0 || m.APIEquivalentUSD != nil {
			t.Fatalf("lost partial accounting: %+v, %v", m, err)
		}
	}
}
