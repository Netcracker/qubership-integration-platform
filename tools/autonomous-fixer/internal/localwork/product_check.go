package localwork

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func validateProductCheckConfig(cfg Config) error {
	if cfg.ProductCheckBinary == "" {
		return nil
	}
	if cfg.TaskKind == "documentation" {
		return fmt.Errorf("product_check_binary is only supported for engineering work")
	}
	info, e := os.Stat(cfg.ProductCheckBinary)
	if !filepath.IsAbs(cfg.ProductCheckBinary) || e != nil || !info.Mode().IsRegular() || info.Mode()&0111 == 0 {
		return fmt.Errorf("product_check_binary must be an absolute executable file")
	}
	return nil
}
func ProductCheckGuidance(binary, evidence string) string {
	if binary == "" {
		return ""
	}
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	return fmt.Sprintf(`
Use the configured product-check adapter for every Playwright or Jest test result you claim. It captures actual child status and checks a fresh JSON report; a successful shell or tee exit is not a test result. Keep the existing task environment, dataset, and provisioning settings. Choose a NEW output directory below the current evidence directory for each command. The adapter prints machine-generated observations with immutable IDs, measured outcomes, test identities, and capture paths. For each typed scenario or check, copy its supporting IDs into observation_ids, set outcome to measured, and leave evidence empty. Select each relevant observation separately when tests have the same name across captures. The controller derives the outcome and proof paths from those IDs. Never manufacture an ID or use a rejected capture as a measurement. Choosing the tests that establish a scenario remains your responsibility.
Playwright recipe (replace WORKSPACE, SPEC, and test names with the actual accepted tests):
%s --cwd WORKSPACE/e2e --output %s --report-format playwright --min-tests 1 --require-test 'exact required test title' -- npx --no-install playwright test --project=ui --no-deps --workers=1 --reporter=line,json SPEC
Use the actual project required by the contract, including vscode when applicable. Preserve needed project dependencies; --no-deps is only for checks whose fixtures are already ready. The runner sets the JSON report output variables. Prefer an explicit test artifact output directory below the same new capture directory when the framework permits it.
Jest recipe:
%s --cwd WORKSPACE/ui --output %s --report-format jest --require-test 'exact required test full name' -- npm test -- --json --outputFile={report} --maxWorkers=4 TEST_FILE
For a baseline or mutation that must fail, use --expect tests-fail and one --expect-failed-test 'exact unique test title or full name' for each intended assertion failure. The adapter returns expected-test-failure while preserving a nonzero child status; declare that measured baseline result as fail, never as final pass. Setup errors, timeouts, skipped selections, and unnamed failures do not satisfy this expectation. A negative framework report still needs inspection to establish that its assertion matches the reported defect.
Use argv rather than a shell pipeline. If a shell is unavoidable, --shell runs bash with pipefail and exposes PRODUCT_CHECK_REPORT. The adapter cannot intercept raw commands executed outside it. Source/image identity, fresh data, and adequate scenario coverage remain your responsibility.
`, quote(binary), quote(filepath.Join(evidence, "product-playwright-NEW")), quote(binary), quote(filepath.Join(evidence, "product-jest-NEW")))
}
func (d *Driver) validateProductResults(response workcycle.Response, root string, stage workcycle.Stage) error {
	captures := map[string]checkrunner.ProductRecord{}
	seen := map[string]bool{}
	typedProofs := map[string]bool{}
	for _, result := range resultPointers(&response) {
		for _, ref := range result.Evidence {
			typedProofs[ref] = true
		}
	}
	for _, refs := range evidenceLists(&response) {
		for _, ref := range *refs {
			if seen[ref] || filepath.Ext(ref) != ".json" {
				continue
			}
			seen[ref] = true
			data, e := os.ReadFile(ref)
			if e != nil {
				return e
			}
			var header struct {
				Version string `json:"version"`
			}
			if json.Unmarshal(data, &header) != nil || !strings.HasPrefix(header.Version, "product-check-") {
				continue
			}
			if !inside(root, ref) {
				return fmt.Errorf("product capture is outside current stage evidence")
			}
			var capture checkrunner.ProductRecord
			if e = json.Unmarshal(data, &capture); e != nil {
				return e
			}
			if !typedProofs[ref] && (capture.Verdict == "rejected" || capture.Error != "") {
				continue
			}
			if e = checkrunner.ValidateProductCapture(capture, filepath.Dir(ref)); e != nil {
				return fmt.Errorf("invalid product capture: %w", e)
			}
			for path, sum := range capture.OutputSHA256 {
				if !inside(root, path) {
					return fmt.Errorf("product capture artifact is outside current stage evidence")
				}
				if d.artifacts == nil {
					d.artifacts = map[string]string{}
				}
				if prev, ok := d.artifacts[path]; ok && prev != sum {
					return fmt.Errorf("product capture artifact changed")
				}
				d.artifacts[path] = sum
			}
			captures[ref] = capture
		}
	}
	covered := map[string]map[int]bool{}
	validateResult := func(result workcycle.Result, finalResult bool) error {
		referenced := map[string]checkrunner.ProductRecord{}
		for _, ref := range result.Evidence {
			if capture, ok := captures[ref]; ok {
				referenced[ref] = capture
			}
		}
		if len(referenced) == 0 {
			if len(result.ProductTests) > 0 || len(result.ObservationIDs) > 0 {
				return bindingError("result %s has product_tests but no product capture", result.ID)
			}
			return nil
		}
		selected, err := selectProductTests(result, referenced)
		if err != nil {
			return err
		}
		expected := "pass"
		executed := 0
		for _, item := range selected {
			if item.test.Outcome != "pass" && item.test.Outcome != "fail" && item.test.Outcome != "skipped" {
				return fmt.Errorf("selected product test has unsupported outcome %s", item.test.Outcome)
			}
			if item.test.Outcome == "pass" || item.test.Outcome == "fail" {
				executed++
			}
			if item.test.Outcome == "fail" {
				expected = "fail"
			}
		}
		if executed == 0 {
			return fmt.Errorf("result %s selects no executed product tests", result.ID)
		}
		if result.Outcome != expected {
			return bindingError("typed result %s=%s conflicts with selected product tests (%s)", result.ID, result.Outcome, expected)
		}
		if finalResult && result.Outcome == "fail" {
			for _, item := range selected {
				if item.test.Outcome == "fail" {
					if covered[item.ref] == nil {
						covered[item.ref] = map[int]bool{}
					}
					covered[item.ref][item.index] = true
				}
			}
		}
		return nil
	}
	for _, result := range append(append([]workcycle.Result(nil), response.Scenarios...), response.Checks...) {
		if e := validateResult(result, true); e != nil {
			return e
		}
	}
	for _, finding := range response.Findings {
		if finding.Compatibility != nil {
			for _, result := range []workcycle.Result{finding.Compatibility.BaselineResult, finding.Compatibility.CandidateResult} {
				if e := validateResult(result, false); e != nil {
					return e
				}
			}
		}
	}
	if stage == workcycle.VerifyStage && response.Question == nil {
		catalog, err := BuildObservationCatalog(root)
		if err != nil {
			return err
		}
		for ref, capture := range catalog.captures {
			if capture.Summary.Failed > 0 {
				captures[ref] = capture
			}
		}
		for ref, capture := range captures {
			for i, test := range capture.Summary.Tests {
				if test.Outcome == "fail" && !covered[ref][i] {
					return fmt.Errorf("failed product test %q has no failed final scenario or check", test.FullName)
				}
			}
		}
	}
	return d.checkArtifacts()
}

