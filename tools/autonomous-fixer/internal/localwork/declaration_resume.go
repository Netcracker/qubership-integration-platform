package localwork

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

// Resume validates corrected declarations against their recorded inputs and outputs.
func (d *Driver) validateDeclarationResolutionArchive(step string, bound workcycle.Response) error {
	auditPath := filepath.Join(filepath.Dir(step), "evidence", filepath.Base(step), "controller-response-resolution.json")
	data, err := os.ReadFile(auditPath)
	if os.IsNotExist(err) {
		if _, recorded := d.priorArtifacts[auditPath]; recorded {
			return fmt.Errorf("recorded declaration resolution is missing")
		}
		return nil
	}
	if err != nil {
		return err
	}
	if d.priorArtifacts[auditPath] != bytesDigest(data) {
		return fmt.Errorf("declaration resolution has no matching recorded identity")
	}
	var audit struct {
		Original  string             `json:"original_bound_sha256"`
		Effective workcycle.Response `json:"effective_response"`
		Archive   map[string]string  `json:"archive_hashes"`
	}
	if err = json.Unmarshal(data, &audit); err != nil {
		return err
	}
	if !reflect.DeepEqual(audit.Effective, bound) {
		return fmt.Errorf("resolved declaration differs from the bound response")
	}
	beforePath := filepath.Join(step, "response-before-resolution.json")
	before, err := os.ReadFile(beforePath)
	if err != nil || bytesDigest(before) != audit.Original || audit.Archive[beforePath] != audit.Original {
		return fmt.Errorf("declaration resolution original response changed")
	}
	if audit.Archive[filepath.Join(step, "response-bound.json")] == "" {
		return fmt.Errorf("declaration resolution omits its bound response")
	}
	for path, want := range audit.Archive {
		resolved, err := filepath.EvalSymlinks(path)
		if err != nil || resolved != path || !inside(step, path) || inside(filepath.Join(step, "home"), path) || inside(filepath.Join(step, "declaration-correction", "home"), path) {
			return fmt.Errorf("declaration archive reference escaped its stage: %s", path)
		}
		contents, err := os.ReadFile(path)
		if err != nil || bytesDigest(contents) != want {
			return fmt.Errorf("declaration archive changed: %s", path)
		}
	}
	return nil
}
