package checkrunner

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

type ProductOptions struct {
	CWD                 string   `json:"cwd"`
	OutputDirectory     string   `json:"output_directory"`
	Command             []string `json:"command"`
	ReportPath          string   `json:"report_path"`
	ReportFormat        string   `json:"report_format"`
	Expect              string   `json:"expect"`
	MinTests            int      `json:"min_tests"`
	RequiredTests       []string `json:"required_tests,omitempty"`
	ExpectedFailedTests []string `json:"expected_failed_tests,omitempty"`
}
type ExpectationError struct{ Message string }

func (e *ExpectationError) Error() string { return e.Message }

type ProductRecord struct {
	ExpectationError string                   `json:"expectation_error,omitempty"`
	ExecutedCommand  []string                 `json:"executed_command,omitempty"`
	OutputSHA256     map[string]string        `json:"output_sha256,omitempty"`
	Version          string                   `json:"version"`
	Options          ProductOptions           `json:"options"`
	Process          experimentruntime.Result `json:"process"`
	DurationSeconds  float64                  `json:"duration_seconds"`
	Summary          *ProductSummary          `json:"summary,omitempty"`
	Verdict          string                   `json:"verdict"`
	ExpectationMet   bool                     `json:"expectation_met"`
	ReportSHA256     string                   `json:"report_sha256,omitempty"`
	ReportCopy       string                   `json:"report_copy,omitempty"`
	Error            string                   `json:"error,omitempty"`
	Path             string                   `json:"-"`
}

