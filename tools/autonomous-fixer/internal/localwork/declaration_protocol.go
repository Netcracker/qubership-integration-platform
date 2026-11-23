package localwork

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type declarationBindingPatch struct {
	Bindings []struct {
		Kind           string   `json:"kind"`
		ID             string   `json:"id"`
		ObservationIDs []string `json:"observation_ids"`
	} `json:"bindings"`
	MetadataReferences []evidenceCorrection `json:"metadata_references"`
}

type declarationInputInventory struct {
	Candidate        workcycle.Snapshot `json:"candidate"`
	Evidence         map[string]string  `json:"evidence_sha256"`
	DeclarationFiles map[string]string  `json:"declaration_files_sha256,omitempty"`
}

func cloneDeclaration(response workcycle.Response) (workcycle.Response, error) {
	data, err := json.Marshal(response)
	if err != nil {
		return response, err
	}
	var copy workcycle.Response
	err = json.Unmarshal(data, &copy)
	return copy, err
}

func (d *Driver) resolveDeclarationBindings(ctx context.Context, inv workcycle.Invocation, response workcycle.Response, evidenceDir, stepDir string) (workcycle.Response, error) {
	if d.Config.ProductCheckBinary == "" {
		return response, nil
	}
	original := response
	prefixed, corrections, err := correctEvidencePrefixes(response, evidenceDir)
	if err != nil {
		return response, err
	}
	if len(corrections) > 0 {
		if err := writeJSON(filepath.Join(stepDir, "evidence-corrections.json"), map[string]any{"corrections": corrections, "method": "one deterministic correction of a missing absolute path prefix; exact current invocation suffix retained"}); err != nil {
			return response, err
		}
		response = prefixed
	}
	catalog, err := BuildObservationCatalog(evidenceDir)
	if err != nil {
		d.failureKind = "agent_protocol"
		return response, err
	}
	if err := writeJSON(filepath.Join(stepDir, "observation-catalog.json"), catalog); err != nil {
		return response, err
	}
	corrected, err := cloneDeclaration(response)
	if err != nil {
		return response, err
	}
	err = d.bindProductObservations(&corrected, catalog)
	if err == nil {
		return corrected, d.recordObservationProjection(stepDir, original, corrected)
	}
	var bindingErr *DeclarationBindingError
	if !errors.As(err, &bindingErr) {
		d.failureKind = "agent_protocol"
		return response, err
	}
	d.failureKind = "agent_protocol"
	initialErr := err
	typed := response
	typed.Evidence = nil
	typed.Handoff = nil
	if proofErr := d.verifyArtifacts(typed, evidenceDir); proofErr != nil {
		return response, fmt.Errorf("declaration correction cannot replace missing typed proofs: %w", proofErr)
	}
	if len(catalog.Observations) == 0 || len(response.Scenarios)+len(response.Checks) == 0 {
		return response, fmt.Errorf("declaration correction has no existing result measurements: %w", initialErr)
	}
	allowed, err := d.unresolvedDeclarationBindings(response, catalog)
	if err != nil {
		return response, err
	}
	if len(allowed) == 0 {
		return response, fmt.Errorf("binding correction has no unresolved scenario or check: %w", initialErr)
	}
	declarationFiles, err := declarationFileInventory(stepDir)
	if err != nil {
		return response, err
	}
	correctionDir := filepath.Join(stepDir, "declaration-correction")
	if err := os.Mkdir(correctionDir, 0700); err != nil {
		return response, errors.Join(initialErr, err)
	}
	audit := map[string]any{"initial_error": initialErr.Error(), "bounded_calls": 1, "accepted": false}
	if err := writeJSON(filepath.Join(correctionDir, "diagnostic.json"), audit); err != nil {
		return response, err
	}
	repaired, err := d.withImmutableDeclarationInputs(ctx, evidenceDir, func(candidate workcycle.Snapshot, inventory map[string]string) (workcycle.Response, map[string]string, error) {
		inputs := declarationInputInventory{Candidate: candidate, Evidence: inventory, DeclarationFiles: declarationFiles}
		if err := writeJSON(filepath.Join(correctionDir, "input-inventory.json"), inputs); err != nil {
			return response, nil, err
		}
		patch, err := d.requestDeclarationPatch(ctx, inv, response, catalog, inputs, initialErr, correctionDir, allowed)
		if err != nil {
			return response, nil, err
		}
		corrected, err := applyDeclarationPatch(response, patch, catalog, inventory, allowed)
		if err != nil {
			return response, nil, err
		}
		if err := d.bindProductObservations(&corrected, catalog); err != nil {
			return response, nil, err
		}
		return corrected, nil, nil
	})
	after, snapshotErr := d.Snapshot(ctx)
	inventory, inventoryErr := stageEvidenceInventory(evidenceDir)
	currentDeclarationFiles, declarationErr := declarationFileInventory(stepDir)
	if declarationErr == nil && !sameStringMap(declarationFiles, currentDeclarationFiles) {
		declarationErr = fmt.Errorf("original stage declaration files changed during correction")
	}
	outputErr := writeJSON(filepath.Join(correctionDir, "output-inventory.json"), declarationInputInventory{Candidate: after, Evidence: inventory, DeclarationFiles: currentDeclarationFiles})
	err = errors.Join(err, snapshotErr, inventoryErr, declarationErr, outputErr)
	if err != nil {
		audit["error"] = err.Error()
		return response, errors.Join(initialErr, err, writeJSON(filepath.Join(correctionDir, "diagnostic.json"), audit))
	}
	audit["accepted"] = true
	if err := writeJSON(filepath.Join(correctionDir, "diagnostic.json"), audit); err != nil {
		return response, err
	}
	if err := d.recordObservationProjection(stepDir, original, repaired); err != nil {
		return response, err
	}
	d.failureKind = ""
	return repaired, nil
}

