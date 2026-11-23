package agentci

import (
	"net/http"
	"slices"
	"strings"
	"testing"
)

func TestRepairKeepsDistinctESLintFilesAndSites(t *testing.T) {
	check := failedAction(testHead, 42, 43, 44, 1)
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/repos/org/repo/actions/jobs/42":
			fixture(w, workflowJob{ID: 42, RunID: 43, SHA: testHead, Status: "completed", Conclusion: "failure"})
		case "/repos/org/repo/check-runs/42/annotations":
			fixture(w, []annotation{
				{Level: "failure", Path: "ui/src/Nav.ts", Message: "15:3  error  untrusted text  @typescript-eslint/no-unused-vars"},
				{Level: "failure", Message: strings.Join([]string{
					"/home/runner/work/repo/repo/ui/src/Nav.ts",
					"  15:3  error  untrusted text  @typescript-eslint/no-unused-vars",
					"  23:3  error  untrusted text  @typescript-eslint/no-unused-vars",
					"",
					"/home/runner/work/repo/repo/ui/src/Report.ts",
					"  15:3  error  untrusted text  @typescript-eslint/no-unused-vars",
				}, "\n")},
			})
		default:
			t.Errorf("unexpected request %s", r.URL)
			http.NotFound(w, r)
		}
	})
	plan, err := PlanRepair(t.Context(), client, Snapshot{HeadSHA: testHead, Checks: []Check{check}})
	if err != nil || !plan.Ready {
		t.Fatalf("repair plan = %+v, %v", plan, err)
	}
	var excerpts []string
	for _, failure := range plan.Failures {
		excerpts = append(excerpts, failure.Excerpt)
	}
	want := []string{
		"ESLint reported @typescript-eslint/no-unused-vars at ui/src/Nav.ts:15:3.",
		"ESLint reported @typescript-eslint/no-unused-vars at ui/src/Nav.ts:23:3.",
		"ESLint reported @typescript-eslint/no-unused-vars at ui/src/Report.ts:15:3.",
	}
	if !slices.Equal(excerpts, want) {
		t.Fatalf("diagnostics = %q, want %q", excerpts, want)
	}
}

func TestRepairIdentifiesGoSubtestsAndIgnoresMavenTotals(t *testing.T) {
	var dependency dependencyErrors
	got := readDiagnostics(nil, &dependency, `[ERROR] Tests run: 12, Failures: 2, Errors: 0
--- FAIL: TestReport/request_a (0.03s)
--- FAIL: TestReport/request_b (0.01s)`, "")
	want := []string{"Go reported a failure in TestReport/request_a.", "Go reported a failure in TestReport/request_b."}
	if !slices.Equal(got, want) {
		t.Fatalf("diagnostics = %q, want %q", got, want)
	}
}

func TestRepairIdentifiesJestCasesWithinOneSuite(t *testing.T) {
	var dependency dependencyErrors
	suite := "Jest reported a failure in src/Button.test.tsx."
	header := "2026-11-20T11:02:03.4Z \x1b[31mFAIL src/Button.test.tsx\x1b[0m (12.43s)"
	got := readDiagnostics([]string{suite}, &dependency, header+strings.Join([]string{
		"",
		"  ● Button › opens",
		"",
		"    Expected: \"text from the assertion\"",
		"",
		"  ● Button › closes",
		"",
		"  ● Button › opens",
		"",
		"FAIL src/Form.spec.ts",
		"  ● Test suite failed to run",
		"",
		"Test Suites: 2 failed, 2 total",
		"",
	}, "\n"), "")
	if len(got) != 3 || got[0] == got[1] || got[2] != "Jest reported a failure in src/Form.spec.ts." {
		t.Fatalf("diagnostics = %q", got)
	}
	for _, diagnostic := range got[:2] {
		if diagnostic == suite || !strings.Contains(diagnostic, "src/Button.test.tsx") ||
			strings.Contains(diagnostic, "text from the assertion") || len(diagnostic) > 200 {
			t.Fatalf("Jest case diagnostic = %q", diagnostic)
		}
	}

	rerun := readDiagnostics(nil, &dependency, "FAIL src/Button.test.tsx (3s)\n   ● \x1b[1mButton › opens\x1b[0m", "")
	if len(rerun) != 1 || rerun[0] != got[0] {
		t.Fatalf("Jest case identity changed on rerun: %q, want %q", rerun, got[0])
	}
	if repeated := readDiagnostics(got, &dependency, "FAIL src/Button.test.tsx", ""); !slices.Equal(repeated, got) {
		t.Fatalf("a suite header added another diagnostic: %q", repeated)
	}
}
