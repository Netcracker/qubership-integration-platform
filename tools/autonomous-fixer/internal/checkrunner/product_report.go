package checkrunner

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type ProductTest struct {
	Name             string `json:"name"`
	FullName         string `json:"full_name"`
	Project          string `json:"project,omitempty"`
	File             string `json:"file,omitempty"`
	Outcome          string `json:"outcome"`
	AssertionFailure bool   `json:"assertion_failure,omitempty"`
}
type ProductSummary struct {
	Format           string        `json:"format"`
	StartedAt        time.Time     `json:"started_at"`
	Total            int           `json:"total"`
	Passed           int           `json:"passed"`
	Failed           int           `json:"failed"`
	Skipped          int           `json:"skipped"`
	Flaky            int           `json:"flaky"`
	Interrupted      int           `json:"interrupted"`
	ExpectedFailures int           `json:"expected_failures"`
	SetupErrors      int           `json:"setup_errors"`
	Tests            []ProductTest `json:"tests"`
}

func assertionMessage(message string) bool {
	// Report wording identifies assertion evidence, not whether the product caused it.
	return strings.Contains(message, "expect(") || strings.Contains(message, "AssertionError")
}
func ParseProductReport(format string, data []byte) (ProductSummary, error) {
	switch format {
	case "playwright":
		return parsePlaywright(data)
	case "jest":
		return parseJest(data)
	}
	return ProductSummary{}, fmt.Errorf("unsupported test report format %q", format)
}

type playwrightError struct {
	Message string `json:"message"`
}
type playwrightResult struct {
	Status string            `json:"status"`
	Error  *playwrightError  `json:"error"`
	Errors []playwrightError `json:"errors"`
}
type playwrightTest struct {
	ProjectName    string             `json:"projectName"`
	ExpectedStatus string             `json:"expectedStatus"`
	Status         string             `json:"status"`
	Results        []playwrightResult `json:"results"`
}
type playwrightSpec struct {
	Title string           `json:"title"`
	File  string           `json:"file"`
	Tests []playwrightTest `json:"tests"`
}
type playwrightSuite struct {
	Title  string            `json:"title"`
	Suites []playwrightSuite `json:"suites"`
	Specs  []playwrightSpec  `json:"specs"`
}

func parsePlaywright(data []byte) (ProductSummary, error) {
	result := ProductSummary{Format: "playwright", Tests: []ProductTest{}}
	var report struct {
		Suites []playwrightSuite `json:"suites"`
		Errors []json.RawMessage `json:"errors"`
		Stats  *struct {
			StartTime  string `json:"startTime"`
			Expected   *int   `json:"expected"`
			Unexpected *int   `json:"unexpected"`
			Skipped    *int   `json:"skipped"`
			Flaky      *int   `json:"flaky"`
		} `json:"stats"`
	}
	if err := json.Unmarshal(data, &report); err != nil {
		return result, err
	}
	if report.Stats == nil || report.Stats.Expected == nil || report.Stats.Unexpected == nil || report.Stats.Skipped == nil || report.Stats.Flaky == nil {
		return result, fmt.Errorf("Playwright report is missing complete statistics")
	}
	var err error
	result.StartedAt, err = time.Parse(time.RFC3339Nano, report.Stats.StartTime)
	if err != nil {
		return result, fmt.Errorf("Playwright report has no valid startTime")
	}
	result.SetupErrors = len(report.Errors)
	counts := map[string]int{}
	var visit func(playwrightSuite, []string) error
	visit = func(suite playwrightSuite, parents []string) error {
		path := append(append([]string(nil), parents...), suite.Title)
		for _, spec := range suite.Specs {
			for _, test := range spec.Tests {
				item := ProductTest{Name: spec.Title, FullName: strings.Join(append(append([]string(nil), path...), spec.Title), " > "), File: spec.File, Project: test.ProjectName}
				if item.Name == "" {
					return fmt.Errorf("Playwright test is missing its title")
				}
				switch test.Status {
				case "expected", "unexpected", "skipped", "flaky":
					counts[test.Status]++
				default:
					return fmt.Errorf("Playwright test has unknown aggregate status %q", test.Status)
				}
				if len(test.Results) == 0 {
					if test.Status != "skipped" {
						return fmt.Errorf("Playwright test %s has no execution result", spec.Title)
					}
					item.Outcome = "skipped"
					result.Skipped++
				} else {
					last := test.Results[len(test.Results)-1]
					if test.Status == "expected" && last.Status != test.ExpectedStatus || test.Status == "unexpected" && last.Status == test.ExpectedStatus || test.Status == "flaky" && last.Status != "passed" || test.Status == "skipped" && last.Status != "skipped" {
						return fmt.Errorf("Playwright aggregate and execution statuses conflict for %s", spec.Title)
					}
					switch last.Status {
					case "passed":
						if test.Status == "flaky" {
							item.Outcome = "flaky"
							result.Flaky++
						} else if test.ExpectedStatus == "passed" && test.Status == "expected" {
							item.Outcome = "pass"
							result.Passed++
						} else {
							item.Outcome = "fail"
							result.Failed++
						}
					case "failed":
						if test.ExpectedStatus == "failed" && test.Status == "expected" {
							item.Outcome = "expected-failure"
							result.ExpectedFailures++
						} else {
							item.Outcome = "fail"
							result.Failed++
						}
						messages := append([]playwrightError(nil), last.Errors...)
						if last.Error != nil {
							messages = append(messages, *last.Error)
						}
						for _, e := range messages {
							item.AssertionFailure = item.AssertionFailure || assertionMessage(e.Message)
						}
					case "timedOut":
						item.Outcome = "timeout"
						result.Failed++
					case "interrupted":
						item.Outcome = "interrupted"
						result.Interrupted++
					case "skipped":
						item.Outcome = "skipped"
						result.Skipped++
					default:
						return fmt.Errorf("Playwright test has unknown execution status %q", last.Status)
					}
				}
				result.Tests = append(result.Tests, item)
			}
		}
		for _, child := range suite.Suites {
			if err := visit(child, path); err != nil {
				return err
			}
		}
		return nil
	}
	for _, suite := range report.Suites {
		if err := visit(suite, nil); err != nil {
			return result, err
		}
	}
	result.Total = len(result.Tests)
	for name, want := range map[string]int{"expected": *report.Stats.Expected, "unexpected": *report.Stats.Unexpected, "skipped": *report.Stats.Skipped, "flaky": *report.Stats.Flaky} {
		if want < 0 || counts[name] != want {
			return result, fmt.Errorf("Playwright %s count differs from reported tests", name)
		}
	}
	return result, nil
}

