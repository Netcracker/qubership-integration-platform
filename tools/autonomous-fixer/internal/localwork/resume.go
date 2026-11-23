package localwork

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) loadResume(ctx context.Context) error {
	if !filepath.IsAbs(d.Config.ResumeFrom) {
		return fmt.Errorf("resume_from requires an absolute saved report path")
	}
	engineering := d.Config.TaskKind != "documentation"
	if engineering && d.Config.ResumeMode != "" && d.Config.ResumeMode != "verification" {
		return fmt.Errorf("engineering resume supports final verification only")
	}
	if d.Config.ResumeMode != "" && d.Config.ResumeMode != "verification" && d.Config.ResumeMode != "assessment" {
		return fmt.Errorf("unsupported documentation resume mode")
	}
	read := func(path string, value any) error {
		f, e := os.Open(path)
		if e != nil {
			return e
		}
		defer f.Close()
		return Decode(f, value)
	}
	var seed workcycle.Report
	if e := read(d.Config.ResumeFrom, &seed); e != nil {
		return e
	}
	oldRoot := filepath.Dir(d.Config.ResumeFrom)
	var config Config
	if e := read(filepath.Join(oldRoot, "config.json"), &config); e != nil {
		return e
	}
	if engineering {
		if config.ResumeFrom != "" {
			return fmt.Errorf("engineering verification continuation cannot resume another continuation")
		}
		if config.TaskKind != d.Config.TaskKind || config.RuntimeBinary != d.Config.RuntimeBinary || config.RuntimeVersion != d.Config.RuntimeVersion || config.ProductCheckBinary != d.Config.ProductCheckBinary || config.ModelCatalog != d.Config.ModelCatalog || !reflect.DeepEqual(config.ReviewProfile, d.Config.ReviewProfile) || !reflect.DeepEqual(config.StaticChecks, d.Config.StaticChecks) || !reflect.DeepEqual(config.EngineeringTriage, d.Config.EngineeringTriage) {
			return fmt.Errorf("resume runtime, review profile, static checks, or routing configuration differs from checkpoint")
		}
		if err := workcycle.ValidateVerificationResume(d.Request, seed); err != nil {
			return err
		}
	}
	if !reflect.DeepEqual(config.DocumentationTriage, d.Config.DocumentationTriage) {
		return fmt.Errorf("resume documentation triage configuration differs from the checkpoint")
	}
	if !reflect.DeepEqual(entryWithoutEvidence(seed.Request.DocumentationEntry), entryWithoutEvidence(d.Request.DocumentationEntry)) {
		return fmt.Errorf("resume documentation entry differs from the checkpoint")
	}
	if d.Request.DocumentationEntry != nil {
		if d.Request.DocumentationEntry.Route == "docs-no-change" {
			return fmt.Errorf("automatic no-change has no native stage to resume")
		}
		d.Request.DocumentationEntry = seed.Request.DocumentationEntry
	}
	if config.Workspace != d.Config.Workspace || config.Profile != d.Config.Profile || !reflect.DeepEqual(seed.Request, d.Request) {
		return fmt.Errorf("resume task, workspace, model, or request differs from the checkpoint")
	}
	var packet workcontext.Packet
	if e := read(filepath.Join(oldRoot, "context.json"), &packet); e != nil {
		return e
	}
	if !reflect.DeepEqual(packet, d.Packet) {
		return fmt.Errorf("resume context differs from the checkpoint")
	}
	current, e := d.Snapshot(ctx)
	if e != nil {
		return e
	}
	if current.ID != seed.Candidate.ID || !reflect.DeepEqual(current.ChangedPaths, seed.Candidate.ChangedPaths) {
		return fmt.Errorf("resume candidate differs from the saved snapshot")
	}
	d.priorArtifacts = map[string]string{}
	d.priorEvidence = filepath.Join(oldRoot, "evidence")
	files, e := filepath.Glob(filepath.Join(oldRoot, "step-*", "evidence-sha256.json"))
	if e != nil {
		return e
	}
	if d.Config.DocumentationTriage != nil {
		files = append(files, filepath.Join(oldRoot, "controller-evidence-sha256.json"))
	}
	if len(files) == 0 {
		return fmt.Errorf("resume evidence manifests are missing")
	}
	for _, p := range files {
		b, e := os.ReadFile(p)
		if e != nil {
			return e
		}
		var hashes map[string]string
		if e = json.Unmarshal(b, &hashes); e != nil {
			return e
		}
		for path, hash := range hashes {
			if prev, ok := d.priorArtifacts[path]; ok && prev != hash {
				return fmt.Errorf("resume evidence manifests disagree")
			}
			d.priorArtifacts[path] = hash
		}
	}
	if d.Request.DocumentationEntry != nil {
		if len(d.Request.DocumentationEntry.Evidence) != 1 || d.priorArtifacts[d.Request.DocumentationEntry.Evidence[0]] != d.triageProofHash {
			return fmt.Errorf("resume documentation routing evidence differs from the validated inputs")
		}
	}
	if e = d.checkArtifacts(); e != nil {
		return e
	}
	if engineering {
		if e := d.validateEngineeringResumeArtifacts(oldRoot, seed); e != nil {
			return e
		}
		source, e := os.ReadFile(d.Config.ResumeFrom)
		if e != nil {
			return e
		}
		path := filepath.Join(d.evidence, "resume-source.json")
		if e := writeJSON(path, map[string]any{"resume_from": d.Config.ResumeFrom, "source_report_sha256": bytesDigest(source), "source_status": seed.Status, "candidate_id": seed.Candidate.ID, "mode": "engineering-verification-only", "environment_binding": "Logical environment_id is unchanged; the caller verifies the private environment configuration and deployed product identity."}); e != nil {
			return e
		}
		proof, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		if d.artifacts == nil {
			d.artifacts = map[string]string{}
		}
		d.artifacts[path] = bytesDigest(proof)
	}
	if d.Config.ResumeMode == "assessment" {
		if len(seed.Observations) != 1 {
			return fmt.Errorf("assessment replay requires exactly one recorded invocation")
		}
		o := &seed.Observations[0]
		o.Declaration = normalizeDocumentationResponse(o.Declaration, o.Before.ID)
		for _, ref := range o.Declaration.Evidence {
			if _, ok := d.priorArtifacts[ref]; !ok {
				return fmt.Errorf("recorded assessment evidence is not bound to a verified manifest")
			}
		}
		if e = writeJSON(filepath.Join(d.Config.OutputDirectory, "replay-source.json"), map[string]any{"report": d.Config.ResumeFrom, "mode": "revalidate recorded no-change assessment", "new_model_calls": 0, "candidate_id": seed.Candidate.ID}); e != nil {
			return e
		}
	}
	d.resume = &seed
	return nil
}

