package localwork

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func verificationAssignment(input workcycle.AgentAssessmentRequest) string {
	cases, _ := verificationCases(input)
	data, _ := json.MarshalIndent(cases, "", "  ")
	assignment := fmt.Sprintf(`Independently rerun the required cases on fresh dataset B in the existing product environment. Confirm that each consumer runs this candidate and record the observed product identity. Preserve the existing cluster, namespace, configuration, and services. Use %q as a suffix for new data where the scenario supports it. Record the actual data or fixture you exercised, including how you established freshness.

For each case, record the entry path, the behavior you observed, and the evidence produced by the current run. Include any healthy neighboring behavior required by the task. Inspect the implemented assertions or live output to confirm each case separately. If one test covers several cases, name the distinct assertion or observation for each. An earlier report, a static check, or a summary of what a test intends to cover cannot establish a fresh live result. Block approval when a required case is missing, partially exercised, skipped, or runs against the wrong product.

Treat the listed verification cases as the required scope. The controller records the results and evidence for managed checks in managed_results. A passing result satisfies the configured check for this candidate. Leave managed checks to the controller, including their configured scope. Report an optional check or an unrelated external failure in the summary. Base any blocking finding on a required case, candidate identity, or environment prerequisite.

Required verification cases:
%s`, input.VerificationToken, data)
	if len(input.VerificationRecheck) > 0 {
		assignment += "\nRecheck only the cases listed above. The controller retained the other cases from the first attempt. Jev flagged a possible mismatch between each listed requirement and the previous observation in verification_recheck. Use fresh evidence to decide whether the listed cases pass."
	}
	return assignment
}

func verificationCases(input workcycle.AgentAssessmentRequest) ([]workcycle.VerificationScenario, error) {
	cases, err := workcycle.VerificationCases(input.Request)
	if err != nil || len(input.VerificationRecheck) == 0 {
		return cases, err
	}
	wanted := map[string]bool{}
	for _, row := range input.VerificationRecheck {
		wanted[row.CaseID] = true
	}
	var selected []workcycle.VerificationScenario
	for _, c := range cases {
		if wanted[c.ID] {
			selected = append(selected, c)
		}
	}
	if len(selected) != len(input.VerificationRecheck) {
		return nil, fmt.Errorf("recheck contains an unknown or repeated verification case")
	}
	return selected, nil
}

const verificationDisposition = `Add a coverage array to the final JSON. For each required case, provide case_id, observed, and evidence. Copy the required case ID. Use observed for a concise account of the actual entry, assertion result, and data or fixture. Use evidence for paths to the raw output, report, screenshot, or trace from this attempt. Mention issue images and other task references in observed or summary. Cite fresh files in the evidence directory or checkout with absolute or relative paths. Copy relevant fresh files from other locations into the evidence directory. When using product-check, cite its result.json from a capture created in the evidence directory or checkout. Shared evidence can support several cases. For a blocked or failing verdict, include any coverage you established and name absent or failing cases in the summary or findings.`

func verificationCoverageSchema() map[string]any {
	return map[string]any{"type": "array", "items": map[string]any{
		"type": "object", "additionalProperties": false, "required": []string{"case_id", "observed", "evidence"},
		"properties": map[string]any{
			"case_id":  map[string]string{"type": "string"},
			"observed": map[string]string{"type": "string"},
			"evidence": map[string]any{"type": "array", "items": map[string]string{"type": "string"}},
		},
	}}
}

