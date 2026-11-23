package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestProductCheckConfigAndPromptGuidance(t *testing.T) {
	for _, kind := range []string{"relative", "missing", "directory", "not executable", "documentation"} {
		t.Run(kind, func(t *testing.T) {
			cfg := fixture(t)
			switch kind {
			case "relative":
				cfg.ProductCheckBinary = "product-check"
			case "missing":
				cfg.ProductCheckBinary = filepath.Join(t.TempDir(), "absent")
			case "directory":
				cfg.ProductCheckBinary = t.TempDir()
			case "not executable":
				cfg.ProductCheckBinary = filepath.Join(t.TempDir(), "binary")
				os.WriteFile(cfg.ProductCheckBinary, []byte("data"), 0600)
			case "documentation":
				cfg.ProductCheckBinary = cfg.RuntimeBinary
				cfg.TaskKind = "documentation"
			}
			if _, e := Prepare(context.Background(), cfg); e == nil {
				t.Fatal("invalid product helper config accepted")
			}
		})
	}
	cfg := fixture(t)
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	d, e := Prepare(context.Background(), cfg)
	if e != nil {
		t.Fatal(e)
	}
	snap, _ := d.Snapshot(context.Background())
	inv := workcycle.Invocation{Request: d.Request, Stage: workcycle.ContractStage, Candidate: snap}
	if _, e = d.Run(context.Background(), inv); e != nil {
		t.Fatal(e)
	}
	dir := filepath.Join(cfg.OutputDirectory, "step-0001-contract")
	b, _ := os.ReadFile(filepath.Join(dir, "prompt.txt"))
	if !strings.Contains(string(b), cfg.ProductCheckBinary) || !strings.Contains(string(b), "--reporter=line,json") || !strings.Contains(string(b), "--outputFile={report}") {
		t.Fatal("configured helper not supplied")
	}
	b, _ = os.ReadFile(filepath.Join(dir, "invocation.json"))
	var archived workcycle.Invocation
	if e = json.Unmarshal(b, &archived); e != nil || archived.Request.Context != d.Packet.Text {
		t.Fatal("original invocation context changed", e)
	}
	if ProductCheckGuidance("", t.TempDir()) != "" {
		t.Fatal("disabled helper changes prompt")
	}
}
func productCapture(t *testing.T, root, kind string) string {
	t.Helper()
	dir := filepath.Join(root, "capture")
	if e := os.Mkdir(dir, 0700); e != nil {
		t.Fatal(e)
	}
	now := time.Now().UTC()
	failed := kind != "pass"
	npass, nfail, status, aggregate, exit := 1, 0, "passed", "expected", 0
	errors := []any{}
	if failed {
		npass, nfail, status, aggregate, exit = 0, 1, "failed", "unexpected", 1
		errors = []any{map[string]string{"message": "Error: expect(received).toEqual(expected)"}}
	}
	if kind == "timeout" {
		status = "timedOut"
	}
	result := map[string]any{"status": status, "errors": errors}
	test := map[string]any{"projectName": "ui", "expectedStatus": "passed", "status": aggregate, "results": []any{result}}
	spec := map[string]any{"title": "defect", "file": "spec.ts", "tests": []any{test}}
	suite := map[string]any{"title": "suite", "specs": []any{spec}}
	body := map[string]any{"errors": []any{}, "stats": map[string]any{"startTime": now.Format(time.RFC3339Nano), "expected": npass, "unexpected": nfail, "skipped": 0, "flaky": 0}, "suites": []any{suite}}
	data, _ := json.Marshal(body)
	report := filepath.Join(dir, "captured-test-report.json")
	os.WriteFile(report, data, 0600)
	stdout, stderr := filepath.Join(dir, "stdout.log"), filepath.Join(dir, "stderr.log")
	os.WriteFile(stdout, []byte("measured output"), 0600)
	os.WriteFile(stderr, nil, 0600)
	summary, e := checkrunner.ParseProductReport("playwright", data)
	if e != nil {
		t.Fatal(e)
	}
	record := checkrunner.ProductRecord{Version: "product-check-v1", ExecutedCommand: []string{"fixture"}, Options: checkrunner.ProductOptions{OutputDirectory: dir, ReportFormat: "playwright", Expect: "pass", MinTests: 1}, Process: experimentruntime.Result{ExitCode: exit, Classification: "completed_unverified", StartedAt: now, FinishedAt: now.Add(time.Millisecond), StdoutPath: stdout, StderrPath: stderr}, Summary: &summary, Verdict: "tests-passed", ExpectationMet: true, ReportCopy: report, ReportSHA256: bytesDigest(data), OutputSHA256: map[string]string{stdout: bytesDigest([]byte("measured output")), stderr: bytesDigest(nil), report: bytesDigest(data)}}
	if failed {
		record.Process.Classification = "process_error"
		record.Verdict = "tests-failed"
		record.ExpectationMet = false
		record.ExpectationError = "test command failed"
	}
	if kind == "negative" {
		record.Options.Expect = "tests-fail"
		record.Options.ExpectedFailedTests = []string{"defect"}
		record.Verdict = "expected-test-failure"
		record.ExpectationMet = true
		record.ExpectationError = ""
	}
	if kind == "rejected" {
		record.Verdict = "rejected"
		record.Error = "capture rejected"
	}
	path := filepath.Join(dir, "result.json")
	if e = writeJSON(path, record); e != nil {
		t.Fatal(e)
	}
	return path
}
func TestProductCaptureMatchesTypedOutcomesAndKeepsFailuresForRepair(t *testing.T) {
	for _, tc := range []struct {
		kind, outcome string
		stage         workcycle.Stage
		valid         bool
	}{{"pass", "pass", workcycle.VerifyStage, true}, {"fail", "fail", workcycle.VerifyStage, true}, {"fail", "pass", workcycle.VerifyStage, false}, {"negative", "fail", workcycle.ReproduceStage, true}, {"negative", "pass", workcycle.ReproduceStage, false}, {"negative", "pass", workcycle.VerifyStage, false}, {"rejected", "pass", workcycle.VerifyStage, false}, {"timeout", "pass", workcycle.VerifyStage, false}, {"timeout", "fail", workcycle.VerifyStage, false}} {
		t.Run(tc.kind+"-"+tc.outcome+"-"+string(tc.stage), func(t *testing.T) {
			root := t.TempDir()
			path := productCapture(t, root, tc.kind)
			r := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: tc.outcome, Evidence: []string{path}}}}
			d := &Driver{evidence: root}
			if e := d.verifyArtifacts(r, root); e != nil {
				t.Fatal(e)
			}
			e := d.validateProductResults(r, root, tc.stage)
			if (e == nil) != tc.valid {
				t.Fatal(e)
			}
			if tc.valid {
				if len(d.artifacts) != 4 {
					t.Fatal("capture internals not bound", d.artifacts)
				}
			}
		})
	}
}
func TestProductCaptureRejectsTamperingOldEvidenceAndUnboundFailure(t *testing.T) {
	for _, kind := range []string{"report tamper", "stdout tamper", "old stage", "unbound failure"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			captureRoot := root
			if kind == "old stage" {
				captureRoot = t.TempDir()
			}
			state := "pass"
			if kind == "unbound failure" {
				state = "fail"
			}
			path := productCapture(t, captureRoot, state)
			if kind == "report tamper" {
				os.WriteFile(filepath.Join(captureRoot, "capture/captured-test-report.json"), []byte("{}"), 0600)
			}
			if kind == "stdout tamper" {
				os.WriteFile(filepath.Join(captureRoot, "capture/stdout.log"), []byte("altered"), 0600)
			}
			d := &Driver{evidence: root}
			r := workcycle.Response{Evidence: []string{path}}
			if e := d.validateProductResults(r, root, workcycle.VerifyStage); e == nil {
				t.Fatal("invalid capture accepted")
			}
		})
	}
}

