package localwork

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type DocumentationTriage struct {
	PacketPath string                   `json:"packet_path"`
	ReportPath string                   `json:"report_path"`
	Policy     *doctriage.RoutingPolicy `json:"policy,omitempty"`
}

type Config struct {
	Workflow              string                           `json:"workflow,omitempty"`
	Hosted                bool                             `json:"hosted,omitempty"`
	ReviewParallelism     int                              `json:"review_parallelism,omitempty"`
	ProductCheckBinary    string                           `json:"product_check_binary,omitempty"`
	StaticChecks          []checkrunner.Recipe             `json:"static_checks,omitempty"`
	ReviewProfile         *experimentruntime.Profile       `json:"review_profile,omitempty"`
	AuthorHelperProfile   *experimentruntime.Profile       `json:"author_helper_profile,omitempty"`
	EngineeringTriage     *EngineeringTriage               `json:"engineering_triage,omitempty"`
	DocumentationTriage   *DocumentationTriage             `json:"documentation_triage,omitempty"`
	ResumeFrom            string                           `json:"resume_from,omitempty"`
	ResumeMode            string                           `json:"resume_mode,omitempty"`
	TaskKind              string                           `json:"task_kind,omitempty"`
	Workspace             string                           `json:"workspace"`
	TaskDirectory         string                           `json:"task_directory"`
	OutputDirectory       string                           `json:"output_directory"`
	RuntimeBinary         string                           `json:"runtime_binary"`
	RuntimeVersion        string                           `json:"runtime_version"`
	Profile               experimentruntime.Profile        `json:"profile"`
	ModelCatalog          string                           `json:"model_catalog,omitempty"`
	LoginFile             string                           `json:"login_file,omitempty"`
	Context               workcontext.Selection            `json:"context"`
	TaskID                string                           `json:"task_id"`
	RequiredCriteria      []string                         `json:"required_criteria"`
	RequiredChecks        []workcycle.Check                `json:"required_checks"`
	RequiredConsumers     []string                         `json:"required_consumers"`
	RequiredCoverage      []workcycle.Coverage             `json:"required_coverage"`
	VerificationScenarios []workcycle.VerificationScenario `json:"verification_scenarios,omitempty"`
	EnvironmentID         string                           `json:"environment_id"`
	Environment           map[string]string                `json:"environment,omitempty"`
}

func Decode(reader io.Reader, value any) error {
	d := json.NewDecoder(reader)
	d.DisallowUnknownFields()
	if err := d.Decode(value); err != nil {
		return err
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		return fmt.Errorf("expected one JSON value")
	}
	return nil
}

func writeJSON(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return writeAtomic(path, append(b, '\n'))
}

func writeAtomic(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".write-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_, err = f.Write(data)
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), path)
}

