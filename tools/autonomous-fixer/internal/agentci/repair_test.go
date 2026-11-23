package agentci

import (
	"net/http"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
)

func failedAction(sha string, job, run, workflow int64, attempt int) Check {
	return Check{ID: job, AppID: 15368, Name: "Build", Kind: "check-run", SHA: sha, Workflow: "CI", Status: "failed", Conclusion: "failure",
		action: actionRun{SHA: sha, ID: run, WorkflowID: workflow, Event: "pull_request", Attempt: attempt, Status: "completed", Conclusion: "failure"}}
}

func TestRepairWaitsForRequiredChecksAndIncludesCompletedOptionalFailures(t *testing.T) {
	var requests atomic.Int32
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		switch r.URL.Path {
		case "/repos/org/repo/actions/jobs/42":
			fixture(w, workflowJob{ID: 42, RunID: 43, SHA: testHead, Status: "completed", Conclusion: "failure"})
		case "/repos/org/repo/check-runs/42/annotations":
			fixture(w, []annotation{{Level: "failure", Message: "ui/src/App.tsx(32,4): error TS2322: password is secret-from-log"}})
		default:
			t.Errorf("unexpected request %s", r.URL)
			http.NotFound(w, r)
		}
	})
	snapshot := Snapshot{HeadSHA: testHead, Checks: []Check{failedAction(testHead, 42, 43, 44, 1),
		{SHA: testHead, Name: "Required build", Required: true, Status: "pending"}, {SHA: testHead, Name: "Optional build", Status: "pending"}}}
	for _, status := range []string{"pending", "missing", "unknown"} {
		snapshot.Checks[1].Status = status
		plan, err := PlanRepair(t.Context(), client, snapshot)
		if err != nil || plan.Ready || requests.Load() != 0 {
			t.Fatalf("plan with a required check marked %s: %+v, %v (%d requests)", status, plan, err, requests.Load())
		}
	}
	snapshot.Checks[1].Status = "passed"
	plan, err := PlanRepair(t.Context(), client, snapshot)
	if err != nil || !plan.Ready || len(plan.Failures) != 1 || len(plan.Human)+len(plan.RetryRuns) != 0 {
		t.Fatalf("plan after required completion: %+v, %v", plan, err)
	}
	failure := plan.Failures[0]
	if failure.Excerpt != "TypeScript reported TS2322 at ui/src/App.tsx:32:4." || failure.URL != "https://github.com/org/repo/actions/runs/43/job/42" {
		t.Fatalf("failure evidence: %+v", failure)
	}
	if key := failureKey(failureJob(failedAction(testMerge, 52, 53, 44, 2)), failure.Excerpt); key != failure.Key {
		t.Error("a new commit and run changed the failure key")
	}
	if key := failureKey(failureJob(failedAction(testMerge, 52, 53, 45, 2)), failure.Excerpt); key == failure.Key {
		t.Error("different workflows share a failure key")
	}
	if key := failureKey(failureJob(failedAction(testMerge, 52, 53, 44, 2)), "TypeScript reported TS2323 at ui/src/App.tsx:32:4."); key == failure.Key {
		t.Error("different errors in the same workflow share a failure key")
	}
}

func TestRepairWaitsForSelectedChecksWithoutRequirements(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("unexpected request %s", r.URL)
		http.NotFound(w, r)
	})
	mergeBuild := failedAction(testMerge, 52, 53, 44, 1)
	mergeBuild.Status, mergeBuild.Conclusion = "passed", "success"
	snapshot := Snapshot{HeadSHA: testHead, TestMergeSHA: testMerge, Checks: []Check{
		failedAction(testHead, 42, 43, 44, 1), mergeBuild,
		{SHA: testHead, Name: "Tests", Status: "pending"},
	}}
	plan, err := PlanRepair(t.Context(), client, snapshot)
	if err != nil || plan.Ready {
		t.Fatalf("plan before completion: %+v, %v", plan, err)
	}
	snapshot.Checks[2].Status = "skipped"
	plan, err = PlanRepair(t.Context(), client, snapshot)
	if err != nil || !plan.Ready || len(plan.Failures)+len(plan.RetryRuns)+len(plan.Human) != 0 {
		t.Fatalf("plan after the effective commit passed: %+v, %v", plan, err)
	}
	snapshot.Diagnostics = []string{"Required checks could not be read."}
	plan, err = PlanRepair(t.Context(), client, snapshot)
	if err != nil || plan.Ready {
		t.Fatalf("plan without a confirmed inventory: %+v, %v", plan, err)
	}
}