func (d *Driver) recordObservationProjection(stepDir string, original, projected workcycle.Response) error {
	return writeJSON(filepath.Join(stepDir, "observation-projection.json"), map[string]any{"method": "controller derives product outcomes from captured observations", "original_scenarios": original.Scenarios, "original_checks": original.Checks, "projected_scenarios": projected.Scenarios, "projected_checks": projected.Checks})
}

func applyDeclarationPatch(original workcycle.Response, patch declarationBindingPatch, catalog ObservationCatalog, inventory map[string]string, allowed map[string]bool) (workcycle.Response, error) {
	corrected, err := cloneDeclaration(original)
	if err != nil {
		return original, err
	}
	ids := map[string]bool{}
	for _, observation := range catalog.Observations {
		ids[observation.ID] = true
	}
	seen := map[string]bool{}
	for _, binding := range patch.Bindings {
		key := binding.Kind + ":" + binding.ID
		if !allowed[key] {
			return original, fmt.Errorf("correction cannot replace a resolved result: %s", key)
		}
		if seen[key] || len(binding.ObservationIDs) == 0 {
			return original, fmt.Errorf("correction requires one nonempty binding per result: %s", key)
		}
		seen[key] = true
		var results []workcycle.Result
		switch binding.Kind {
		case "scenario":
			results = corrected.Scenarios
		case "check":
			results = corrected.Checks
		default:
			return original, fmt.Errorf("unknown declaration result kind %q", binding.Kind)
		}
		found := false
		for i := range results {
			if results[i].ID == binding.ID {
				if found {
					return original, fmt.Errorf("ambiguous declaration result %s", key)
				}
				found = true
				selected := map[string]bool{}
				for _, id := range binding.ObservationIDs {
					if !ids[id] || selected[id] {
						return original, fmt.Errorf("unknown or repeated observation ID %q", id)
					}
					selected[id] = true
				}
				results[i].ObservationIDs = append([]string{}, binding.ObservationIDs...)
				results[i].ProductTests = nil
			}
		}
		if !found {
			return original, fmt.Errorf("correction cannot add a result: %s", key)
		}
	}
	if len(seen) != len(allowed) {
		return original, fmt.Errorf("correction must resolve every offered binding")
	}
	replacements := map[string]string{}
	for _, replacement := range patch.MetadataReferences {
		if _, err := os.Lstat(replacement.Before); !os.IsNotExist(err) {
			return original, fmt.Errorf("metadata correction cannot replace an existing proof: %s", replacement.Before)
		}
		if replacement.Before == replacement.After || replacements[replacement.Before] != "" || inventory[replacement.After] == "" {
			return original, fmt.Errorf("metadata correction must name one existing proof")
		}
		info, err := os.Lstat(replacement.After)
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return original, fmt.Errorf("metadata correction requires a nonempty existing proof")
		}
		replacements[replacement.Before] = replacement.After
	}
	lists := []*[]string{&corrected.Evidence}
	if corrected.Handoff != nil {
		lists = append(lists, &corrected.Handoff.Evidence)
	}
	used := map[string]bool{}
	for _, refs := range lists {
		for i, ref := range *refs {
			if replacement := replacements[ref]; replacement != "" {
				(*refs)[i] = replacement
				used[ref] = true
			}
		}
	}
	for ref := range replacements {
		if !used[ref] {
			return original, fmt.Errorf("metadata correction cannot add a reference: %s", ref)
		}
	}
	if len(patch.Bindings)+len(patch.MetadataReferences) == 0 {
		return original, fmt.Errorf("declaration correction is empty")
	}
	return corrected, nil
}

