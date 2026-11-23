package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
)

func TestProductCLIChild(t *testing.T) {
	mode := os.Getenv("PRODUCT_CLI_FIXTURE")
	if mode == "" {
		return
	}
	fail := mode == "fail"
	passed, failed, status, exit := 1, 0, "passed", 0
	messages := []string{}
	if fail {
		passed, failed, status, exit = 0, 1, "failed", 1
		messages = []string{"expect(received).toBe(expected)"}
	}
	report := map[string]any{"numTotalTests": 1, "numPassedTests": passed, "numFailedTests": failed, "numPendingTests": 0, "numRuntimeErrorTestSuites": 0, "success": !fail, "wasInterrupted": false, "startTime": time.Now().UnixMilli(), "testResults": []any{map[string]any{"name": "spec.test.ts", "status": status, "assertionResults": []any{map[string]any{"title": "defect", "fullName": "suite defect", "status": status, "failureMessages": messages}}}}}
	b, _ := json.Marshal(report)
	if e := os.WriteFile(os.Getenv("PRODUCT_CHECK_REPORT"), b, 0600); e != nil {
		os.Exit(3)
	}
	os.Exit(exit)
}
func TestCLIArgvReportAndExpectedFailure(t *testing.T) {
	for _, mode := range []string{"pass", "fail"} {
		t.Run(mode, func(t *testing.T) {
			t.Setenv("PRODUCT_CLI_FIXTURE", mode)
			root := t.TempDir()
			output := filepath.Join(root, "capture")
			args := []string{"--cwd", root, "--output", output, "--report-format", "jest"}
			if mode == "fail" {
				args = append(args, "--expect", "tests-fail", "--expect-failed-test", "suite defect")
			} else {
				args = append(args, "--require-test", "suite defect")
			}
			args = append(args, "--", os.Args[0], "-test.run=TestProductCLIChild", "--", "--outputFile={report}")
			if e := run(context.Background(), args); e != nil {
				t.Fatal(e)
			}
			b, e := os.ReadFile(filepath.Join(output, "result.json"))
			if e != nil {
				t.Fatal(e)
			}
			var result checkrunner.ProductRecord
			if e = json.Unmarshal(b, &result); e != nil {
				t.Fatal(e)
			}
			if !result.ExpectationMet || len(result.ExecutedCommand) == 0 || strings.Contains(strings.Join(result.ExecutedCommand, " "), "{report}") {
				t.Fatal(result)
			}
			if mode == "fail" && (result.Process.ExitCode != 1 || result.Verdict != "expected-test-failure") {
				t.Fatal(result)
			}
		})
	}
}
func TestCLIShellIsExplicitAndUsesPipefail(t *testing.T) {
	t.Setenv("PRODUCT_CLI_FIXTURE", "fail")
	root := t.TempDir()
	script := "'" + strings.ReplaceAll(os.Args[0], "'", "'\\''") + "' -test.run=TestProductCLIChild | tee /dev/null"
	args := []string{"--cwd", root, "--output", filepath.Join(root, "capture"), "--report-format", "jest", "--expect", "tests-fail", "--expect-failed-test", "defect", "--shell", script}
	if e := run(context.Background(), args); e != nil {
		t.Fatal(e)
	}
	if e := run(context.Background(), append(args, "--", "true")); e == nil {
		t.Fatal("shell plus argv accepted")
	}
}
