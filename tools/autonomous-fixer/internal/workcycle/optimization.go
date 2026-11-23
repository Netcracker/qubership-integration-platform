package workcycle

import (
	"context"
	"fmt"
	"sort"
	"strings"
)

func authorHandoff(observations []Observation, stage Stage) []StageHandoff {
	if stage == ReviewStage {
		return nil
	}
	latest := map[Stage]StageHandoff{}
	for _, observation := range observations {
		if observation.Stage == ReviewStage {
			continue
		}
		r := observation.Declaration
		latest[observation.Stage] = StageHandoff{Stage: observation.Stage, CandidateID: observation.After.ID, EnvironmentID: r.EnvironmentID, DataID: r.DataID, Results: append(append([]Result(nil), r.Scenarios...), r.Checks...), Evidence: append([]string(nil), r.Evidence...), Details: r.Handoff}
	}
	var result []StageHandoff
	for _, s := range []Stage{ContractStage, ReproduceStage, ImplementStage, VerifyStage} {
		if h, ok := latest[s]; ok {
			result = append(result, h)
		}
	}
	return result
}

func collectStaticChecks(ctx context.Context, driver interface {
	Snapshot(context.Context) (Snapshot, error)
}, report *Report, candidate Snapshot) ([]Result, error) {
	checker, ok := driver.(StaticChecker)
	if !ok {
		return nil, nil
	}
	run, err := checker.StaticChecks(ctx, candidate)
	if err != nil {
		return nil, err
	}
	if len(run.Results) == 0 {
		return nil, nil
	}
	if run.CandidateID != candidate.ID {
		return nil, fmt.Errorf("static checks refer to another candidate")
	}
	var ids []string
	checks := report.Request.RequiredChecks
	if report.Contract != nil {
		checks = report.Contract.Checks
	}
	for _, check := range checks {
		ids = append(ids, check.ID)
	}
	seen := map[string]bool{}
	for _, r := range run.Results {
		allowed := false
		for _, id := range ids {
			allowed = allowed || id == r.ID
		}
		if !allowed || seen[r.ID] || !evidence(r.Evidence) || (r.Outcome != "pass" && r.Outcome != "fail") {
			return nil, fmt.Errorf("invalid controller check %q", r.ID)
		}
		seen[r.ID] = true
	}
	after, err := driver.Snapshot(ctx)
	if err != nil {
		return nil, err
	}
	if after.ID != candidate.ID {
		return nil, fmt.Errorf("static checks changed the source")
	}
	report.StaticCheckRuns = append(report.StaticCheckRuns, run)
	return run.Results, nil
}

func mergeStaticResults(native, controlled []Result) ([]Result, error) {
	merged := append([]Result(nil), native...)
	for _, check := range controlled {
		found := false
		for _, existing := range native {
			if existing.ID == check.ID {
				if existing.Outcome != check.Outcome {
					return nil, fmt.Errorf("conflicting result for controller check %s", check.ID)
				}
				found = true
			}
		}
		if !found {
			merged = append(merged, check)
		}
	}
	return merged, nil
}

func changedSince(before, after Snapshot) ([]string, bool) {
	if before.ID == after.ID {
		return nil, true
	}
	if (before.FileHashes == nil && len(before.ChangedPaths) > 0) || (after.FileHashes == nil && len(after.ChangedPaths) > 0) {
		return after.ChangedPaths, false
	}
	set := map[string]bool{}
	for p, h := range before.FileHashes {
		if after.FileHashes[p] != h {
			set[p] = true
		}
	}
	for p, h := range after.FileHashes {
		if before.FileHashes[p] != h {
			set[p] = true
		}
	}
	var paths []string
	for p := range set {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	return paths, len(paths) > 0
}

func canReuseReview(lens string, before, after Snapshot, hadFinding bool) bool {
	if lens == "correctness" || hadFinding {
		return false
	}
	paths, known := changedSince(before, after)
	if !known {
		return false
	}
	for _, path := range paths {
		p := strings.ToLower(path)
		test := strings.HasSuffix(p, "_test.go") || strings.HasSuffix(p, ".test.ts") || strings.HasSuffix(p, ".test.tsx") || strings.Contains(p, "/src/test/") || strings.Contains(p, "/tests/") || strings.HasPrefix(p, "e2e/specs/") || strings.HasPrefix(p, "e2e/fixtures/")
		doc := strings.HasPrefix(p, "help/docs/")
		if !test && !doc {
			return false
		}
	}
	return true
}

// Reproduction may add regression tests while product sources stay at baseline.
func stageChangeAllowed(stage Stage, before, after Snapshot) bool {
	if before.ID == after.ID || stage == ImplementStage {
		return true
	}
	if stage != ReproduceStage {
		return false
	}
	paths, known := changedSince(before, after)
	if !known || len(paths) == 0 {
		return false
	}
	for _, path := range paths {
		if !reproductionTestPath(path) {
			return false
		}
	}
	return true
}

func reproductionTestPath(p string) bool {
	if strings.Contains(p, "..") || strings.HasPrefix(p, "/") || strings.Contains(p, "\\") {
		return false
	}
	if strings.HasPrefix(p, "e2e/specs/") {
		return strings.HasSuffix(p, ".spec.ts")
	}
	if strings.HasPrefix(p, "ui/src/") || strings.HasPrefix(p, "ui/tests/") || strings.HasPrefix(p, "vscode-extension/src/") {
		return strings.HasSuffix(p, ".test.ts") || strings.HasSuffix(p, ".test.tsx") || strings.HasSuffix(p, ".spec.ts")
	}
	for _, module := range []string{"engine", "micro-engine", "runtime-catalog", "sessions-management", "integration-build-pipeline", "integration-build-maven-plugin"} {
		if strings.HasPrefix(p, module+"/") && strings.Contains(p, "/src/test/") {
			return true
		}
	}
	return strings.HasPrefix(p, "testing-service/") && strings.HasSuffix(p, "_test.go")
}