// RunProduct always runs the command and validates its newly written test report.
func RunProduct(ctx context.Context, opts ProductOptions, env []string) (record ProductRecord, err error) {
	record = ProductRecord{Version: "product-check-v1", Options: opts, Verdict: "rejected", Process: experimentruntime.Result{ExitCode: -1, Classification: "not_started"}}
	if !filepath.IsAbs(opts.CWD) || !filepath.IsAbs(opts.OutputDirectory) || len(opts.Command) == 0 || opts.MinTests < 1 {
		return record, fmt.Errorf("product check requires absolute cwd/output paths, a command, and min-tests of at least 1")
	}
	if opts.ReportFormat != "playwright" && opts.ReportFormat != "jest" {
		return record, fmt.Errorf("report-format must be playwright or jest")
	}
	if opts.Expect != "pass" && opts.Expect != "tests-fail" {
		return record, fmt.Errorf("expect must be pass or tests-fail")
	}
	if opts.Expect == "tests-fail" && len(opts.ExpectedFailedTests) == 0 {
		return record, fmt.Errorf("tests-fail requires at least one named expected failing test")
	}
	if opts.Expect == "pass" && len(opts.ExpectedFailedTests) > 0 {
		return record, fmt.Errorf("expected failing tests require expect=tests-fail")
	}
	if opts.ReportPath == "" {
		opts.ReportPath = filepath.Join(opts.OutputDirectory, "test-report.json")
	}
	if !filepath.IsAbs(opts.ReportPath) {
		return record, fmt.Errorf("report path must be absolute")
	}
	for _, reserved := range []string{"stdout.log", "stderr.log", "result.json", "progress.json", "progress.jsonl"} {
		if filepath.Clean(opts.ReportPath) == filepath.Join(opts.OutputDirectory, reserved) {
			return record, fmt.Errorf("report path conflicts with reserved capture file %s", reserved)
		}
	}
	if _, e := os.Lstat(opts.ReportPath); e == nil {
		return record, fmt.Errorf("test report already exists; select a new report path")
	} else if !os.IsNotExist(e) {
		return record, e
	}
	if err = os.Mkdir(opts.OutputDirectory, 0700); err != nil {
		return record, err
	}
	record.Options = opts
	record.Path = filepath.Join(opts.OutputDirectory, "result.json")
	defer func() {
		if err != nil {
			var mismatch *ExpectationError
			if errors.As(err, &mismatch) {
				record.ExpectationError = err.Error()
			} else {
				record.Error = err.Error()
			}
		}
		if e := write(record.Path, record); e != nil {
			err = errors.Join(err, e)
		}
	}()
	program, e := resolveProgram(opts.Command[0], opts.CWD, env)
	if e != nil {
		return record, e
	}
	args := append([]string(nil), opts.Command[1:]...)
	for i, arg := range args {
		args[i] = strings.ReplaceAll(arg, "{report}", opts.ReportPath)
	}
	commandEnv := append([]string(nil), env...)
	for key, value := range map[string]string{"PRODUCT_CHECK_REPORT": opts.ReportPath, "PLAYWRIGHT_JSON_OUTPUT_FILE": opts.ReportPath, "PLAYWRIGHT_JSON_OUTPUT_NAME": filepath.Base(opts.ReportPath), "PLAYWRIGHT_JSON_OUTPUT_DIR": filepath.Dir(opts.ReportPath), "PLAYWRIGHT_JSON_ONLY_FAILURES": "false"} {
		filtered := commandEnv[:0]
		for _, entry := range commandEnv {
			if !strings.HasPrefix(entry, key+"=") {
				filtered = append(filtered, entry)
			}
		}
		commandEnv = append(filtered, key+"="+value)
	}
	record.ExecutedCommand = append([]string{program}, args...)
	process, runErr := experimentruntime.RunWithProgress(ctx, experimentruntime.Command{Program: program, Args: args, Workspace: opts.CWD}, opts.OutputDirectory, commandEnv, nil, nil, experimentruntime.ProgressOptions{Runtime: "command", Stage: "product-check"})
	record.Process = process
	record.DurationSeconds = process.Duration.Seconds()
	record.OutputSHA256 = map[string]string{}
	for _, path := range []string{process.StdoutPath, process.StderrPath} {
		if path != "" {
			if sum, e := fileDigest(path); e == nil {
				record.OutputSHA256[path] = sum
			}
		}
	}
	if ctx.Err() != nil || process.ExitCode < 0 || process.Classification == "capture_error" {
		return record, fmt.Errorf("test command did not finish normally (%s, exit %d): %v", process.Classification, process.ExitCode, errors.Join(runErr, ctx.Err()))
	}
	if runErr != nil {
		if _, ok := runErr.(*exec.ExitError); !ok {
			return record, fmt.Errorf("test command capture failed: %w", runErr)
		}
	}
	info, e := os.Lstat(opts.ReportPath)
	if e != nil {
		return record, fmt.Errorf("test command produced no report: %w", e)
	}
	if !info.Mode().IsRegular() || info.Size() == 0 {
		return record, fmt.Errorf("test report must be a nonempty regular file")
	}
	data, e := os.ReadFile(opts.ReportPath)
	if e != nil {
		return record, e
	}
	record.ReportSHA256 = digest(data)
	record.ReportCopy = filepath.Join(opts.OutputDirectory, "captured-test-report.json")
	if e = os.WriteFile(record.ReportCopy, data, 0600); e != nil {
		return record, e
	}
	record.OutputSHA256[record.ReportCopy] = record.ReportSHA256
	summary, e := ParseProductReport(opts.ReportFormat, data)
	if e != nil {
		return record, fmt.Errorf("invalid %s report: %w", opts.ReportFormat, e)
	}
	record.Summary = &summary
	if summary.StartedAt.UnixMilli() < process.StartedAt.UnixMilli() || summary.StartedAt.UnixMilli() > process.FinishedAt.UnixMilli() {
		return record, fmt.Errorf("test report start time is outside this command invocation")
	}
	if e = validateProductMeasurement(opts, process.ExitCode, summary); e != nil {
		return record, e
	}
	record.Verdict = "tests-passed"
	if summary.Failed > 0 {
		record.Verdict = "tests-failed"
	}
	if e = validateProductExpectation(opts, summary); e != nil {
		return record, &ExpectationError{Message: e.Error()}
	}
	record.ExpectationMet = true
	if opts.Expect == "tests-fail" {
		record.Verdict = "expected-test-failure"
	}
	return record, nil
}

