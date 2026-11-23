package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func validateHostedRuntime(ctx context.Context, cfg localwork.Config) error {
	if !filepath.IsAbs(cfg.RuntimeBinary) || cfg.RuntimeVersion == "" {
		return fmt.Errorf("hosted work requires an absolute OpenCode runtime path and runtime-version")
	}
	if strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY")) == "" {
		return fmt.Errorf("hosted work requires OPENROUTER_API_KEY")
	}
	if err := experimentruntime.ValidateHostedOpenCodeProfile(cfg.Profile); err != nil {
		return err
	}
	command := exec.CommandContext(ctx, cfg.RuntimeBinary, "--version")
	command.Env = []string{"PATH=" + os.Getenv("PATH"), "LANG=C.UTF-8"}
	version, err := command.Output()
	if err != nil {
		return fmt.Errorf("read OpenCode version: %w", err)
	}
	if strings.TrimSpace(string(version)) != cfg.RuntimeVersion {
		return fmt.Errorf("OpenCode version differs from runtime-version")
	}
	return nil
}

func controllerCredentials() [][]byte {
	var secrets [][]byte
	for _, key := range []string{"OPENROUTER_API_KEY", "GITHUB_TOKEN", "GH_TOKEN"} {
		value := strings.TrimSpace(os.Getenv(key))
		if value != "" {
			encoded, _ := json.Marshal(value)
			secrets = append(secrets, []byte(value), encoded[1:len(encoded)-1])
		}
	}
	return secrets
}

func exportRejectedDiff(ctx context.Context, prepared, runDir, workspace, output string) error {
	for _, name := range []string{prepared, runDir, workspace, output} {
		if !filepath.IsAbs(name) {
			return fmt.Errorf("rejected-diff requires absolute prepared, run, workspace, and output paths")
		}
	}
	var plan docgithub.Plan
	if err := read(filepath.Join(prepared, "plan.json"), &plan); err != nil {
		return err
	}
	if err := plan.Validate(); err != nil {
		return err
	}
	reportPath := filepath.Join(runDir, "report.json")
	if err := regularPath(runDir, reportPath, false); err != nil {
		return err
	}
	info, err := os.Lstat(reportPath)
	if err != nil || !info.Mode().IsRegular() {
		return fmt.Errorf("rejected-diff requires a regular work report")
	}
	var report workcycle.Report
	if err := read(reportPath, &report); err != nil {
		return err
	}
	if report.Workflow != string(doccycle.AuthorStage) || report.Request.TaskID != fmt.Sprintf("%s#%d:documentation", plan.Repository, plan.IssueNumber) ||
		(report.Status != "rejected" && report.Status != "verification-blocked") {
		return fmt.Errorf("rejected-diff requires a failed hosted documentation run for this issue")
	}
	patch, err := localwork.RejectedDocumentationPatch(ctx, workspace, plan.BaseSHA, report.Candidate)
	if err != nil || len(patch) == 0 {
		return err
	}
	for _, secret := range controllerCredentials() {
		if bytes.Contains(patch, secret) {
			return fmt.Errorf("rejected documentation diff contains a controller credential")
		}
	}
	file, err := os.OpenFile(output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, writeErr := file.Write(patch)
	closeErr := file.Close()
	if err = errors.Join(writeErr, closeErr); err != nil {
		_ = os.Remove(output)
	}
	return err
}

func exportHandoff(ctx context.Context, plan docgithub.Plan, output, workspace, destination string) error {
	output, err := filepath.EvalSymlinks(output)
	if err != nil {
		return err
	}
	destination = filepath.Clean(destination)
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		return fmt.Errorf("handoff destination must be a new directory")
	}
	tmp, err := os.MkdirTemp(filepath.Dir(destination), ".docs-handoff-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)

	secrets := controllerCredentials()
	copyFile := func(rel string, optional bool) error {
		source := filepath.Join(output, rel)
		if err := regularPath(output, source, false); err != nil {
			if optional && os.IsNotExist(err) {
				return nil
			}
			return err
		}
		info, err := os.Lstat(source)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("handoff source must be a regular file")
		}
		content, err := os.ReadFile(source)
		if err != nil {
			return err
		}
		for _, secret := range secrets {
			if bytes.Contains(content, secret) || bytes.Contains([]byte(rel), secret) {
				return fmt.Errorf("handoff contains a controller credential")
			}
		}
		target := filepath.Join(tmp, rel)
		if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return err
		}
		return os.WriteFile(target, content, 0600)
	}
	for _, name := range []string{
		"feedback.json", "task/task.md", "task/plan.json", "task/packet.json", "task/jev-report.json",
		"work/config.json", "work/context.json", "work/report.json", "work/receipt.json",
	} {
		if err := copyFile(name, false); err != nil {
			return err
		}
	}
	runDir := filepath.Join(output, "work")
	targetRun := filepath.Join(tmp, "work")
	var cfg localwork.Config
	if err := read(filepath.Join(targetRun, "config.json"), &cfg); err != nil {
		return err
	}
	if !cfg.Hosted || cfg.Workflow != string(doccycle.AuthorStage) || cfg.ResumeFrom != "" || cfg.OutputDirectory != runDir || cfg.TaskDirectory != filepath.Join(output, "task") {
		return fmt.Errorf("handoff requires a fresh hosted documentation run")
	}
	var receipt struct {
		Evidence map[string]string `json:"evidence_sha256"`
		Prior    map[string]string `json:"prior_evidence_sha256"`
	}
	if err := read(filepath.Join(targetRun, "receipt.json"), &receipt); err != nil {
		return err
	}
	if len(receipt.Prior) != 0 {
		return fmt.Errorf("handoff requires evidence from the current run")
	}
	for name := range receipt.Evidence {
		rel, err := filepath.Rel(runDir, name)
		if err != nil || !strings.HasPrefix(rel, "evidence"+string(filepath.Separator)) {
			return fmt.Errorf("handoff receipt refers to a path outside evidence")
		}
		if err := copyFile(filepath.Join("work", rel), false); err != nil {
			return err
		}
	}
	var report workcycle.Report
	if err := read(filepath.Join(targetRun, "report.json"), &report); err != nil {
		return err
	}
	for _, name := range report.Candidate.ChangedPaths {
		if !strings.HasPrefix(name, "help/docs/") || filepath.Clean(name) != name || strings.Contains(name, "\\") {
			return fmt.Errorf("handoff candidate modifies a path outside help")
		}
		if err := copyFile(filepath.Join("work", "candidate-files", name), true); err != nil {
			return err
		}
	}
	if len(report.Observations) != 0 {
		if len(report.Observations) != 1 || report.Observations[0].Stage != doccycle.AuthorStage {
			return fmt.Errorf("handoff requires one documentation author observation")
		}
		dir := filepath.Join("work", fmt.Sprintf("step-0001-%s", doccycle.AuthorStage))
		for _, name := range []string{"profile.json", agentsignature.IdentityFilename} {
			if err := copyFile(filepath.Join(dir, name), false); err != nil {
				return err
			}
		}
		if err := copyFile(filepath.Join("work", openrouterusage.Filename), false); err != nil {
			return err
		}
	}
	if _, err := docgithub.ValidateCandidate(ctx, plan, targetRun, workspace); err != nil {
		return err
	}
	return os.Rename(tmp, destination)
}
