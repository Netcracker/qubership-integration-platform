package checkrunner

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestProductCommandFixture(t *testing.T) {
	mode := os.Getenv("PRODUCT_CHECK_FIXTURE")
	if mode == "" {
		return
	}
	if mode == "missing" {
		os.Exit(0)
	}
	path := os.Getenv("PRODUCT_CHECK_REPORT")
	if mode == "empty" {
		os.WriteFile(path, nil, 0600)
		os.Exit(0)
	}
	now := time.Now().UTC()
	if mode == "stale" {
		now = now.Add(-time.Hour)
	}
	if mode == "recent-stale" {
		now = now.Add(-500 * time.Millisecond)
	}
	status, aggregate := "passed", "expected"
	failed, passed, code := 0, 1, 0
	errors := []map[string]string{}
	if mode == "assertion" || mode == "masked" || mode == "nonassertion" || mode == "timeout" {
		status, aggregate = "failed", "unexpected"
		failed, passed, code = 1, 0, 1
		errors = append(errors, map[string]string{"message": "Error: expect(received).toEqual(expected)"})
	}
	if mode == "masked" {
		code = 0
	}
	if mode == "nonassertion" {
		errors = []map[string]string{{"message": "Error: connect ECONNREFUSED during setup"}}
	}
	if mode == "timeout" {
		status = "timedOut"
	}
	result := map[string]any{"status": status, "errors": errors}
	tests := []any{map[string]any{"title": "defect", "file": "spec.ts", "tests": []any{map[string]any{"expectedStatus": "passed", "projectName": "ui", "status": aggregate, "results": []any{result}}}}}
	report := map[string]any{"suites": []any{map[string]any{"title": "suite", "specs": tests}}, "errors": []any{}, "stats": map[string]any{"startTime": now.Format(time.RFC3339Nano), "expected": passed, "unexpected": failed, "skipped": 0, "flaky": 0}}
	if mode == "no-tests" {
		report["suites"] = []any{}
		report["stats"].(map[string]any)["expected"] = 0
	}
	if mode == "setup" {
		report["errors"] = []any{map[string]any{"message": "global setup unavailable"}}
		code = 1
	}
	if mode == "count-mismatch" {
		report["stats"].(map[string]any)["expected"] = 2
	}
	data, _ := json.Marshal(report)
	os.WriteFile(path, data, 0600)
	os.Stdout.WriteString("fixture test stdout\n")
	os.Stderr.WriteString("fixture test stderr\n")
	os.Exit(code)
}
func productOptions(t *testing.T) ProductOptions {
	t.Helper()
	return ProductOptions{CWD: t.TempDir(), OutputDirectory: filepath.Join(t.TempDir(), "run"), Command: []string{os.Args[0], "-test.run=TestProductCommandFixture"}, ReportFormat: "playwright", Expect: "pass", MinTests: 1, RequiredTests: []string{"defect"}}
}
func productEnvironment(mode string) []string {
	return append(os.Environ(), "PRODUCT_CHECK_FIXTURE="+mode)
}
func TestProductRunCapturesRealStatusAndNeverReuses(t *testing.T) {
	opts := productOptions(t)
	first, e := RunProduct(context.Background(), opts, productEnvironment("pass"))
	if e != nil || !first.ExpectationMet || first.Verdict != "tests-passed" || first.Process.ExitCode != 0 || first.ReportSHA256 == "" {
		t.Fatal(first, e)
	}
	for _, path := range []string{first.Path, first.ReportCopy, first.Process.StdoutPath, first.Process.StderrPath} {
		b, e := os.ReadFile(path)
		if e != nil || len(b) == 0 {
			t.Fatal(path, e)
		}
	}
	if _, e = RunProduct(context.Background(), opts, productEnvironment("pass")); e == nil {
		t.Fatal("reused output accepted")
	}
	opts.OutputDirectory = filepath.Join(filepath.Dir(opts.OutputDirectory), "second")
	second, e := RunProduct(context.Background(), opts, productEnvironment("pass"))
	if e != nil || second.Process.StartedAt == first.Process.StartedAt {
		t.Fatal("command was not rerun", second, e)
	}
}
func TestProductRejectsMaskedExitEvenWithExpectedFailure(t *testing.T) {
	opts := productOptions(t)
	opts.Expect = "tests-fail"
	opts.RequiredTests = nil
	opts.ExpectedFailedTests = []string{"defect"}
	script := "'" + strings.ReplaceAll(os.Args[0], "'", "'\\''") + "' -test.run=TestProductCommandFixture | tee /dev/null"
	opts.Command = []string{"bash", "-c", script}
	record, e := RunProduct(context.Background(), opts, productEnvironment("assertion"))
	if e == nil || record.Process.ExitCode != 0 || record.ExpectationMet || !strings.Contains(e.Error(), "conflicts with report") {
		t.Fatal(record, e)
	}
	opts.OutputDirectory = filepath.Join(filepath.Dir(opts.OutputDirectory), "pipefail")
	opts.Command = BashProductCommand(script)
	record, e = RunProduct(context.Background(), opts, productEnvironment("assertion"))
	if e != nil || record.Process.ExitCode != 1 || !record.ExpectationMet || record.Verdict != "expected-test-failure" {
		t.Fatal(record, e)
	}
}
func TestProductRejectsMissingInvalidStaleOrUnexecutedReports(t *testing.T) {
	for _, mode := range []string{"missing", "empty", "no-tests", "stale", "recent-stale", "count-mismatch", "setup"} {
		t.Run(mode, func(t *testing.T) {
			record, e := RunProduct(context.Background(), productOptions(t), productEnvironment(mode))
			if e == nil || record.ExpectationMet || record.Verdict == "tests-passed" {
				t.Fatal(record, e)
			}
			if _, e := os.Stat(record.Path); e != nil {
				t.Fatal("failure record missing", e)
			}
		})
	}
	opts := productOptions(t)
	opts.ReportPath = filepath.Join(t.TempDir(), "old.json")
	os.WriteFile(opts.ReportPath, []byte("old"), 0600)
	record, e := RunProduct(context.Background(), opts, productEnvironment("pass"))
	if e == nil || record.Process.Classification != "not_started" {
		t.Fatal(record, e)
	}
	data, _ := os.ReadFile(opts.ReportPath)
	if string(data) != "old" {
		t.Fatal("old report changed")
	}
}
func TestProductExpectedNegativeRequiresNamedAssertionFailure(t *testing.T) {
	for _, mode := range []string{"pass", "nonassertion", "timeout", "setup", "masked"} {
		t.Run(mode, func(t *testing.T) {
			opts := productOptions(t)
			opts.Expect = "tests-fail"
			opts.RequiredTests = nil
			opts.ExpectedFailedTests = []string{"defect"}
			record, e := RunProduct(context.Background(), opts, productEnvironment(mode))
			if e == nil || record.ExpectationMet {
				t.Fatal(record, e)
			}
		})
	}
	opts := productOptions(t)
	opts.Expect = "tests-fail"
	opts.RequiredTests = nil
	if _, e := RunProduct(context.Background(), opts, productEnvironment("assertion")); e == nil {
		t.Fatal("unnamed expected failure accepted")
	}
}
func TestProductRequiresSelectedTestsAndMinimumCount(t *testing.T) {
	for _, kind := range []string{"missing name", "count"} {
		t.Run(kind, func(t *testing.T) {
			opts := productOptions(t)
			if kind == "count" {
				opts.MinTests = 2
			} else {
				opts.RequiredTests = []string{"unselected test"}
			}
			r, e := RunProduct(context.Background(), opts, productEnvironment("pass"))
			if e == nil || r.ExpectationMet {
				t.Fatal(r, e)
			}
		})
	}
}
func TestRecordedPlaywrightAndJestReportShapes(t *testing.T) {
	for _, format := range []string{"playwright", "jest"} {
		t.Run(format, func(t *testing.T) {
			data, e := os.ReadFile("testdata/product-" + format + ".json")
			if e != nil {
				t.Fatal(e)
			}
			r, e := ParseProductReport(format, data)
			if e != nil || r.Passed == 0 || r.Failed != 0 || r.Total != r.Passed {
				t.Fatal(r, e)
			}
			if e = validateProductOutcome(ProductOptions{Expect: "pass", MinTests: 1}, 0, r); e != nil {
				t.Fatal(e)
			}
		})
	}
}
func TestJestSetupInterruptionAndAssertionFailure(t *testing.T) {
	data, _ := os.ReadFile("testdata/product-jest.json")
	for _, kind := range []string{"assertion", "setup", "interrupted", "empty assertions", "false success", "missing counter"} {
		t.Run(kind, func(t *testing.T) {
			var report map[string]any
			json.Unmarshal(data, &report)
			suite := report["testResults"].([]any)[0].(map[string]any)
			test := suite["assertionResults"].([]any)[0].(map[string]any)
			switch kind {
			case "assertion":
				report["numFailedTests"] = 1
				report["numPassedTests"] = 0
				report["success"] = false
				test["status"] = "failed"
				test["failureMessages"] = []string{"Error: expect(received).toBe(expected)"}
				suite["status"] = "failed"
			case "setup":
				report["numRuntimeErrorTestSuites"] = 1
				report["success"] = false
			case "interrupted":
				report["wasInterrupted"] = true
				report["success"] = false
			case "empty assertions":
				suite["assertionResults"] = []any{}
			case "false success":
				report["success"] = false
			case "missing counter":
				delete(report, "numTotalTests")
			}
			encoded, _ := json.Marshal(report)
			summary, e := ParseProductReport("jest", encoded)
			if kind == "assertion" {
				if e != nil {
					t.Fatal(e)
				}
				e = validateProductOutcome(ProductOptions{Expect: "tests-fail", MinTests: 1, ExpectedFailedTests: []string{"succeeds"}}, 1, summary)
				if e != nil {
					t.Fatal(summary, e)
				}
				return
			}
			if e == nil {
				e = validateProductOutcome(ProductOptions{Expect: "pass", MinTests: 1}, 0, summary)
			}
			if e == nil {
				t.Fatal("invalid run accepted", summary)
			}
		})
	}
}