func parseJest(data []byte) (ProductSummary, error) {
	result := ProductSummary{Format: "jest", Tests: []ProductTest{}}
	var report struct {
		StartTime      *int64          `json:"startTime"`
		Success        *bool           `json:"success"`
		WasInterrupted *bool           `json:"wasInterrupted"`
		Total          *int            `json:"numTotalTests"`
		Passed         *int            `json:"numPassedTests"`
		Failed         *int            `json:"numFailedTests"`
		Pending        *int            `json:"numPendingTests"`
		Todo           int             `json:"numTodoTests"`
		RuntimeErrors  *int            `json:"numRuntimeErrorTestSuites"`
		RunError       json.RawMessage `json:"runExecError"`
		Snapshot       struct {
			Failure bool `json:"failure"`
		} `json:"snapshot"`
		TestResults []struct {
			Name       string `json:"name"`
			Status     string `json:"status"`
			Assertions []struct {
				Title           string   `json:"title"`
				FullName        string   `json:"fullName"`
				Status          string   `json:"status"`
				FailureMessages []string `json:"failureMessages"`
			} `json:"assertionResults"`
		} `json:"testResults"`
	}
	if err := json.Unmarshal(data, &report); err != nil {
		return result, err
	}
	if report.StartTime == nil || report.Success == nil || report.WasInterrupted == nil || report.Total == nil || report.Passed == nil || report.Failed == nil || report.Pending == nil || report.RuntimeErrors == nil {
		return result, fmt.Errorf("Jest report is missing complete statistics")
	}
	result.StartedAt = time.UnixMilli(*report.StartTime).UTC()
	result.SetupErrors = *report.RuntimeErrors
	if len(report.RunError) > 0 && string(report.RunError) != "null" {
		result.SetupErrors++
	}

	if *report.WasInterrupted {
		result.Interrupted++
	}
	pending, todo := 0, 0
	for _, suite := range report.TestResults {
		if suite.Status == "failed" && len(suite.Assertions) == 0 && result.SetupErrors == 0 {
			result.SetupErrors++
		}
		for _, test := range suite.Assertions {
			if test.Title == "" || test.FullName == "" {
				return result, fmt.Errorf("Jest assertion is missing its identity")
			}
			item := ProductTest{Name: test.Title, FullName: test.FullName, File: suite.Name}
			switch test.Status {
			case "passed":
				item.Outcome = "pass"
				result.Passed++
			case "failed":
				item.Outcome = "fail"
				result.Failed++
				for _, m := range test.FailureMessages {
					item.AssertionFailure = item.AssertionFailure || assertionMessage(m)
				}
			case "pending", "skipped", "disabled":
				item.Outcome = "skipped"
				result.Skipped++
				pending++
			case "todo":
				item.Outcome = "skipped"
				result.Skipped++
				todo++
			default:
				return result, fmt.Errorf("Jest assertion has unknown status %q", test.Status)
			}
			result.Tests = append(result.Tests, item)
		}
	}
	result.Total = len(result.Tests)
	if *report.Total != result.Total || *report.Passed != result.Passed || *report.Failed != result.Failed || *report.Pending != pending || report.Todo != todo || result.SetupErrors < 0 {
		return result, fmt.Errorf("Jest statistics differ from reported assertions")
	}
	if report.Snapshot.Failure && result.Failed == 0 {
		result.SetupErrors++
	}
	expectedSuccess := result.Failed == 0 && result.SetupErrors == 0 && result.Interrupted == 0
	if *report.Success != expectedSuccess {
		return result, fmt.Errorf("Jest success flag conflicts with test results")
	}
	return result, nil
}
