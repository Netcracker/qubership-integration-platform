package localwork

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type declarationIndex struct {
	Kind                     string             `json:"kind"`
	IsMeasurement            bool               `json:"is_measurement"`
	Note                     string             `json:"note"`
	CandidateID              string             `json:"candidate_id"`
	InputDeclarationSHA256   string             `json:"input_declaration_sha256"`
	ClaimedSummary           string             `json:"claimed_summary"`
	ClaimedScenarios         []workcycle.Result `json:"claimed_scenarios"`
	ClaimedChecks            []workcycle.Result `json:"claimed_checks"`
	ExistingEvidenceSHA256   map[string]string  `json:"existing_evidence_sha256"`
	MissingSummaryReferences []string           `json:"missing_summary_references"`
}

func decodeEngineeringDeclaration(raw string, response *workcycle.Response) (string, error) {
	parse := func(text string) error {
		if !strings.HasPrefix(strings.TrimSpace(text), "{") {
			return fmt.Errorf("stage result must be a JSON object")
		}
		return Decode(strings.NewReader(text), response)
	}
	originalErr := parse(raw)
	if originalErr == nil {
		return "", nil
	}
	text := strings.TrimSpace(raw)
	first, body, ok := strings.Cut(text, "\n")
	first = strings.TrimSuffix(first, "\r")
	if !ok || (first != "```json" && first != "```") {
		return "", originalErr
	}
	body = strings.TrimSpace(body)
	if !strings.HasSuffix(body, "\n```") {
		return "", originalErr
	}
	body = strings.TrimSuffix(body, "\n```")
	*response = workcycle.Response{}
	if err := parse(body); err != nil {
		return "", errors.Join(originalErr, err)
	}
	return originalErr.Error(), nil
}

func stageEvidenceInventory(root string) (map[string]string, error) {
	inventory := map[string]string{}
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("declaration repair requires regular evidence files: %s", path)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		inventory[path] = bytesDigest(data)
		return nil
	})
	return inventory, err
}

func (d *Driver) withImmutableDeclarationInputs(ctx context.Context, evidenceDir string, repair func(workcycle.Snapshot, map[string]string) (workcycle.Response, map[string]string, error)) (workcycle.Response, error) {
	before, err := d.Snapshot(ctx)
	if err != nil {
		return workcycle.Response{}, err
	}
	if err := d.checkArtifacts(); err != nil {
		return workcycle.Response{}, err
	}
	inventory, err := stageEvidenceInventory(evidenceDir)
	if err != nil {
		return workcycle.Response{}, err
	}
	response, added, repairErr := repair(before, inventory)
	after, snapshotErr := d.Snapshot(ctx)
	if snapshotErr == nil && before.ID != after.ID {
		snapshotErr = fmt.Errorf("candidate changed during declaration repair")
	}
	current, inventoryErr := stageEvidenceInventory(evidenceDir)
	if inventoryErr == nil {
		expected := make(map[string]string, len(inventory)+len(added))
		for path, sum := range inventory {
			expected[path] = sum
		}
		for path, sum := range added {
			if _, exists := expected[path]; exists {
				inventoryErr = fmt.Errorf("declaration repair attempted to replace evidence: %s", path)
				break
			}
			expected[path] = sum
		}
		if inventoryErr == nil {
			if len(current) != len(expected) {
				inventoryErr = fmt.Errorf("evidence inventory changed during declaration repair")
			}
			for path, want := range expected {
				if current[path] != want {
					inventoryErr = fmt.Errorf("evidence changed during declaration repair: %s", path)
					break
				}
			}
		}
	}
	return response, errors.Join(repairErr, snapshotErr, inventoryErr, d.checkArtifacts())
}

func typedMeasurementDeclaration(response workcycle.Response) (workcycle.Response, error) {
	typed := response
	typed.Evidence = nil
	typed.Handoff = nil
	if response.Documentation != nil {
		return typed, fmt.Errorf("engineering declaration recovery does not replace documentation claims")
	}
	for _, results := range [][]workcycle.Result{response.Scenarios, response.Checks} {
		seen := map[string]bool{}
		for _, result := range results {
			if result.ID == "" || seen[result.ID] || (result.Outcome != "pass" && result.Outcome != "fail") || len(result.Evidence) == 0 {
				return typed, fmt.Errorf("declaration repair requires complete typed measurement claims")
			}
			seen[result.ID] = true
		}
	}
	return typed, nil
}