func (d *Driver) validateVerification(input workcycle.AgentAssessmentRequest, coverage []workcycle.VerificationResult, evidence string, started time.Time) (map[string]string, error) {
	cases, err := verificationCases(input)
	if err != nil {
		return nil, err
	}
	wanted := map[string]bool{}
	for _, c := range cases {
		wanted[c.ID] = true
	}
	hashes := map[string]string{}
	checked := map[string]bool{}
	var tracked map[string]bool
	normalized := append([]workcycle.VerificationResult(nil), coverage...)
	for i := range normalized {
		row := &normalized[i]
		if !wanted[row.CaseID] {
			return nil, fmt.Errorf("unknown or repeated verification case %q", row.CaseID)
		}
		delete(wanted, row.CaseID)
		if strings.TrimSpace(row.Observed) == "" || len(row.Evidence) == 0 {
			return nil, fmt.Errorf("verification case %q has no observation or evidence", row.CaseID)
		}
		row.Evidence, row.References, row.Corrections = nil, nil, nil
		for _, ref := range coverage[i].Evidence {
			reference, err := d.verificationTaskReference(evidence, ref)
			if err != nil {
				return nil, fmt.Errorf("verification case %q: %w", row.CaseID, err)
			}
			if reference != "" {
				row.References = append(row.References, reference)
				recordReferenceCorrection(row, ref, reference)
				continue
			}
			path, err := d.verificationEvidencePath(evidence, ref)
			if err != nil {
				return nil, fmt.Errorf("verification case %q: %w", row.CaseID, err)
			}
			recordReferenceCorrection(row, ref, path)
			if inside(d.Config.Workspace, path) {
				if tracked == nil {
					tracked = d.verificationSourcePaths(input.Candidate)
				}
				name, _ := filepath.Rel(d.Config.Workspace, path)
				if tracked[name] {
					row.References = append(row.References, path)
					continue
				}
			}
			if !checked[path] {
				if err = readVerificationEvidence(path, started, hashes); err != nil {
					return nil, fmt.Errorf("verification case %q: %w", row.CaseID, err)
				}
				checked[path] = true
			}
			row.Evidence = append(row.Evidence, path)
		}
		if len(row.Evidence) == 0 {
			return nil, fmt.Errorf("verification case %q has no fresh evidence", row.CaseID)
		}
	}
	var missing []string
	for _, c := range cases {
		if wanted[c.ID] {
			missing = append(missing, c.ID)
		}
	}
	if len(missing) > 0 {
		return nil, fmt.Errorf("missing verification cases %s", strings.Join(missing, ", "))
	}
	copy(coverage, normalized)
	return hashes, nil
}

func recordReferenceCorrection(row *workcycle.VerificationResult, original, resolved string) {
	if filepath.IsAbs(original) && original != resolved {
		row.Corrections = append(row.Corrections, workcycle.VerificationReferenceCorrection{Original: original, Resolved: resolved})
	}
}

func (d *Driver) verificationSourcePaths(candidate workcycle.Snapshot) map[string]bool {
	paths := map[string]bool{}
	tracked, err := git(context.Background(), d.Config.Workspace, "ls-files", "--cached", "-z")
	if err == nil {
		for _, name := range strings.Split(string(tracked), "\x00") {
			paths[name] = true
		}
	}
	for _, name := range candidate.ChangedPaths {
		paths[name] = true
	}
	return paths
}

func (d *Driver) verificationTaskReference(evidence, ref string) (string, error) {
	root := d.Config.TaskDirectory
	name := filepath.Clean(ref)
	switch {
	case filepath.IsAbs(name) && inside(root, name):
		name, _ = filepath.Rel(root, name)
	case filepath.IsAbs(name) && inside(evidence, name):
		if _, err := os.Lstat(name); !os.IsNotExist(err) {
			return "", nil
		}
		name, _ = filepath.Rel(evidence, name)
	case !filepath.IsAbs(name):
		for _, folder := range []string{evidence, d.Config.Workspace} {
			if _, err := os.Lstat(filepath.Join(folder, name)); !os.IsNotExist(err) {
				return "", nil
			}
		}
	default:
		return "", nil
	}
	for _, file := range d.Packet.Files {
		if file.Root != "task" || file.Path != name {
			continue
		}
		actual := filepath.Join(root, name)
		resolvedRoot, rootErr := filepath.EvalSymlinks(root)
		resolved, fileErr := filepath.EvalSymlinks(actual)
		if rootErr != nil || fileErr != nil || !inside(resolvedRoot, resolved) {
			return "", fmt.Errorf("cannot resolve task reference %q within the task directory", name)
		}
		hash, err := hashEvidence(resolved, io.Discard)
		if err != nil || hash != file.SHA256 {
			return "", fmt.Errorf("task reference %q differs from the frozen input", name)
		}
		return resolved, nil
	}
	return "", nil
}

func (d *Driver) verificationEvidencePath(evidence, ref string) (string, error) {
	if strings.TrimSpace(ref) == "" {
		return "", fmt.Errorf("empty verification evidence path")
	}
	path := ref
	if !filepath.IsAbs(path) {
		path = filepath.Join(evidence, ref)
		if _, err := os.Stat(path); os.IsNotExist(err) {
			path = filepath.Join(d.Config.Workspace, ref)
		}
	}
	resolved, err := filepath.EvalSymlinks(path)
	if os.IsNotExist(err) {
		stage := filepath.Base(evidence)
		parent := filepath.Dir(evidence)
		run := filepath.Base(filepath.Dir(parent))
		sep := string(filepath.Separator)
		marker := sep + filepath.Join(run, filepath.Base(parent), stage) + sep
		if corrected := existingEvidenceWithCorrectPrefix(path, evidence, marker); corrected != "" {
			resolved, err = corrected, nil
		}
	}
	if err != nil {
		return "", fmt.Errorf("cannot resolve verification evidence %q: %w", ref, err)
	}
	login, _ := filepath.EvalSymlinks(d.Config.LoginFile)
	if resolved == login || inside(filepath.Join(d.Config.Workspace, ".git"), resolved) ||
		(!inside(evidence, resolved) && !inside(d.Config.Workspace, resolved)) {
		return "", fmt.Errorf("verification evidence %q must be in the checkout or current evidence directory", ref)
	}
	return resolved, nil
}

