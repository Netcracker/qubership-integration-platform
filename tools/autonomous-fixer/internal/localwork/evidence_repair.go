package localwork

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type evidenceCorrection struct {
	Before string `json:"before"`
	After  string `json:"after"`
}

func evidenceLists(r *workcycle.Response) []*[]string {
	lists := []*[]string{&r.Evidence}
	if r.Handoff != nil {
		lists = append(lists, &r.Handoff.Evidence)
	}
	for i := range r.Scenarios {
		lists = append(lists, &r.Scenarios[i].Evidence)
	}
	for i := range r.Checks {
		lists = append(lists, &r.Checks[i].Evidence)
	}
	for i := range r.Findings {
		lists = append(lists, &r.Findings[i].Evidence)
		if r.Findings[i].Compatibility != nil {
			lists = append(lists, &r.Findings[i].Compatibility.BaselineResult.Evidence, &r.Findings[i].Compatibility.CandidateResult.Evidence)
		}
	}
	if r.Question != nil {
		lists = append(lists, &r.Question.Evidence)
	}
	if r.Documentation != nil {
		for _, claims := range [][]workcycle.DocClaim{r.Documentation.Claims, r.Documentation.SeparateFindings} {
			for i := range claims {
				lists = append(lists, &claims[i].SourceEvidence, &claims[i].DocumentationEvidence)
			}
		}
	}
	return lists
}

func correctEvidencePrefixes(original workcycle.Response, outputDir string) (workcycle.Response, []evidenceCorrection, error) {
	data, err := json.Marshal(original)
	if err != nil {
		return original, nil, err
	}
	var corrected workcycle.Response
	if err := json.Unmarshal(data, &corrected); err != nil {
		return original, nil, err
	}
	marker := string(filepath.Separator) + filepath.Base(filepath.Dir(outputDir)) + string(filepath.Separator) + filepath.Base(outputDir) + string(filepath.Separator)
	var corrections []evidenceCorrection
	seen := map[string]string{}
	for _, list := range evidenceLists(&corrected) {
		for i, ref := range *list {
			if replacement, exists := seen[ref]; exists {
				(*list)[i] = replacement
				continue
			}
			replacement := existingEvidenceWithCorrectPrefix(ref, outputDir, marker)
			if replacement == "" {
				continue
			}
			seen[ref] = replacement
			(*list)[i] = replacement
			corrections = append(corrections, evidenceCorrection{Before: ref, After: replacement})
		}
	}
	return corrected, corrections, nil
}

func existingEvidenceWithCorrectPrefix(ref, outputDir, marker string) string {
	if !filepath.IsAbs(ref) || filepath.Clean(ref) != ref || strings.Count(ref, marker) != 1 {
		return ""
	}
	if _, err := os.Lstat(ref); !os.IsNotExist(err) {
		return ""
	}
	suffix := strings.SplitN(ref, marker, 2)[1]
	replacement := filepath.Join(outputDir, suffix)
	if !inside(outputDir, replacement) || replacement == ref || hasSymlink(ref) || hasSymlink(replacement) {
		return ""
	}
	info, err := os.Lstat(replacement)
	if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
		return ""
	}
	return replacement
}

func hasSymlink(path string) bool {
	for path != filepath.Dir(path) {
		info, err := os.Lstat(path)
		if err != nil && !os.IsNotExist(err) {
			return true
		}
		if err == nil && info.Mode()&os.ModeSymlink != 0 {
			return true
		}
		path = filepath.Dir(path)
	}
	return false
}

func (d *Driver) validateOrRepairEvidence(ctx context.Context, response workcycle.Response, evidenceDir, stepDir string) (workcycle.Response, error) {
	originalErr := d.verifyArtifacts(response, evidenceDir)
	if originalErr == nil {
		return response, nil
	}
	d.failureKind = "agent_protocol"
	audit := map[string]any{"initial_error": originalErr.Error(), "model_calls": 0, "accepted": false, "bounded_passes": 1}
	if raw, err := os.ReadFile(filepath.Join(stepDir, "response-raw.txt")); err == nil {
		audit["raw_response_sha256"] = bytesDigest(raw)
	}
	repaired, err := d.withImmutableDeclarationInputs(ctx, evidenceDir, func(candidate workcycle.Snapshot, inventory map[string]string) (workcycle.Response, map[string]string, error) {
		audit["candidate_id"] = candidate.ID
		audit["original_evidence_sha256"] = inventory
		corrected, corrections, err := correctEvidencePrefixes(response, evidenceDir)
		if err != nil {
			return response, nil, err
		}
		if len(corrections) > 0 {
			audit["prefix_corrections"] = corrections
			if err := writeJSON(filepath.Join(stepDir, "evidence-corrections.json"), map[string]any{"initial_error": originalErr.Error(), "corrections": corrections, "candidate_id": candidate.ID, "method": "one deterministic correction of a missing absolute path prefix; exact current invocation suffix retained"}); err != nil {
				return response, nil, err
			}
		}
		var added map[string]string
		if err := d.verifyArtifacts(corrected, evidenceDir); err != nil {
			corrected, added, err = d.repairAggregateSummary(corrected, evidenceDir, candidate, inventory)
			if err != nil {
				return response, added, err
			}
			audit["new_declaration_indexes_sha256"] = added
			audit["method"] = "missing aggregate handoff summary replaced by a controller declaration index; measurement references and outcomes preserved"
		} else {
			audit["method"] = "existing evidence prefix correction"
		}
		return corrected, added, d.verifyArtifacts(corrected, evidenceDir)
	})
	if err != nil {
		audit["error"] = err.Error()
		return response, errors.Join(originalErr, err, writeJSON(filepath.Join(stepDir, "declaration-repair.json"), audit))
	}
	if err := writeJSON(filepath.Join(stepDir, "response-bound.json"), repaired); err != nil {
		return response, err
	}
	audit["accepted"] = true
	if err := writeJSON(filepath.Join(stepDir, "declaration-repair.json"), audit); err != nil {
		return response, err
	}
	d.failureKind = ""
	return repaired, nil
}