func summaryReference(path string) bool {
	name := strings.ToLower(filepath.Base(path))
	extension := filepath.Ext(name)
	return (extension == ".json" || extension == ".txt") && (strings.Contains(name, "summary") || strings.Contains(name, "observations"))
}

func (d *Driver) repairAggregateSummary(response workcycle.Response, evidenceDir string, candidate workcycle.Snapshot, inventory map[string]string) (workcycle.Response, map[string]string, error) {
	typed, err := typedMeasurementDeclaration(response)
	if err != nil {
		return response, nil, err
	}
	if err := d.verifyArtifacts(typed, evidenceDir); err != nil {
		return response, nil, fmt.Errorf("declaration repair cannot replace missing measurement evidence: %w", err)
	}
	if len(response.Scenarios)+len(response.Checks) == 0 {
		return response, nil, fmt.Errorf("declaration repair has no typed measurements")
	}
	if response.Handoff == nil || strings.TrimSpace(response.Handoff.Summary) == "" {
		return response, nil, fmt.Errorf("declaration repair has no recorded handoff summary")
	}
	handoffRefs := map[string]bool{}
	for _, ref := range response.Handoff.Evidence {
		handoffRefs[ref] = true
	}
	missing := []string{}
	seen := map[string]bool{}
	for _, refs := range [][]string{response.Evidence, response.Handoff.Evidence} {
		for _, ref := range refs {
			if seen[ref] {
				continue
			}
			seen[ref] = true
			if err := d.verifyArtifacts(workcycle.Response{Evidence: []string{ref}}, evidenceDir); err == nil {
				continue
			}
			if !handoffRefs[ref] || !summaryReference(ref) || !filepath.IsAbs(ref) || filepath.Clean(ref) != ref || !inside(evidenceDir, ref) || hasSymlink(ref) {
				return response, nil, fmt.Errorf("invalid reference is not an eligible current-stage handoff summary: %s", ref)
			}
			if _, err := os.Lstat(ref); !os.IsNotExist(err) {
				return response, nil, fmt.Errorf("declaration repair does not replace existing or unreadable evidence: %s", ref)
			}
			missing = append(missing, ref)
		}
	}
	if len(missing) == 0 {
		return response, nil, fmt.Errorf("declaration repair found no missing handoff summary")
	}
	original, err := json.Marshal(response)
	if err != nil {
		return response, nil, err
	}
	proofHashes := map[string]string{}
	for _, refs := range evidenceLists(&typed) {
		for _, ref := range *refs {
			resolved, err := filepath.EvalSymlinks(ref)
			if err != nil {
				return response, nil, err
			}
			if inventory[resolved] == "" {
				return response, nil, fmt.Errorf("measurement is absent from the frozen evidence inventory: %s", ref)
			}
			proofHashes[ref] = inventory[resolved]
		}
	}
	index := declarationIndex{Kind: "controller-declaration-index", IsMeasurement: false, Note: "This index records the original claims and checksums of existing evidence. It adds no measurement, test execution, or verified outcome.", CandidateID: candidate.ID, InputDeclarationSHA256: bytesDigest(original), ClaimedSummary: response.Handoff.Summary, ClaimedScenarios: response.Scenarios, ClaimedChecks: response.Checks, ExistingEvidenceSHA256: proofHashes, MissingSummaryReferences: missing}
	data, err := json.MarshalIndent(index, "", "  ")
	if err != nil {
		return response, nil, err
	}
	data = append(data, '\n')
	path := filepath.Join(evidenceDir, "controller-declaration-index.json")
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return response, nil, err
	}
	_, writeErr := file.Write(data)
	closeErr := file.Close()
	if err := errors.Join(writeErr, closeErr); err != nil {
		return response, map[string]string{path: bytesDigest(data)}, err
	}
	var corrected workcycle.Response
	if err := json.Unmarshal(original, &corrected); err != nil {
		return response, map[string]string{path: bytesDigest(data)}, err
	}
	replacements := map[string]bool{}
	for _, ref := range missing {
		replacements[ref] = true
	}
	for _, refs := range []*[]string{&corrected.Evidence, &corrected.Handoff.Evidence} {
		for i, ref := range *refs {
			if replacements[ref] {
				(*refs)[i] = path
			}
		}
	}
	return corrected, map[string]string{path: bytesDigest(data)}, nil
}
