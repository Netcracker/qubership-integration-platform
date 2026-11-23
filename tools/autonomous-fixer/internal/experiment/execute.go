package experiment

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

type Readiness struct {
	SourceSHA           string            `json:"source_sha"`
	Profile             Profile           `json:"profile"`
	Issue               int               `json:"issue"`
	ModelAccessEvidence string            `json:"model_access_evidence"`
	ContextEvidence     string            `json:"context_evidence"`
	EnvironmentEvidence string            `json:"environment_evidence"`
	EvidenceHashes      map[string]string `json:"evidence_sha256"`
	NetworkAccess       string            `json:"network_access"`
}

func (r Readiness) Validate(p Plan, run Prepared) error {
	if r.SourceSHA != p.Revision || r.Issue != run.Issue || r.Profile != run.Profile {
		return fmt.Errorf("readiness evidence belongs to another source, issue, or profile")
	}
	if r.NetworkAccess != "shared-host-network" {
		return fmt.Errorf("record the shared network boundary before execution")
	}
	for _, path := range []string{r.ModelAccessEvidence, r.ContextEvidence, r.EnvironmentEvidence} {
		if !filepath.IsAbs(path) || r.EvidenceHashes[path] == "" {
			return fmt.Errorf("readiness requires absolute evidence paths and their hashes")
		}
		b, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		if Hash(b) != r.EvidenceHashes[path] {
			return fmt.Errorf("readiness evidence changed: %s", path)
		}
	}
	return nil
}

type Execution struct {
	JobID         string                        `json:"job_id"`
	Role          string                        `json:"role"`
	Profile       Profile                       `json:"profile"`
	StartedAt     time.Time                     `json:"started_at"`
	Process       experimentruntime.Result      `json:"process"`
	Events        experimentruntime.EventReport `json:"events"`
	Outcome       string                        `json:"outcome"`
	ReadinessHash string                        `json:"readiness_sha256"`
}

type ToolPaths struct {
	ModelCatalog   string `json:"model_catalog,omitempty"`
	Node           string `json:"node"`
	Maven          string `json:"maven"`
	MavenCache     string `json:"maven_cache"`
	NodeModules    string `json:"node_modules"`
	E2ENodeModules string `json:"e2e_node_modules"`
	Browsers       string `json:"browsers"`
}