func TestProductRejectsPreviousReportCopiedToFreshPath(t *testing.T) {
	opts := productOptions(t)
	first, e := RunProduct(context.Background(), opts, productEnvironment("pass"))
	if e != nil {
		t.Fatal(e)
	}
	opts.OutputDirectory = filepath.Join(filepath.Dir(opts.OutputDirectory), "copied")
	opts.Command = []string{"cp", first.ReportCopy, "{report}"}
	second, e := RunProduct(context.Background(), opts, os.Environ())
	if e == nil || second.ExpectationMet || second.Process.ExitCode != 0 {
		t.Fatal(second, e)
	}
}
func TestActualAssertionFailureRemainsValidMeasurement(t *testing.T) {
	opts := productOptions(t)
	record, e := RunProduct(context.Background(), opts, productEnvironment("assertion"))
	if e == nil || record.Error != "" || record.ExpectationError == "" || record.ExpectationMet || record.Verdict != "tests-failed" {
		t.Fatal(record, e)
	}
	if e = ValidateProductCapture(record, opts.OutputDirectory); e != nil {
		t.Fatal("valid failed tests lost", e)
	}
}

func TestProductTimeoutCannotBecomeSuccessfulAggregateWithoutSelectors(t *testing.T) {
	opts := productOptions(t)
	opts.RequiredTests = nil
	record, e := RunProduct(context.Background(), opts, productEnvironment("timeout"))
	if e == nil || record.Verdict != "rejected" || record.ExpectationMet {
		t.Fatal(record, e)
	}
}
