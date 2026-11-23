package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func regularPath(root, name string, missing bool) error {
	rel, e := filepath.Rel(root, name)
	if e != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return fmt.Errorf("candidate path escapes its root")
	}
	p := root
	parts := append([]string{""}, strings.Split(rel, string(filepath.Separator))...)
	for _, part := range parts {
		p = filepath.Join(p, part)
		st, e := os.Lstat(p)
		if os.IsNotExist(e) && missing {
			continue
		}
		if e != nil {
			return e
		}
		if st.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("candidate path contains a symlink")
		}
	}
	return nil
}
func restore(ctx context.Context, prepared, runDir, source, workspace string) error {
	for _, p := range []string{prepared, runDir, source, workspace} {
		if !filepath.IsAbs(p) {
			return fmt.Errorf("restore requires absolute paths")
		}
	}
	var plan docgithub.Plan
	var report workcycle.Report
	if e := read(filepath.Join(prepared, "plan.json"), &plan); e != nil {
		return e
	}
	if e := plan.Validate(); e != nil {
		return e
	}
	if e := read(filepath.Join(runDir, "report.json"), &report); e != nil {
		return e
	}
	if report.Request.TaskID != fmt.Sprintf("%s#%d:documentation", plan.Repository, plan.IssueNumber) || (report.Status != "docs-no-change" && report.Status != "docs-ready-for-pr") {
		return fmt.Errorf("restore requires a completed matching documentation task")
	}
	files := map[string][]byte{}
	for _, name := range report.Candidate.ChangedPaths {
		if !strings.HasPrefix(name, "help/docs/") || filepath.IsAbs(name) || filepath.Clean(name) != name || strings.Contains(name, "\\") {
			return fmt.Errorf("candidate modifies a path outside help")
		}
		path := filepath.Join(runDir, "candidate-files", name)
		if e := regularPath(filepath.Join(runDir, "candidate-files"), path, true); e != nil {
			return e
		}
		st, e := os.Lstat(path)
		if os.IsNotExist(e) {
			files[name] = nil
			continue
		}
		if e != nil {
			return e
		}
		if !st.Mode().IsRegular() {
			return fmt.Errorf("candidate is not a regular file")
		}
		b, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		files[name] = b
	}
	if e := git(ctx, source, "worktree", "add", "--detach", workspace, plan.BaseSHA); e != nil {
		return e
	}
	for name, body := range files {
		dest := filepath.Join(workspace, name)
		if e := regularPath(workspace, dest, true); e != nil {
			return e
		}
		if body == nil {
			if e := os.Remove(dest); e != nil {
				return e
			}
		} else {
			if e := os.MkdirAll(filepath.Dir(dest), 0755); e != nil {
				return e
			}
			if e := os.WriteFile(dest, body, 0644); e != nil {
				return e
			}
		}
	}
	_, e := docgithub.ValidateCandidate(ctx, plan, runDir, workspace)
	return e
}