func (d *Driver) requestDeclarationPatch(ctx context.Context, inv workcycle.Invocation, response workcycle.Response, catalog ObservationCatalog, inputs declarationInputInventory, diagnostic error, dir string, allowed map[string]bool) (declarationBindingPatch, error) {
	var patch declarationBindingPatch
	profile := d.Config.ProfileForStage(inv.Stage)
	if profile.Runtime != "codex" {
		return patch, fmt.Errorf("declaration correction requires a runtime with observable disabled tools")
	}
	metadata := map[string]any{"stage": inv.Stage, "contract": inv.Contract, "scenarios": response.Scenarios, "checks": response.Checks, "metadata_evidence": response.Evidence, "catalog": catalog, "existing_files_sha256": inputs.Evidence, "diagnostic": diagnostic.Error(), "allowed_bindings": allowed}
	if response.Handoff != nil {
		metadata["handoff_evidence"] = response.Handoff.Evidence
	}
	data, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		return patch, err
	}
	prompt := "Declaration binding correction\nReturn one JSON patch with bindings and metadata_references arrays. Each binding has kind (scenario or check), id (an existing result ID), and observation_ids (existing catalog IDs). Each metadata reference has before and after, replacing a declared top-level or handoff path with an existing file. Patch only unresolved result IDs in allowed_bindings. Preserve all resolved results. Correct the diagnostic using only the supplied immutable observations. Preserve every result, contract, criterion, procedure, environment, dataset, and measured outcome. The controller derives outcomes. Do not execute commands, use tools, inspect files, edit source or evidence, or request another measurement. If the supplied observations cannot establish every offered binding, return empty arrays; the controller will keep the stage unresolved. Do not guess a mapping to satisfy the schema. No commentary.\n\n" + string(data)
	if err := os.WriteFile(filepath.Join(dir, "prompt.txt"), []byte(prompt), 0600); err != nil {
		return patch, err
	}
	if err := writeJSON(filepath.Join(dir, "profile.json"), profile); err != nil {
		return patch, err
	}
	home := filepath.Join(dir, "home")
	workspace := filepath.Join(dir, "metadata")
	for _, path := range []string{home, workspace} {
		if err := os.Mkdir(path, 0700); err != nil {
			return patch, err
		}
	}
	cfg := d.Config
	cfg.Profile = profile
	cfg.Workspace = workspace
	command, err := runtimeCommand(cfg, prompt, filepath.Join(dir, "prompt.txt"))
	if err != nil {
		return patch, err
	}
	for i := range command.Args {
		if command.Args[i] == "danger-full-access" {
			command.Args[i] = "read-only"
		}
	}
	args := command.Args[:len(command.Args)-1]
	args = append(args, "--skip-git-repo-check")
	for _, feature := range []string{"shell_tool", "unified_exec", "apps", "browser_use", "browser_use_external", "computer_use", "code_mode_host", "image_generation", "view_image", "skill_search", "sleep_tool"} {
		args = append(args, "--disable", feature)
	}
	schemaPath := filepath.Join(dir, "response-schema.json")
	if err := writeJSON(schemaPath, declarationCorrectionSchema(response, catalog, inputs.Evidence, allowed)); err != nil {
		return patch, err
	}
	args = append(args, "-c", "project_doc_max_bytes=0", "-c", "web_search=\"disabled\"", "--output-schema", schemaPath, "-")
	command.Args = args
	env, err := d.environmentFor(home, profile)
	if err != nil {
		return patch, err
	}
	selection, err := experimentruntime.PinCatalog(&command, profile, d.Config.ModelCatalog, home)
	if err != nil {
		return patch, err
	}
	if err := writeJSON(filepath.Join(dir, "model-catalog.json"), selection); err != nil {
		return patch, err
	}
	if err := writeJSON(filepath.Join(dir, "command.json"), command); err != nil {
		return patch, err
	}
	if err := copyLogin(d.Config.LoginFile, filepath.Join(home, ".codex", "auth.json")); err != nil {
		return patch, err
	}
	defer os.Remove(filepath.Join(home, ".codex", "auth.json"))
	process, runErr := experimentruntime.RunWithProgress(ctx, command, dir, env, nil, nil, experimentruntime.ProgressOptions{Runtime: profile.Runtime, Stage: "declaration-correction", LatestPath: filepath.Join(d.Config.OutputDirectory, "runtime-progress.json")})
	if err := writeJSON(filepath.Join(dir, "process.json"), process); err != nil {
		return patch, err
	}
	if runErr != nil {
		return patch, runErr
	}
	stream, err := os.ReadFile(process.StdoutPath)
	if err != nil {
		return patch, err
	}
	events, err := experimentruntime.ParseEvents(strings.NewReader(string(stream)), profile.Runtime)
	if recordErr := writeJSON(filepath.Join(dir, "runtime-events.json"), events); recordErr != nil {
		return patch, recordErr
	}
	if err != nil {
		return patch, err
	}
	if err := validateDeclarationOnlyEvents(strings.NewReader(string(stream))); err != nil {
		return patch, err
	}
	if !events.CompletionValid {
		return patch, fmt.Errorf("declaration correction did not complete")
	}
	final, err := experimentruntime.FinalText(strings.NewReader(string(stream)), profile.Runtime)
	if err != nil {
		return patch, err
	}
	if err := os.WriteFile(filepath.Join(dir, "response-raw.txt"), []byte(final), 0600); err != nil {
		return patch, err
	}
	if err := Decode(strings.NewReader(final), &patch); err != nil {
		return patch, err
	}
	if patch.Bindings == nil || patch.MetadataReferences == nil {
		return patch, fmt.Errorf("declaration correction requires bindings and metadata_references arrays")
	}
	return patch, writeJSON(filepath.Join(dir, "response.json"), patch)
}

