package localwork

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type DeclarationBindingError struct{ Message string }

func (e *DeclarationBindingError) Error() string { return e.Message }

func bindingError(format string, args ...any) error {
	return &DeclarationBindingError{Message: fmt.Sprintf(format, args...)}
}

type ObservationDiagnostic struct {
	Capture  string `json:"capture"`
	Error    string `json:"error"`
	rejected *checkrunner.ProductObservationError
}

type ObservationCatalog struct {
	Observations []checkrunner.ProductObservation `json:"observations"`
	Diagnostics  []ObservationDiagnostic          `json:"diagnostics,omitempty"`
	captures     map[string]checkrunner.ProductRecord
	hashes       map[string]string
}

func BuildObservationCatalog(root string) (ObservationCatalog, error) {
	catalog := ObservationCatalog{Observations: []checkrunner.ProductObservation{}, captures: map[string]checkrunner.ProductRecord{}, hashes: map[string]string{}}
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || entry.Name() != "result.json" {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		var header struct {
			Version string `json:"version"`
		}
		if json.Unmarshal(data, &header) == nil && !strings.HasPrefix(header.Version, "product-check-") {
			return nil
		}
		var record checkrunner.ProductRecord
		if err = json.Unmarshal(data, &record); err == nil {
			resolved, resolveErr := filepath.EvalSymlinks(path)
			if resolveErr != nil || resolved != path || !inside(root, path) {
				err = fmt.Errorf("product capture is missing or uses a symlink outside its stage")
			} else if filepath.Clean(record.Options.OutputDirectory) != filepath.Dir(path) {
				err = fmt.Errorf("product capture directory differs from its record")
			} else {
				var observations []checkrunner.ProductObservation
				observations, err = checkrunner.ProductObservations(record)
				if err == nil {
					catalog.Observations = append(catalog.Observations, observations...)
					catalog.captures[path] = record
					catalog.hashes[path] = bytesDigest(data)
				}
			}
		}
		if err != nil {
			var rejected *checkrunner.ProductObservationError
			errors.As(err, &rejected)
			catalog.Diagnostics = append(catalog.Diagnostics, ObservationDiagnostic{Capture: path, Error: err.Error(), rejected: rejected})
		}
		return nil
	})
	return catalog, err
}

func resultPointers(response *workcycle.Response) []*workcycle.Result {
	var results []*workcycle.Result
	for i := range response.Scenarios {
		results = append(results, &response.Scenarios[i])
	}
	for i := range response.Checks {
		results = append(results, &response.Checks[i])
	}
	for i := range response.Findings {
		if compatibility := response.Findings[i].Compatibility; compatibility != nil {
			results = append(results, &compatibility.BaselineResult, &compatibility.CandidateResult)
		}
	}
	return results
}

func (d *Driver) bindProductObservations(response *workcycle.Response, catalog ObservationCatalog) error {
	data, err := json.Marshal(response)
	if err != nil {
		return err
	}
	var bound workcycle.Response
	if err = json.Unmarshal(data, &bound); err != nil {
		return err
	}
	byID := map[string]checkrunner.ProductObservation{}
	for _, observation := range catalog.Observations {
		byID[observation.ID] = observation
	}
	for _, result := range resultPointers(&bound) {
		for _, ref := range result.Evidence {
			for _, diagnostic := range catalog.Diagnostics {
				if ref == diagnostic.Capture || filepath.Dir(ref) == filepath.Dir(diagnostic.Capture) {
					return fmt.Errorf("invalid product capture %s: %s", diagnostic.Capture, diagnostic.Error)
				}
			}
		}
	}
	selectedCaptures := map[string]bool{}
	for _, result := range resultPointers(&bound) {
		if len(result.ObservationIDs) == 0 {
			referenced := map[string]checkrunner.ProductRecord{}
			for _, ref := range result.Evidence {
				if capture, ok := catalog.captures[ref]; ok {
					referenced[ref] = capture
				}
			}
			if len(referenced) == 0 {
				if len(result.ProductTests) > 0 || result.Outcome == "measured" {
					if len(catalog.Observations) == 0 {
						return fmt.Errorf("result %s has no intact current-stage product measurement", result.ID)
					}
					return bindingError("result %s has no observation IDs or referenced product capture", result.ID)
				}
				continue
			}
			selected, err := selectProductTests(*result, referenced)
			if err != nil {
				return err
			}
			for _, item := range selected {
				if item.test.Outcome == "skipped" {
					continue
				}
				for _, observation := range catalog.Observations {
					if observation.Capture == item.ref && observation.TestIndex == item.index {
						result.ObservationIDs = append(result.ObservationIDs, observation.ID)
					}
				}
			}
			if len(result.ObservationIDs) == 0 {
				return bindingError("result %s selects no executed product tests", result.ID)
			}
		}
		expected := "pass"
		seen := map[string]bool{}
		for _, id := range result.ObservationIDs {
			observation, ok := byID[id]
			if !ok {
				for _, diagnostic := range catalog.Diagnostics {
					if diagnostic.rejected != nil && diagnostic.rejected.References(id) {
						return fmt.Errorf("invalid product capture %s: %s", diagnostic.Capture, diagnostic.Error)
					}
				}
				if len(catalog.Observations) == 0 {
					return fmt.Errorf("result %s has no intact current-stage product measurement", result.ID)
				}
				return bindingError("result %s observation ID %q is absent from the current stage catalog", result.ID, id)
			}
			if seen[id] {
				return bindingError("result %s repeats observation ID %q", result.ID, id)
			}
			seen[id] = true
			if observation.Outcome != "pass" && observation.Outcome != "fail" {
				return bindingError("result %s observation ID %q did not execute", result.ID, id)
			}
			if observation.Outcome == "fail" {
				expected = "fail"
			}
			selectedCaptures[observation.Capture] = true
			for _, evidence := range observation.Evidence {
				found := false
				for _, ref := range result.Evidence {
					found = found || ref == evidence
				}
				if !found {
					result.Evidence = append(result.Evidence, evidence)
				}
			}
		}
		result.Outcome, result.ProductTests = expected, nil
	}
	if len(bound.Evidence) == 0 {
		seen := map[string]bool{}
		for _, result := range resultPointers(&bound) {
			if len(result.ObservationIDs) == 0 {
				continue
			}
			for _, ref := range result.Evidence {
				if !seen[ref] {
					bound.Evidence = append(bound.Evidence, ref)
					seen[ref] = true
				}
			}
		}
	}
	for path := range selectedCaptures {
		current, err := os.ReadFile(path)
		if err != nil || bytesDigest(current) != catalog.hashes[path] {
			return fmt.Errorf("product capture changed after catalog creation: %s", path)
		}
		if err = checkrunner.ValidateProductCapture(catalog.captures[path], filepath.Dir(path)); err != nil {
			return fmt.Errorf("invalid product capture: %w", err)
		}
	}
	*response = bound
	return nil
}
