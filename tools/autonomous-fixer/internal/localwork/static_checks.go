package localwork

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) StaticChecks(ctx context.Context, candidate workcycle.Snapshot) (workcycle.StaticCheckRun, error) {
	run := workcycle.StaticCheckRun{CandidateID: candidate.ID}
	d.lastStaticCheck = nil
	d.failureKind = ""
	if len(d.Config.StaticChecks) == 0 {
		return run, nil
	}
	if e := d.checkArtifacts(); e != nil {
		return run, e
	}
	before, e := d.Snapshot(ctx)
	if e != nil {
		return run, e
	}
	if before.ID != candidate.ID {
		return run, fmt.Errorf("static check candidate changed")
	}
	home := filepath.Join(d.Config.OutputDirectory, "check-home")
	if e = os.MkdirAll(home, 0700); e != nil {
		return run, e
	}
	env, e := d.environmentFor(home, d.Config.Profile)
	if e != nil {
		return run, e
	}
	if d.checkInputs == nil {
		d.checkInputs = &checkrunner.InputCache{}
	}
	for _, recipe := range d.Config.StaticChecks {
		record, e := checkrunner.RunCached(ctx, d.Config.Workspace, candidate.ID, d.evidence, recipe, env, d.checkInputs)
		d.lastStaticCheck = &record
		if e != nil {
			return run, e
		}
		outcome := "fail"
		if record.ExitCode == 0 {
			outcome = "pass"
		}
		refs := []string{record.Path}
		if d.artifacts == nil {
			d.artifacts = map[string]string{}
		}
		for _, path := range []string{record.Path, record.Stdout, record.Stderr} {
			b, e := os.ReadFile(path)
			if e != nil {
				return run, e
			}
			sum := bytesDigest(b)
			if prev, ok := d.artifacts[path]; ok && prev != sum {
				return run, fmt.Errorf("static check evidence changed")
			}
			d.artifacts[path] = sum
			if len(b) > 0 && path != record.Path {
				refs = append(refs, path)
			}
		}
		if record.ReusedFrom != "" {
			run.Reused = append(run.Reused, recipe.ID)
		}
		run.Results = append(run.Results, workcycle.Result{ID: recipe.ID, Outcome: outcome, Evidence: refs})
	}
	after, e := d.Snapshot(ctx)
	if e != nil {
		return run, e
	}
	if after.ID != candidate.ID {
		return run, fmt.Errorf("static checks changed source files")
	}
	return run, nil
}