func validateDeclarationOnlyEvents(reader io.Reader) error {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), 8*1024*1024)
	for scanner.Scan() {
		var event struct {
			Type string `json:"type"`
			Item struct {
				Type string `json:"type"`
			} `json:"item"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &event); err != nil {
			return err
		}
		switch event.Type {
		case "thread.started", "turn.started", "turn.completed", "turn.failed", "error":
		case "item.started", "item.updated", "item.completed":
			if event.Item.Type != "agent_message" && event.Item.Type != "reasoning" && event.Item.Type != "error" {
				return fmt.Errorf("declaration correction attempted a tool or command: %s", event.Item.Type)
			}
		default:
			return fmt.Errorf("unrecognized declaration correction event %q", event.Type)
		}
	}
	return scanner.Err()
}

func (d *Driver) recordDeclarationResolution(response workcycle.Response, evidenceDir, stepDir string) error {
	original, err := os.ReadFile(filepath.Join(stepDir, "response-bound.json"))
	if err != nil {
		return err
	}
	beforePath := filepath.Join(stepDir, "response-before-resolution.json")
	beforeFile, err := os.OpenFile(beforePath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, writeErr := beforeFile.Write(original)
	if err := errors.Join(writeErr, beforeFile.Close()); err != nil {
		return err
	}
	if err := writeJSON(filepath.Join(stepDir, "response-bound.json"), response); err != nil {
		return err
	}
	archive := map[string]string{}
	for _, name := range []string{"response-before-resolution.json", "response-bound.json", "observation-catalog.json", "observation-projection.json", "evidence-corrections.json", "declaration-repair.json", "response.json", "response-raw.txt"} {
		path := filepath.Join(stepDir, name)
		data, err := os.ReadFile(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return err
		}
		archive[path] = bytesDigest(data)
	}
	correctionDir := filepath.Join(stepDir, "declaration-correction")
	if info, err := os.Stat(correctionDir); err == nil && info.IsDir() {
		files, err := stageEvidenceInventory(correctionDir)
		if err != nil {
			return err
		}
		for path, hash := range files {
			if inside(filepath.Join(correctionDir, "home"), path) {
				continue
			}
			archive[path] = hash
		}
	}
	path := filepath.Join(evidenceDir, "controller-response-resolution.json")
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		return fmt.Errorf("response resolution path already exists: %s", path)
	}
	if err := writeJSON(path, map[string]any{"original_bound_sha256": bytesDigest(original), "effective_response": response, "archive_hashes": archive}); err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if d.artifacts == nil {
		d.artifacts = map[string]string{}
	}
	d.artifacts[path] = bytesDigest(data)
	return nil
}

func declarationFileInventory(stepDir string) (map[string]string, error) {
	files := map[string]string{}
	for _, name := range []string{"response.json", "response-bound.json", "response-raw.txt", "observation-catalog.json", "response-binding.json", "runtime-events.json", "process.json", "invocation.json", "profile.json", "prompt.txt"} {
		path := filepath.Join(stepDir, name)
		info, err := os.Lstat(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if !info.Mode().IsRegular() {
			return nil, fmt.Errorf("stage declaration input is not a regular file: %s", path)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		files[path] = bytesDigest(data)
	}
	return files, nil
}

func sameStringMap(first, second map[string]string) bool {
	if len(first) != len(second) {
		return false
	}
	for key, value := range first {
		if second[key] != value {
			return false
		}
	}
	return true
}

func declarationCorrectionSchema(response workcycle.Response, catalog ObservationCatalog, inventory map[string]string, allowed map[string]bool) map[string]any {
	object := func(properties map[string]any, required ...string) map[string]any {
		return map[string]any{"type": "object", "properties": properties, "required": required, "additionalProperties": false}
	}
	observations := []string{}
	for _, observation := range catalog.Observations {
		observations = append(observations, observation.ID)
	}
	variants := []any{}
	for _, group := range []struct {
		kind    string
		results []workcycle.Result
	}{{"scenario", response.Scenarios}, {"check", response.Checks}} {
		ids := []string{}
		for _, result := range group.results {
			if allowed[group.kind+":"+result.ID] {
				ids = append(ids, result.ID)
			}
		}
		if len(ids) == 0 {
			continue
		}
		variants = append(variants, object(map[string]any{
			"kind":            map[string]any{"type": "string", "enum": []string{group.kind}},
			"id":              map[string]any{"type": "string", "enum": ids},
			"observation_ids": map[string]any{"type": "array", "items": map[string]any{"type": "string", "enum": observations}, "minItems": 1, "maxItems": len(observations)},
		}, "kind", "id", "observation_ids"))
	}
	metadataRefs := append([]string{}, response.Evidence...)
	if response.Handoff != nil {
		metadataRefs = append(metadataRefs, response.Handoff.Evidence...)
	}
	before := []string{}
	for _, ref := range metadataRefs {
		if _, err := os.Lstat(ref); os.IsNotExist(err) {
			before = append(before, ref)
		}
	}
	after := []string{}
	for path := range inventory {
		info, err := os.Stat(path)
		if err == nil && info.Mode().IsRegular() && info.Size() > 0 {
			after = append(after, path)
		}
	}
	sort.Strings(after)
	metadata := map[string]any{"type": "array", "items": object(map[string]any{"before": map[string]any{"type": "string"}, "after": map[string]any{"type": "string"}}, "before", "after"), "maxItems": 0}
	if len(before) > 0 && len(after) > 0 {
		metadata["items"] = object(map[string]any{"before": map[string]any{"type": "string", "enum": before}, "after": map[string]any{"type": "string", "enum": after}}, "before", "after")
		metadata["maxItems"] = len(before)
	}
	return object(map[string]any{"bindings": map[string]any{"type": "array", "items": map[string]any{"anyOf": variants}, "minItems": 0, "maxItems": len(allowed)}, "metadata_references": metadata}, "bindings", "metadata_references")
}

func (d *Driver) unresolvedDeclarationBindings(response workcycle.Response, catalog ObservationCatalog) (map[string]bool, error) {
	allowed := map[string]bool{}
	for _, group := range []struct {
		kind    string
		results []workcycle.Result
	}{{"scenario", response.Scenarios}, {"check", response.Checks}} {
		for _, result := range group.results {
			probe := workcycle.Response{Scenarios: []workcycle.Result{result}}
			err := d.bindProductObservations(&probe, catalog)
			if err == nil {
				continue
			}
			var bindingErr *DeclarationBindingError
			if !errors.As(err, &bindingErr) {
				return nil, err
			}
			allowed[group.kind+":"+result.ID] = true
		}
	}
	return allowed, nil
}