func Prepare(ctx context.Context, cfg Config) (*Driver, error) {
	if cfg.TaskKind != "documentation" {
		cfg.EngineeringTriage = nil
		if cfg.Workflow == "" {
			cfg.Workflow = "fix-issue"
		}
		switch cfg.Workflow {
		case "fix-issue", "agent":
			if cfg.ResumeFrom != "" {
				return nil, fmt.Errorf("cross-run resumption requires the staged workflow")
			}
		case "staged":
		default:
			return nil, fmt.Errorf("workflow must be fix-issue, agent, or staged")
		}
	} else {
		switch cfg.Workflow {
		case "", "staged", string(doccycle.AuthorStage):
		default:
			return nil, fmt.Errorf("%s workflow applies to engineering tasks", cfg.Workflow)
		}
		if cfg.Hosted {
			if cfg.Workflow == "staged" || cfg.ResumeFrom != "" || cfg.ReviewProfile != nil {
				return nil, fmt.Errorf("hosted documentation runs one author session with a fresh candidate")
			}
			cfg.Workflow = string(doccycle.AuthorStage)
		} else if cfg.Workflow == string(doccycle.AuthorStage) {
			return nil, fmt.Errorf("docs-author workflow requires hosted execution")
		}
	}
	managedRecipes := cfg.StaticChecks
	if cfg.Hosted && cfg.TaskKind != "documentation" && cfg.Workflow != "fix-issue" {
		return nil, fmt.Errorf("hosted execution requires the fix-issue workflow")
	}
	if cfg.Hosted {
		if err := experimentruntime.ValidateHostedOpenCodeProfile(cfg.Profile); err != nil {
			return nil, err
		}
		if cfg.TaskKind != "documentation" {
			if cfg.ReviewProfile == nil {
				return nil, fmt.Errorf("hosted engineering requires a review_profile")
			}
			if err := experimentruntime.ValidateHostedOpenCodeProfile(*cfg.ReviewProfile); err != nil {
				return nil, err
			}
		}
		if strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY")) == "" {
			return nil, fmt.Errorf("hosted execution requires OPENROUTER_API_KEY in the controller environment")
		}
	}
	if cfg.Workflow == "fix-issue" {
		if !cfg.Hosted && cfg.Profile.Runtime != "codex" {
			return nil, fmt.Errorf("fix-issue workflow requires Codex")
		}
		cfg.AuthorHelperProfile = nil
		cfg.ReviewParallelism = 0
		managedRecipes = nil
		cfg.ProductCheckBinary = ""
		if cfg.ReviewProfile == nil {
			cfg.ReviewProfile = &experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "xhigh"}
		}
	}
	if err := validateReviewParallelism(cfg); err != nil {
		return nil, err
	}
	if err := validateProductCheckConfig(cfg); err != nil {
		return nil, err
	}
	seenChecks := map[string]bool{}
	for _, recipe := range managedRecipes {
		if e := checkrunner.Validate(recipe); e != nil {
			return nil, e
		}
		if seenChecks[recipe.ID] {
			return nil, fmt.Errorf("duplicate static check %s", recipe.ID)
		}
		seenChecks[recipe.ID] = true
		matched := false
		for _, check := range cfg.RequiredChecks {
			matched = matched || (check.ID == recipe.ID && check.Scope == recipe.Scope)
		}
		if !matched {
			return nil, fmt.Errorf("static check %s is not a configured required check", recipe.ID)
		}
	}
	for _, path := range []string{cfg.Workspace, cfg.TaskDirectory, cfg.OutputDirectory, cfg.RuntimeBinary} {
		if !filepath.IsAbs(path) {
			return nil, fmt.Errorf("workspace, task, output, and runtime paths must be absolute")
		}
	}
	workspace, err := filepath.EvalSymlinks(cfg.Workspace)
	if err != nil {
		return nil, err
	}
	cfg.Workspace = workspace
	parent, err := filepath.EvalSymlinks(filepath.Dir(cfg.OutputDirectory))
	if err != nil {
		return nil, err
	}
	cfg.OutputDirectory = filepath.Join(parent, filepath.Base(cfg.OutputDirectory))
	if inside(cfg.Workspace, cfg.OutputDirectory) || inside(cfg.OutputDirectory, cfg.Workspace) || inside(cfg.OutputDirectory, cfg.TaskDirectory) {
		return nil, fmt.Errorf("output must be separate from the source and task directories")
	}
	if cfg.EngineeringTriage != nil && cfg.TaskKind == "documentation" {
		return nil, fmt.Errorf("engineering triage cannot replace documentation triage")
	}
	if cfg.RuntimeVersion == "" {
		return nil, fmt.Errorf("runtime_version is required")
	}
	if _, err := experimentruntime.BuildCommand(cfg.Profile, cfg.Workspace, ""); err != nil {
		return nil, err
	}
	if cfg.ReviewProfile != nil {
		if cfg.ReviewProfile.Runtime != cfg.Profile.Runtime {
			return nil, fmt.Errorf("review profile must use the configured runtime")
		}
		if _, err := experimentruntime.BuildCommand(*cfg.ReviewProfile, cfg.Workspace, ""); err != nil {
			return nil, err
		}
	}
	if cfg.AuthorHelperProfile != nil {
		if cfg.Workflow != "agent" || cfg.TaskKind == "documentation" || cfg.Profile.Runtime != "codex" || cfg.AuthorHelperProfile.Runtime != "codex" || runtime.GOOS != "linux" {
			return nil, fmt.Errorf("author_helper_profile requires the Codex engineering agent workflow on Linux")
		}
		if _, err := experimentruntime.BuildCommand(*cfg.AuthorHelperProfile, cfg.Workspace, ""); err != nil {
			return nil, fmt.Errorf("author helper profile: %w", err)
		}
	}
	for key := range cfg.Environment {
		if key == "HOME" || key == "CODEX_HOME" || strings.HasPrefix(key, "OPENCODE_") || strings.HasPrefix(key, "XDG_") ||
			key == "GITHUB_TOKEN" || key == "GH_TOKEN" || key == "GH_ACCESS_TOKEN" || key == "AGENT_PROJECT_TOKEN" || (cfg.Hosted && key == "OPENROUTER_API_KEY") {
			return nil, fmt.Errorf("environment key %s is controlled by the worker", key)
		}
	}
	var packet workcontext.Packet
	if cfg.Workflow == "fix-issue" {
		skill, err := os.Stat(filepath.Join(cfg.Workspace, nativeFixIssueSkill))
		if err != nil {
			return nil, fmt.Errorf("read the repository fix-issue skill: %w", err)
		}
		if !skill.Mode().IsRegular() {
			return nil, fmt.Errorf("the repository fix-issue skill must be a file: %s", nativeFixIssueSkill)
		}
	} else {
		packet, err = workcontext.BuildForKind(cfg.Workspace, cfg.TaskDirectory, cfg.Context, cfg.TaskKind)
		if err != nil {
			return nil, err
		}
	}
	task, err := os.ReadFile(filepath.Join(cfg.TaskDirectory, "task.md"))
	if err != nil {
		return nil, err
	}
	if len(strings.TrimSpace(string(task))) == 0 {
		return nil, fmt.Errorf("task.md is empty")
	}
	base, err := git(ctx, cfg.Workspace, "rev-parse", "HEAD")
	if err != nil {
		return nil, err
	}
	status, err := git(ctx, cfg.Workspace, "status", "--porcelain")
	if err != nil {
		return nil, err
	}
	if len(status) != 0 && cfg.ResumeFrom == "" {
		return nil, fmt.Errorf("use a clean, dedicated worktree for a new work cycle")
	}
	d := &Driver{Config: cfg, Packet: packet, base: strings.TrimSpace(string(base)), evidence: filepath.Join(cfg.OutputDirectory, "evidence")}
	d.Request = workcycle.Request{Kind: cfg.TaskKind, TaskID: cfg.TaskID, Task: string(task), Context: packet.Text, RequiredCriteria: cfg.RequiredCriteria, RequiredChecks: cfg.RequiredChecks, RequiredConsumers: cfg.RequiredConsumers, RequiredCoverage: cfg.RequiredCoverage, VerificationScenarios: cfg.VerificationScenarios, EnvironmentID: cfg.EnvironmentID}
	for _, recipe := range managedRecipes {
		d.Request.ManagedChecks = append(d.Request.ManagedChecks, workcycle.Check{ID: recipe.ID, Scope: recipe.Scope})
	}
	if cfg.Workflow == "agent" {
		if _, err = workcycle.VerificationCases(d.Request); err != nil {
			return nil, err
		}
	}
	if err = os.Mkdir(cfg.OutputDirectory, 0700); err != nil {
		return nil, err
	}
	if err := os.Mkdir(d.evidence, 0700); err != nil {
		return nil, err
	}
	if err := d.prepareDocumentationTriage(); err != nil {
		return nil, err
	}
	if cfg.ResumeFrom != "" {
		if err := d.loadResume(ctx); err != nil {
			return nil, err
		}
	}
	if cfg.Workflow != "fix-issue" {
		if err := writeJSON(filepath.Join(cfg.OutputDirectory, "context.json"), packet); err != nil {
			return nil, err
		}
	}
	// Credentials and caller environment are deliberately absent from the public input record.
	public := cfg
	public.LoginFile = ""
	public.Environment = nil
	if err := writeJSON(filepath.Join(cfg.OutputDirectory, "config.json"), public); err != nil {
		return nil, err
	}
	return d, nil
}