func validateProductMeasurement(opts ProductOptions, exit int, summary ProductSummary) error {
	if summary.SetupErrors > 0 || summary.Interrupted > 0 {
		return fmt.Errorf("test run contains setup/runtime errors or interruptions")
	}
	if summary.Flaky > 0 || summary.ExpectedFailures > 0 {
		return fmt.Errorf("flaky results and expected-failure annotations cannot establish this check")
	}
	if summary.Passed+summary.Failed < opts.MinTests {
		return fmt.Errorf("test run executed fewer than %d tests", opts.MinTests)
	}
	if exit == 0 && summary.Failed > 0 || exit != 0 && summary.Failed == 0 {
		return fmt.Errorf("child exit %d conflicts with report: %d passed, %d failed", exit, summary.Passed, summary.Failed)
	}
	for _, test := range summary.Tests {
		if test.Outcome != "pass" && test.Outcome != "fail" && test.Outcome != "skipped" {
			return fmt.Errorf("test %q has incomplete or non-assertion outcome %s", test.FullName, test.Outcome)
		}
		if test.Outcome == "fail" && !test.AssertionFailure {
			return fmt.Errorf("failed test %q has no assertion-failure evidence", test.FullName)
		}
	}
	for _, name := range append(append([]string(nil), opts.RequiredTests...), opts.ExpectedFailedTests...) {
		test, e := findProductTest(summary, name)
		if e != nil {
			return e
		}
		if test.Outcome != "pass" && test.Outcome != "fail" {
			return fmt.Errorf("required test %q did not execute", name)
		}
	}
	return nil
}
func findProductTest(summary ProductSummary, name string) (ProductTest, error) {
	var hits []ProductTest
	for _, test := range summary.Tests {
		if test.FullName == name || test.Name == name {
			hits = append(hits, test)
		}
	}
	if len(hits) != 1 {
		return ProductTest{}, fmt.Errorf("required test %q is absent or ambiguous", name)
	}
	return hits[0], nil
}
func validateProductExpectation(opts ProductOptions, summary ProductSummary) error {
	for _, name := range opts.RequiredTests {
		test, e := findProductTest(summary, name)
		if e != nil {
			return e
		}
		if test.Outcome != "pass" {
			return fmt.Errorf("required healthy test %q did not pass", name)
		}
	}

	if opts.Expect == "pass" {
		if summary.Failed > 0 {
			return fmt.Errorf("test command failed")
		}
		return nil
	}
	if summary.Failed == 0 {
		return fmt.Errorf("expected assertion failure was not observed")
	}
	allowed := map[string]bool{}
	for _, name := range opts.ExpectedFailedTests {
		test, e := findProductTest(summary, name)
		if e != nil {
			return e
		}
		if test.Outcome != "fail" || !test.AssertionFailure {
			return fmt.Errorf("expected test %q has no assertion-failure evidence", name)
		}
		allowed[test.File+"\x00"+test.Project+"\x00"+test.FullName] = true
	}
	for _, test := range summary.Tests {
		if test.Outcome != "pass" && test.Outcome != "skipped" {
			if !allowed[test.File+"\x00"+test.Project+"\x00"+test.FullName] || !test.AssertionFailure {
				return fmt.Errorf("test %q failed outside the expected assertion failures", test.FullName)
			}
		}
	}
	return nil
}

func validateProductOutcome(opts ProductOptions, exit int, summary ProductSummary) error {
	if e := validateProductMeasurement(opts, exit, summary); e != nil {
		return e
	}
	return validateProductExpectation(opts, summary)
}

func BashProductCommand(script string) []string {
	return []string{"bash", "-o", "pipefail", "-c", script}
}