func addProductTest(t *testing.T, path, name, outcome string) {
	t.Helper()
	var record checkrunner.ProductRecord
	b, _ := os.ReadFile(path)
	json.Unmarshal(b, &record)
	b, _ = os.ReadFile(record.ReportCopy)
	var report map[string]any
	json.Unmarshal(b, &report)
	status, aggregate, expected := "passed", "expected", "passed"
	messages := []any{}
	switch outcome {
	case "fail":
		status, aggregate = "failed", "unexpected"
		messages = []any{map[string]string{"message": "Error: expect(received).toBe(expected)"}}
	case "skipped":
		status, aggregate, expected = "skipped", "skipped", "skipped"
	}
	test := map[string]any{"expectedStatus": expected, "projectName": "ui", "status": aggregate, "results": []any{map[string]any{"status": status, "errors": messages}}}
	spec := map[string]any{"title": name, "file": "spec.ts", "tests": []any{test}}
	suite := report["suites"].([]any)[0].(map[string]any)
	suite["specs"] = append(suite["specs"].([]any), spec)
	stats := report["stats"].(map[string]any)
	key := aggregate
	stats[key] = stats[key].(float64) + 1
	b, _ = json.Marshal(report)
	os.WriteFile(record.ReportCopy, b, 0600)
	summary, e := checkrunner.ParseProductReport("playwright", b)
	if e != nil {
		t.Fatal(e)
	}
	record.Summary = &summary
	record.ReportSHA256 = bytesDigest(b)
	record.OutputSHA256[record.ReportCopy] = record.ReportSHA256
	if e = writeJSON(path, record); e != nil {
		t.Fatal(e)
	}
}
func TestMixedBaselineCaptureSupportsHealthyAndFailingSelectors(t *testing.T) {
	root := t.TempDir()
	path := productCapture(t, root, "negative")
	addProductTest(t, path, "healthy peer", "pass")
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "defect", Outcome: "fail", ProductTests: []string{"defect"}, Evidence: []string{path}}, {ID: "peer", Outcome: "pass", ProductTests: []string{"healthy peer"}, Evidence: []string{path}}}}
	d := &Driver{evidence: root}
	if e := d.validateProductResults(response, root, workcycle.ReproduceStage); e != nil {
		t.Fatal(e)
	}
	response.Scenarios[1].ProductTests = []string{"defect"}
	if e := d.validateProductResults(response, root, workcycle.ReproduceStage); e == nil {
		t.Fatal("failed test supported healthy pass")
	}
}
func TestProductSelectorsRejectMissingAmbiguousAndSkippedTests(t *testing.T) {
	for _, kind := range []string{"missing", "ambiguous", "skipped"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			path := productCapture(t, root, "pass")
			name := "missing"
			if kind == "ambiguous" {
				addProductTest(t, path, "defect", "pass")
				name = "defect"
			}
			if kind == "skipped" {
				addProductTest(t, path, "skipped test", "skipped")
				name = "skipped test"
			}
			d := &Driver{evidence: root}
			response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "scenario", Outcome: "pass", ProductTests: []string{name}, Evidence: []string{path}}}}
			if e := d.validateProductResults(response, root, workcycle.VerifyStage); e == nil {
				t.Fatal("invalid selection accepted")
			}
		})
	}
}
func TestFinalProductFailuresMustAllBeReported(t *testing.T) {
	root := t.TempDir()
	path := productCapture(t, root, "fail")
	addProductTest(t, path, "second defect", "fail")
	d := &Driver{evidence: root}
	response := workcycle.Response{Scenarios: []workcycle.Result{{ID: "first", Outcome: "fail", ProductTests: []string{"defect"}, Evidence: []string{path}}}}
	if e := d.validateProductResults(response, root, workcycle.VerifyStage); e == nil {
		t.Fatal("unreported second failure accepted")
	}
	response.Scenarios = append(response.Scenarios, workcycle.Result{ID: "second", Outcome: "fail", ProductTests: []string{"second defect"}, Evidence: []string{path}})
	if e := d.validateProductResults(response, root, workcycle.VerifyStage); e != nil {
		t.Fatal(e)
	}
}
func TestAggregateResultCanCombinePassingAndFailingCaptures(t *testing.T) {
	root := t.TempDir()
	a, b := filepath.Join(root, "a"), filepath.Join(root, "b")
	os.Mkdir(a, 0700)
	os.Mkdir(b, 0700)
	pass, fail := productCapture(t, a, "pass"), productCapture(t, b, "fail")
	d := &Driver{evidence: root}
	response := workcycle.Response{Checks: []workcycle.Result{{ID: "aggregate", Outcome: "fail", Evidence: []string{pass, fail}}}}
	if e := d.validateProductResults(response, root, workcycle.VerifyStage); e != nil {
		t.Fatal(e)
	}
}