type observedProductTest struct {
	ref   string
	index int
	test  checkrunner.ProductTest
}

func selectProductTests(result workcycle.Result, captures map[string]checkrunner.ProductRecord) ([]observedProductTest, error) {
	var selected []observedProductTest
	refs := make([]string, 0, len(captures))
	for ref := range captures {
		refs = append(refs, ref)
	}
	sort.Strings(refs)
	if len(result.ObservationIDs) > 0 {
		byID := map[string]observedProductTest{}
		for _, ref := range refs {
			capture := captures[ref]
			observations, err := checkrunner.ProductObservations(capture)
			if err != nil {
				return nil, err
			}
			for _, observation := range observations {
				byID[observation.ID] = observedProductTest{ref, observation.TestIndex, capture.Summary.Tests[observation.TestIndex]}
			}
		}
		for _, id := range result.ObservationIDs {
			observation, ok := byID[id]
			if !ok {
				return nil, bindingError("result %s observation ID %q is absent from referenced captures", result.ID, id)
			}
			if observation.test.Outcome != "pass" && observation.test.Outcome != "fail" {
				return nil, bindingError("result %s observation ID %q did not execute", result.ID, id)
			}
			selected = append(selected, observation)
		}
	} else if len(result.ProductTests) == 0 {
		for _, ref := range refs {
			capture := captures[ref]
			for i, test := range capture.Summary.Tests {
				selected = append(selected, observedProductTest{ref, i, test})
			}
		}
	} else {
		for _, name := range result.ProductTests {
			var matches []observedProductTest
			for _, ref := range refs {
				capture := captures[ref]
				for i, test := range capture.Summary.Tests {
					if name == test.Name || name == test.FullName {
						matches = append(matches, observedProductTest{ref, i, test})
					}
				}
			}
			if len(matches) != 1 {
				return nil, bindingError("result %s selector %q is absent or ambiguous", result.ID, name)
			}
			if matches[0].test.Outcome != "pass" && matches[0].test.Outcome != "fail" {
				return nil, bindingError("result %s selector %q did not execute", result.ID, name)
			}
			selected = append(selected, matches[0])
		}
	}
	return selected, nil
}