// ProductResultJSON is useful for callers that need the same machine-readable CLI summary.
func ProductResultJSON(record ProductRecord) ([]byte, error) {
	observations, _ := ProductObservations(record)
	return json.Marshal(map[string]any{"result": record.Path, "verdict": record.Verdict, "expectation_met": record.ExpectationMet, "child_exit_code": record.Process.ExitCode, "error": record.Error, "expectation_error": record.ExpectationError, "observations": observations})
}

// ValidateProductCapture checks the recorded outcome and the archived inputs to that decision.
func ValidateProductCapture(record ProductRecord, directory string) error {
	if record.Version != "product-check-v1" || record.Error != "" {
		return fmt.Errorf("product capture did not satisfy its declared expectation")
	}
	if filepath.Clean(record.Options.OutputDirectory) != filepath.Clean(directory) || record.ReportCopy != filepath.Join(directory, "captured-test-report.json") || record.Process.StdoutPath != filepath.Join(directory, "stdout.log") || record.Process.StderrPath != filepath.Join(directory, "stderr.log") {
		return fmt.Errorf("product capture paths differ from its directory")
	}
	if len(record.ExecutedCommand) == 0 || record.Summary == nil || record.Options.MinTests < 1 || len(record.OutputSHA256) != 3 {
		return fmt.Errorf("product capture is incomplete")
	}
	if record.Options.Expect != "pass" && record.Options.Expect != "tests-fail" {
		return fmt.Errorf("product capture has an unknown expectation")
	}
	if record.Options.Expect == "tests-fail" && len(record.Options.ExpectedFailedTests) == 0 {
		return fmt.Errorf("negative product capture has no named failing test")
	}
	if record.Process.ExitCode < 0 || record.Process.FinishedAt.Before(record.Process.StartedAt) || record.Process.Classification != "completed_unverified" && record.Process.Classification != "process_error" {
		return fmt.Errorf("product capture process did not complete")
	}
	for _, path := range []string{record.ReportCopy, record.Process.StdoutPath, record.Process.StderrPath} {
		resolved, e := filepath.EvalSymlinks(path)
		if e != nil || resolved != path {
			return fmt.Errorf("product capture artifact is missing or uses a symlink")
		}
		got, e := fileDigest(path)
		if e != nil || record.OutputSHA256[path] == "" || got != record.OutputSHA256[path] {
			return fmt.Errorf("product capture artifact hash differs: %s", path)
		}
	}
	data, e := os.ReadFile(record.ReportCopy)
	if e != nil {
		return e
	}
	if digest(data) != record.ReportSHA256 {
		return fmt.Errorf("product report hash differs")
	}
	summary, e := ParseProductReport(record.Options.ReportFormat, data)
	if e != nil {
		return e
	}
	want, _ := json.Marshal(summary)
	got, _ := json.Marshal(record.Summary)
	if string(want) != string(got) {
		return fmt.Errorf("product summary differs from its test report")
	}
	if summary.StartedAt.UnixMilli() < record.Process.StartedAt.UnixMilli() || summary.StartedAt.UnixMilli() > record.Process.FinishedAt.UnixMilli() {
		return fmt.Errorf("product report belongs to another invocation")
	}
	if e = validateProductMeasurement(record.Options, record.Process.ExitCode, summary); e != nil {
		return e
	}
	verdict := "tests-passed"
	if summary.Failed > 0 {
		verdict = "tests-failed"
	}
	expectation := validateProductExpectation(record.Options, summary)
	if record.ExpectationMet != (expectation == nil) {
		return fmt.Errorf("product expectation differs from the report")
	}
	reason := ""
	if expectation != nil {
		reason = expectation.Error()
	}
	if reason != record.ExpectationError {
		return fmt.Errorf("product expectation error differs from the report")
	}
	if expectation == nil && record.Options.Expect == "tests-fail" {
		verdict = "expected-test-failure"
	}
	if record.Verdict != verdict {
		return fmt.Errorf("product verdict differs from its expected outcome")
	}
	return nil
}