func (d *Driver) validateEngineeringResumeArtifacts(root string, seed workcycle.Report) error {
	steps, e := filepath.Glob(filepath.Join(root, "step-*"))
	if e != nil {
		return e
	}
	if len(steps) != len(seed.Observations) {
		return fmt.Errorf("resume stage archive differs from checkpoint history")
	}
	read := func(path string, v any) error {
		f, e := os.Open(path)
		if e != nil {
			return e
		}
		defer f.Close()
		return Decode(f, v)
	}
	catalogHash := ""
	if d.Config.Profile.Runtime == "codex" {
		b, e := os.ReadFile(d.Config.ModelCatalog)
		if e != nil {
			return e
		}
		catalogHash = bytesDigest(b)
	}
	checkRef := func(ref string) error {
		path, e := filepath.EvalSymlinks(ref)
		if e != nil {
			return e
		}
		if _, ok := d.priorArtifacts[path]; !ok {
			return fmt.Errorf("resume evidence has no recorded identity: %s", ref)
		}
		return nil
	}
	for i, step := range steps {
		observation := seed.Observations[i]
		var inv workcycle.Invocation
		var response workcycle.Response
		var profile experimentruntime.Profile
		var process experimentruntime.Result
		if e := read(filepath.Join(step, "invocation.json"), &inv); e != nil {
			return e
		}
		if e := read(filepath.Join(step, "response-bound.json"), &response); e != nil {
			return e
		}
		if e := d.validateDeclarationResolutionArchive(step, response); e != nil {
			return e
		}
		if e := read(filepath.Join(step, "profile.json"), &profile); e != nil {
			return e
		}
		if e := read(filepath.Join(step, "process.json"), &process); e != nil {
			return e
		}
		if inv.Stage != observation.Stage || inv.Lens != observation.Lens || inv.Candidate.ID != observation.Before.ID || !reflect.DeepEqual(inv.Candidate.ChangedPaths, observation.Before.ChangedPaths) || !reflect.DeepEqual(inv.Request, seed.Request) || !reflect.DeepEqual(response, observation.Declaration) {
			return fmt.Errorf("resume stage %s differs from its archived invocation or declaration", filepath.Base(step))
		}
		if profile != d.Config.ProfileForStage(observation.Stage) || process.ExitCode != 0 || process.Classification != "completed_unverified" {
			return fmt.Errorf("resume stage %s has an unexpected profile or incomplete process", filepath.Base(step))
		}
		if i == len(steps)-1 && !reflect.DeepEqual(inv.Contract, seed.Contract) {
			return fmt.Errorf("resume contract differs from the final verification assignment")
		}
		if profile.Runtime == "codex" {
			var selection experimentruntime.CatalogSelection
			if e := read(filepath.Join(step, "model-catalog.json"), &selection); e != nil {
				return e
			}
			if selection.SHA256 != catalogHash || selection.Model != profile.Model {
				return fmt.Errorf("resume model catalog differs from checkpoint")
			}
		}
		for _, refs := range evidenceLists(&response) {
			for _, ref := range *refs {
				if e := checkRef(ref); e != nil {
					return e
				}
			}
		}
	}
	for _, advice := range seed.Routing {
		if e := checkRef(advice.Evidence); e != nil {
			return e
		}
	}
	for _, run := range seed.StaticCheckRuns {
		for _, result := range run.Results {
			for _, ref := range result.Evidence {
				if e := checkRef(ref); e != nil {
					return e
				}
			}
		}
	}
	return nil
}