// Execute requires controller-side evidence. Its result is never a task-success verdict.
func Execute(ctx context.Context, root, id, role string, tools ToolPaths, readinessPath, loginPath, standPath string) (Execution, error) {
	var result Execution
	if err := CheckContinuation(root); err != nil {
		return result, err
	}
	p, e := Load(root)
	if e != nil {
		return result, e
	}
	dir, e := RunDirectory(root, id, role)
	if e != nil {
		return result, e
	}
	var run Prepared
	if e = ReadJSON(filepath.Join(dir, "prepared.json"), &run); e != nil {
		return result, e
	}
	var readiness Readiness
	if e = ReadJSON(readinessPath, &readiness); e != nil {
		return result, e
	}
	if e = readiness.Validate(p, run); e != nil {
		return result, e
	}
	inputs, e := HashFiles(filepath.Join(dir, "inputs"))
	if e != nil {
		return result, e
	}
	if !reflect.DeepEqual(inputs, run.Inputs) {
		return result, fmt.Errorf("run inputs changed")
	}
	instructions, e := InstructionHashes(run.Snapshot.Directory)
	if e != nil {
		return result, e
	}
	if !reflect.DeepEqual(instructions, run.Instructions) {
		return result, fmt.Errorf("run instructions changed")
	}
	status, e := git(ctx, run.Snapshot.Directory, "status", "--porcelain")
	if e != nil {
		return result, e
	}
	head, e := git(ctx, run.Snapshot.Directory, "rev-parse", "HEAD")
	if e != nil {
		return result, e
	}
	if len(status) != 0 || strings.TrimSpace(string(head)) != run.Snapshot.BaseSHA {
		return result, fmt.Errorf("execution requires the prepared workspace without later edits")
	}
	command, e := PreparedCommand(root, id, role)
	if e != nil {
		return result, e
	}
	if e = cleanupBeforeExecution(ctx, root, id); e != nil {
		return result, fmt.Errorf("completed-run retention: %w", e)
	}
	executionDir := filepath.Join(dir, "execution")
	if e = os.Mkdir(executionDir, 0700); e != nil {
		return result, e
	}
	home := filepath.Join(executionDir, "home")
	if e = os.Mkdir(home, 0700); e != nil {
		return result, e
	}
	var stand StandConfig
	if standPath != "" {
		if e = ReadJSON(standPath, &stand); e != nil {
			return result, e
		}
	}
	if e = seedDependencies(ctx, tools, home, run.Snapshot.Directory); e != nil {
		return result, e
	}
	environment, e := NativeEnvironment(run.Profile, run.Snapshot.Directory, home, tools, stand, run.RunToken)
	if e != nil {
		return result, e
	}
	command.Program = filepath.Join(tools.Node, "bin", run.Profile.Runtime)
	if run.Profile.Runtime == "codex" {
		selection, err := experimentruntime.PinCatalog(&command, run.Profile.RuntimeProfile(), tools.ModelCatalog, home)
		if err != nil {
			return result, err
		}
		if err = WriteJSON(filepath.Join(executionDir, "model-catalog.json"), selection); err != nil {
			return result, err
		}
	}
	expectedVersion := map[string]string{"codex": "codex-cli 0.166.0", "opencode": "opencode v2.2.6"}[run.Profile.Runtime]
	versionGuard := fmt.Sprintf(`test "$("$1" --version)" = '%s' || { printf 'Runtime version differs from the prepared profile.\n' >&2; exit 64; }; exec "$@"`, expectedVersion)
	command.Args = append([]string{"-c", versionGuard, "runtime", command.Program}, command.Args...)
	command.Program = "/bin/sh"
	if e = os.MkdirAll(filepath.Join(home, ".codex"), 0700); e != nil {
		return result, e
	}
	if run.Profile.Runtime == "codex" {
		cleanup, e := copyChatGPTLogin(loginPath, filepath.Join(home, ".codex", "auth.json"))
		if e != nil {
			return result, e
		}
		defer cleanup()
	} else if loginPath != "" {
		return result, fmt.Errorf("OpenCode free profiles do not accept the Codex login file")
	}
	readinessBytes, e := os.ReadFile(readinessPath)
	if e != nil {
		return result, e
	}
	result = Execution{JobID: id, Role: role, Profile: run.Profile, StartedAt: time.Now().UTC(), Outcome: "running", ReadinessHash: Hash(readinessBytes)}
	if e = WriteJSON(filepath.Join(executionDir, "started.json"), result); e != nil {
		return result, e
	}
	stopResources, e := RecordResources(filepath.Join(executionDir, "resources.jsonl"), root)
	if e != nil {
		return result, e
	}
	process, processErr := experimentruntime.Run(ctx, command, executionDir, environment, nil, nil)
	resourceErr := stopResources()
	result.Process = process
	result.Outcome = "unverified"
	file, openErr := os.Open(process.StdoutPath)
	if openErr == nil {
		result.Events, e = experimentruntime.ParseEvents(file, run.Profile.Runtime)
		_ = file.Close()
	} else {
		e = openErr
	}
	if processErr != nil {
		result.Outcome = process.Classification
	} else if result.Events.HasError {
		result.Outcome = "runtime_error"
	}
	if role != "implement" {
		trustedCandidate := filepath.Join(root, "runs", id, "candidate", "repository")
		diff, err := trustedWorktreeGit(ctx, trustedCandidate, run.Snapshot.Directory, "status", "--porcelain")
		if err != nil || len(diff) > 0 {
			result.Outcome = "review_modified_candidate"
		}
	}
	if writeErr := WriteJSON(filepath.Join(executionDir, "result.json"), result); writeErr != nil {
		return result, writeErr
	}
	_, metricsErr := RefreshMetrics(root, id, role)
	if metricsErr != nil {
		fmt.Fprintf(os.Stderr, "Usage metrics were not recorded for %s/%s: %v\n", id, role, metricsErr)
	}
	return result, firstError(processErr, firstError(e, resourceErr))
}

func copyChatGPTLogin(source, dest string) (func(), error) {
	noop := func() {}
	if source == "" {
		return noop, fmt.Errorf("Codex requires an explicit ChatGPT login file; local experiments require subscription authentication")
	}
	b, e := os.ReadFile(source)
	if e != nil {
		return noop, fmt.Errorf("cannot read the selected login file")
	}
	var auth struct {
		Mode   string          `json:"auth_mode"`
		APIKey *string         `json:"OPENAI_API_KEY"`
		Tokens json.RawMessage `json:"tokens"`
	}
	if json.Unmarshal(b, &auth) != nil || auth.Mode != "chatgpt" || (auth.APIKey != nil && *auth.APIKey != "") || len(auth.Tokens) == 0 || string(auth.Tokens) == "null" {
		return noop, fmt.Errorf("the selected file is not a ChatGPT subscription login")
	}
	if e = os.WriteFile(dest, b, 0600); e != nil {
		return noop, e
	}
	return func() { _ = os.Remove(dest) }, nil
}