func readVerificationEvidence(path string, started time.Time, hashes map[string]string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 || info.ModTime().UnixMilli() < started.UnixMilli() {
		return fmt.Errorf("evidence %q must be a nonempty file from this verification attempt", path)
	}
	if filepath.Ext(path) == ".json" {
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		decoder := json.NewDecoder(bytes.NewReader(data))
		for documents := 0; ; documents++ {
			var doc json.RawMessage
			err = decoder.Decode(&doc)
			if err == io.EOF && documents > 0 {
				err = nil
				break
			}
			if err != nil {
				return fmt.Errorf("evidence %q contains invalid JSON", path)
			}
			if err = validateVerificationReport(doc, path, started, hashes); err != nil {
				return err
			}
		}
		hashes[path] = bytesDigest(data)
	} else {
		hashes[path], err = hashEvidence(path, io.Discard)
	}
	return err
}

func validateVerificationReport(data []byte, path string, started time.Time, hashes map[string]string) error {
	var header struct {
		Version string          `json:"version"`
		Suites  json.RawMessage `json:"suites"`
		Stats   json.RawMessage `json:"stats"`
		Total   json.RawMessage `json:"numTotalTests"`
		Results json.RawMessage `json:"testResults"`
	}
	if json.Unmarshal(data, &header) != nil {
		return nil
	}
	if strings.HasPrefix(header.Version, "product-check-") {
		var record checkrunner.ProductRecord
		if err := json.Unmarshal(data, &record); err != nil {
			return err
		}
		if err := checkrunner.ValidateProductCapture(record, filepath.Dir(path)); err != nil {
			return fmt.Errorf("invalid product capture %q: %w", path, err)
		}
		if record.Verdict != "tests-passed" || record.Process.StartedAt.UnixMilli() < started.UnixMilli() {
			return fmt.Errorf("product capture %q did not pass in this verification attempt", path)
		}
		for artifact, hash := range record.OutputSHA256 {
			hashes[artifact] = hash
		}
		return nil
	}
	var format string
	switch {
	case header.Suites != nil && header.Stats != nil:
		format = "playwright"
	case header.Total != nil && header.Results != nil:
		format = "jest"
	default:
		return nil
	}
	summary, err := checkrunner.ParseProductReport(format, data)
	if err != nil {
		return fmt.Errorf("invalid verification report %q: %w", path, err)
	}
	if summary.StartedAt.UnixMilli() < started.UnixMilli() || summary.Passed == 0 || summary.Failed+summary.Flaky+summary.Interrupted+summary.ExpectedFailures+summary.SetupErrors > 0 {
		return fmt.Errorf("test report %q has no clean passing result from this verification attempt", path)
	}
	return nil
}

func (d *Driver) archiveVerificationEvidence(root string, coverage []workcycle.VerificationResult, hashes map[string]string) (map[string]string, error) {
	archived := map[string]string{}
	if len(hashes) == 0 {
		return archived, nil
	}
	dir := filepath.Join(root, "verification")
	if err := os.Mkdir(dir, 0700); err != nil {
		return nil, err
	}
	paths := map[string]string{}
	for path, hash := range hashes {
		copy := filepath.Join(dir, bytesDigest([]byte(path))[:16]+"-"+filepath.Base(path))
		file, err := os.OpenFile(copy, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err != nil {
			return nil, err
		}
		sum, err := hashEvidence(path, file)
		closeErr := file.Close()
		if err != nil || closeErr != nil || sum != hash {
			_ = os.Remove(copy)
			if err != nil {
				return nil, err
			}
			if closeErr != nil {
				return nil, closeErr
			}
			return nil, fmt.Errorf("verification evidence %q changed before archiving", path)
		}
		paths[path], archived[copy] = copy, hash
	}
	if d.artifacts == nil {
		d.artifacts = map[string]string{}
	}
	for path, hash := range archived {
		d.artifacts[path] = hash
	}
	for _, row := range coverage {
		for i, ref := range row.Evidence {
			row.Evidence[i] = paths[ref]
		}
	}
	return archived, nil
}