func TestRepairIncludesHeadFailuresAlongsideMergeChecks(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/repos/org/repo/actions/jobs/42":
			fixture(w, workflowJob{ID: 42, RunID: 43, SHA: testHead, Status: "completed", Conclusion: "failure"})
		case "/repos/org/repo/check-runs/42/annotations":
			fixture(w, []annotation{{Level: "failure", Message: "ui/src/App.tsx(32,4): error TS2322: incompatible type"}})
		default:
			t.Errorf("unexpected request %s", r.URL)
			http.NotFound(w, r)
		}
	})
	snapshot := Snapshot{HeadSHA: testHead, TestMergeSHA: testMerge, Checks: []Check{
		failedAction(testHead, 42, 43, 44, 1),
		{Name: "Tests", SHA: testMerge, Required: true, Status: "passed"},
		{Name: "Deploy", SHA: testHead, Required: true, Status: "pending"},
	}}
	plan, err := PlanRepair(t.Context(), client, snapshot)
	if err != nil || plan.Ready {
		t.Fatalf("plan while a required check runs: %+v, %v", plan, err)
	}
	snapshot.Checks[2].Status = "skipped"
	plan, err = PlanRepair(t.Context(), client, snapshot)
	if err != nil || !plan.Ready || len(plan.Failures) != 1 || len(plan.Human)+len(plan.RetryRuns) != 0 {
		t.Fatalf("plan after both commits settled: %+v, %v", plan, err)
	}
}

func TestRepairDistinguishesEvidenceAndRerunsOnlyAnInitialInfraFailure(t *testing.T) {
	const runnerOutage = "The hosted runner encountered an error while running your job."
	const mavenOutage = "[ERROR] Could not transfer artifact org:lib:jar:1 from/to central (https://repo.maven.apache.org/maven2): status code: 503"
	const npmOutage = "npm error 503 Service Unavailable - GET https://registry.npmjs.org/foo"
	const npmDenied = "npm error 403 Forbidden - GET https://registry.npmjs.org/foo"
	const assertion = "--- FAIL: TestSessionLookup (0.02s)"
	for _, test := range []struct {
		name       string
		annotation string
		attempt    int
		runnerOnly bool
		retry      bool
		repair     bool
	}{
		{name: "runner setup", annotation: runnerOutage, attempt: 1, runnerOnly: true, retry: true},
		{name: "repeated runner setup failure", annotation: runnerOutage, attempt: 2, runnerOnly: true},
		{name: "runner signal after repository steps", annotation: runnerOutage, attempt: 1},
		{name: "maven outage", annotation: mavenOutage, attempt: 1, retry: true},
		{name: "npm outage", annotation: npmOutage, attempt: 1, retry: true},
		{name: "npm permission failure", annotation: npmDenied, attempt: 1},
		{name: "npm conflicting responses", annotation: npmDenied + "\n" + npmOutage, attempt: 1},
		{name: "generic command failure", annotation: "Process completed with exit code 1.", attempt: 1},
		{name: "assertion with outage line", annotation: assertion + "\n" + npmOutage, attempt: 1, repair: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			check := failedAction(testHead, 42, 43, 44, test.attempt)
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/repos/org/repo/actions/jobs/42":
					job := map[string]any{"id": 42, "run_id": 43, "head_sha": testHead, "status": "completed", "conclusion": "failure"}
					if !test.runnerOnly {
						job["steps"] = []map[string]any{{"name": "Checkout repository", "number": 2, "status": "completed", "conclusion": "success"}}
					}
					fixture(w, job)
				case "/repos/org/repo/check-runs/42/annotations":
					fixture(w, []annotation{{Level: "failure", Path: ".github", Message: test.annotation}})
				default:
					t.Errorf("unexpected request %s", r.URL)
					http.NotFound(w, r)
				}
			})
			plan, err := PlanRepair(t.Context(), client, Snapshot{HeadSHA: testHead, Checks: []Check{check}})
			if err != nil || !plan.Ready || slices.Contains(plan.RetryRuns, 43) != test.retry || (len(plan.Failures) != 0) != test.repair ||
				(len(plan.Human) != 0) != (!test.retry && !test.repair) {
				t.Fatalf("plan: %+v, %v", plan, err)
			}
		})
	}
}

func TestRepairExtractsBoundedDiagnosticSummaries(t *testing.T) {
	var dependency dependencyErrors
	lines := []string{
		"2026-11-20T11:02:03.4Z \x1b[31mFAIL path/Button.test.tsx\x1b[0m (12.43s)",
		"[ERROR] foo/Foo.java:[22,9] unexpected token private log message",
		"[ERROR] foo/Foo.java:[22,9] duplicate line",
		"/home/runner/work/repo/repo/ui/src/Report.ts",
		"42:6  error  private log message  @typescript-eslint/no-unused-vars",
		"--- FAIL: TestReport (0s)",
	}
	got := readDiagnostics(nil, &dependency, strings.Join(lines, "\n"), "")
	want := []string{"Jest reported a failure in path/Button.test.tsx.", "The Java compiler reported a source error at foo/Foo.java:22:9.",
		"ESLint reported @typescript-eslint/no-unused-vars at ui/src/Report.ts:42:6."}
	if !slices.Equal(got, want) {
		t.Fatalf("diagnostics: %q, want %q", got, want)
	}
}

func TestRerunFailedJobsUsesTheRepositoryRunAndAcceptsCreatedWithoutBody(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/repos/org/repo/actions/runs/43/rerun-failed-jobs" {
			t.Errorf("unexpected request %s %s", r.Method, r.URL)
		}
		w.WriteHeader(http.StatusCreated)
	})
	if err := client.RerunFailedActionsJobs(t.Context(), 43); err != nil {
		t.Fatal(err)
	}
}