func Execute(ctx context.Context, cfg Config) (workcycle.Report, error) {
	d, err := Prepare(ctx, cfg)
	if err != nil {
		return workcycle.Report{}, err
	}
	if cfg.TaskKind == "documentation" {
		if d.Config.Hosted {
			return doccycle.RunAuthor(ctx, d.Request, d)
		}
		if d.resume != nil {
			if cfg.ResumeMode == "assessment" {
				return doccycle.ReplayNoChangeAssessment(ctx, d.Request, d, *d.resume)
			}
			return doccycle.ResumeVerification(ctx, d.Request, d, *d.resume)
		}
		return doccycle.Run(ctx, d.Request, d)
	}
	if d.Config.Workflow == "agent" {
		return workcycle.RunAgent(ctx, d.Request, d)
	}
	if d.Config.Workflow == "fix-issue" {
		return d.runNative(ctx)
	}
	if d.resume != nil {
		return workcycle.ResumeVerification(ctx, d.Request, d, *d.resume)
	}
	return workcycle.Run(ctx, d.Request, d)
}

func inside(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func git(ctx context.Context, dir string, args ...string) ([]byte, error) {
	c := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
	c.Dir = dir
	c.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"}
	out, err := c.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("git %s: %w: %s", args[0], err, out)
	}
	return out, nil
}

func (c Config) ProfileForStage(stage workcycle.Stage) experimentruntime.Profile {
	if (stage == workcycle.ReviewStage || stage == workcycle.Stage("docs-review")) && c.ReviewProfile != nil {
		return *c.ReviewProfile
	}
	return c.Profile
}
