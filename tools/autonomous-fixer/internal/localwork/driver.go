package localwork

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type Driver struct {
	authorSessionID      string
	authorSessionRuntime string
	authorProfile        experimentruntime.Profile
	authorArchive        string
	lastStaticCheck      *checkrunner.Record
	checkInputs          *checkrunner.InputCache
	failureKind          string
	reviewFailureArchive string
	triageClient         tasktriage.Classifier
	triageStep           int
	Config               Config
	Packet               workcontext.Packet
	Request              workcycle.Request
	base                 string
	evidence             string
	step                 int
	checkpoint           int
	artifacts            map[string]string
	priorArtifacts       map[string]string
	priorEvidence        string
	resume               *workcycle.Report
	triageProofHash      string
}

func (d *Driver) Run(ctx context.Context, inv workcycle.Invocation) (response workcycle.Response, err error) {
	d.failureKind = ""
	if err = d.checkArtifacts(); err != nil {
		return response, err
	}
	if err = workcontext.Verify(d.Config.Workspace, d.Config.TaskDirectory, d.Packet); err != nil {
		return response, err
	}
	if inv.Request.Context != d.Packet.Text {
		return response, fmt.Errorf("invocation context differs from the prepared packet")
	}
	d.step++
	dir := filepath.Join(d.Config.OutputDirectory, fmt.Sprintf("step-%04d-%s", d.step, inv.Stage))
	if err = os.Mkdir(dir, 0700); err != nil {
		return response, err
	}
	home := filepath.Join(dir, "home")
	if err = os.Mkdir(home, 0700); err != nil {
		return response, err
	}
	stepEvidence := filepath.Join(d.evidence, filepath.Base(dir))
	if err = os.Mkdir(stepEvidence, 0700); err != nil {
		return response, err
	}
	promptInput := inv
	if inv.Request.Kind != "documentation" {
		promptInput.Request.Context += ProductCheckGuidance(d.Config.ProductCheckBinary, stepEvidence)
	}
	prompt := Prompt(promptInput, stepEvidence, d.base)
	if d.Config.Context.AttachmentsAsFiles {
		prompt += fmt.Sprintf("\nTask attachment directory: %q. Read the selected attachments from this directory; they are complete files, not omitted evidence.\n", d.Config.TaskDirectory)
	}
	if err = os.WriteFile(filepath.Join(dir, "prompt.txt"), []byte(prompt), 0600); err != nil {
		return response, err
	}
	if err = writeJSON(filepath.Join(dir, "invocation.json"), inv); err != nil {
		return response, err
	}
	runtimeConfig := d.Config
	runtimeConfig.Profile = d.Config.ProfileForStage(inv.Stage)
	if err = writeJSON(filepath.Join(dir, "profile.json"), runtimeConfig.Profile); err != nil {
		return response, err
	}
	command, err := runtimeCommand(runtimeConfig, prompt, filepath.Join(dir, "prompt.txt"))
	if err != nil {
		return response, err
	}
	env, err := d.environmentFor(home, runtimeConfig.Profile)
	if err != nil {
		return response, err
	}
	version := exec.CommandContext(ctx, command.Program, "--version")
	version.Env = env
	output, err := version.Output()
	if err != nil {
		return response, fmt.Errorf("read runtime version: %w", err)
	}
	if strings.TrimSpace(string(output)) != d.Config.RuntimeVersion {
		return response, fmt.Errorf("runtime version differs from the configured version")
	}
	if d.Config.Profile.Runtime == "codex" {
		if inv.Request.Kind != "documentation" {
			schemaPath := filepath.Join(dir, "response-schema.json")
			schema := engineeringResponseSchema()
			schema["properties"].(map[string]any)["candidate_id"] = map[string]any{"type": "string", "enum": []string{inv.Candidate.ID}}
			if err = writeJSON(schemaPath, schema); err != nil {
				return response, err
			}
			command.Args = append(command.Args[:len(command.Args)-1], "--output-schema", schemaPath, "-")
		}
		selection, e := experimentruntime.PinCatalog(&command, runtimeConfig.Profile, d.Config.ModelCatalog, home)
		if e != nil {
			return response, e
		}
		if err = writeJSON(filepath.Join(dir, "model-catalog.json"), selection); err != nil {
			return response, err
		}
		if err = copyLogin(d.Config.LoginFile, filepath.Join(home, ".codex", "auth.json")); err != nil {
			return response, err
		}
		defer os.Remove(filepath.Join(home, ".codex", "auth.json"))
	}
	var stopRelay func()
	var providerUsage openrouterusage.Recorder
	if d.Config.Hosted && inv.Request.Kind == "documentation" {
		relayURL, stop, relayErr := startOpenRouterRelay(strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY")), &providerUsage, runtimeConfig.Profile)
		if relayErr != nil {
			return response, relayErr
		}
		stopRelay = stop
		config, configErr := experimentruntime.HostedDocumentationOpenCodeConfig(runtimeConfig.Profile, d.Config.Workspace, relayURL)
		if configErr != nil {
			stopRelay()
			return response, configErr
		}
		env = append(env, "OPENCODE_CONFIG_CONTENT="+config)
	}
	process, runErr := experimentruntime.RunWithProgress(ctx, command, dir, env, nil, nil, experimentruntime.ProgressOptions{Runtime: runtimeConfig.Profile.Runtime, Stage: string(inv.Stage), LatestPath: filepath.Join(d.Config.OutputDirectory, "runtime-progress.json")})
	if stopRelay != nil {
		stopRelay()
		observed, _ := agentsignature.ReadIdentity(dir, runtimeConfig.Profile.Runtime)
		err = errors.Join(
			writeJSON(filepath.Join(d.Config.OutputDirectory, openrouterusage.Filename), providerUsage.Report()),
			writeJSON(filepath.Join(dir, agentsignature.IdentityFilename), observed),
		)
		if err != nil {
			return response, errors.Join(runErr, err)
		}
	}
	if err = writeJSON(filepath.Join(dir, "process.json"), process); err != nil {
		return response, err
	}
	if runErr != nil {
		if process.Classification == "start_error" {
			d.failureKind = "environment"
		}
		return response, runErr
	}
	f, err := os.Open(process.StdoutPath)
	if err != nil {
		return response, err
	}
	events, parseErr := experimentruntime.ParseEvents(f, d.Config.Profile.Runtime)
	f.Close()
	if parseErr != nil {
		d.failureKind = "agent_protocol"
		return response, parseErr
	}
	if err = writeJSON(filepath.Join(dir, "runtime-events.json"), events); err != nil {
		return response, err
	}
	if !events.CompletionValid {
		if events.MalformedLines != 0 {
			d.failureKind = "agent_protocol"
		}
		return response, fmt.Errorf("runtime did not produce a complete response")
	}
	f, err = os.Open(process.StdoutPath)
	if err != nil {
		return response, err
	}
	final, finalErr := experimentruntime.FinalText(f, d.Config.Profile.Runtime)
	f.Close()
	if finalErr != nil {
		d.failureKind = "agent_protocol"
		return response, finalErr
	}
	if inv.Request.Kind == "documentation" {
		err = Decode(strings.NewReader(final), &response)
	} else {
		if err = os.WriteFile(filepath.Join(dir, "response-raw.txt"), []byte(final), 0600); err != nil {
			return response, err
		}
		var normalizationError string
		normalizationError, err = decodeEngineeringDeclaration(final, &response)
		if normalizationError != "" && err == nil {
			if err = writeJSON(filepath.Join(dir, "declaration-format-correction.json"), map[string]any{"method": "removed one JSON Markdown fence with no surrounding text", "initial_error": normalizationError, "raw_response_sha256": bytesDigest([]byte(final)), "model_calls": 0, "accepted": true}); err != nil {
				return response, err
			}
		}
	}
	if err != nil {
		d.failureKind = "agent_protocol"
		return response, fmt.Errorf("decode stage result: %w", err)
	}
	if err = writeJSON(filepath.Join(dir, "response.json"), response); err != nil {
		return response, err
	}
	echo := response.CandidateID
	if inv.Request.Kind == "documentation" {
		response = normalizeDocumentationResponse(response, inv.Candidate.ID)
	} else {
		response.CandidateID = inv.Candidate.ID
	}
	if err = writeJSON(filepath.Join(dir, "response-binding.json"), map[string]any{"candidate_id": inv.Candidate.ID, "model_echo": echo, "context_sha256": d.Packet.SHA256, "binding": "controller-owned synchronous invocation; source snapshot checked before and after", "evidence_binding": "references supplied by this invocation and checked against its evidence directory"}); err != nil {
		return response, err
	}
	if err = writeJSON(filepath.Join(dir, "response-bound.json"), response); err != nil {
		return response, err
	}

	if inv.Request.Kind == "documentation" {
		err = d.verifyArtifacts(response, stepEvidence)
	} else {
		response, err = d.resolveDeclarationBindings(ctx, inv, response, stepEvidence, dir)
		if err == nil {
			response, err = d.validateOrRepairEvidence(ctx, response, stepEvidence, dir)
		}
	}
	if err != nil {
		return response, err
	}
	if inv.Request.Kind != "documentation" && d.Config.ProductCheckBinary != "" {
		if err = d.validateProductResults(response, stepEvidence, inv.Stage); err != nil {
			d.failureKind = "agent_protocol"
			return response, err
		}
	}
	if err = workcontext.Verify(d.Config.Workspace, d.Config.TaskDirectory, d.Packet); err != nil {
		return response, err
	}
	if inv.Request.Kind != "documentation" && d.Config.ProductCheckBinary != "" {
		if err = d.recordDeclarationResolution(response, stepEvidence, dir); err != nil {
			return response, err
		}
	}
	if err = writeJSON(filepath.Join(dir, "evidence-sha256.json"), d.artifacts); err != nil {
		return response, err
	}
	return response, nil
}

func normalizeDocumentationResponse(r workcycle.Response, candidate string) workcycle.Response {
	r.CandidateID = candidate
	refs := append([]string{}, r.Evidence...)
	if r.Documentation != nil {
		for _, claims := range [][]workcycle.DocClaim{r.Documentation.Claims, r.Documentation.SeparateFindings} {
			for _, c := range claims {
				refs = append(refs, c.SourceEvidence...)
				refs = append(refs, c.DocumentationEvidence...)
			}
		}
	}
	for _, c := range r.Checks {
		refs = append(refs, c.Evidence...)
	}
	for _, f := range r.Findings {
		refs = append(refs, f.Evidence...)
	}
	if r.Question != nil {
		refs = append(refs, r.Question.Evidence...)
	}
	r.Evidence = nil
	seen := map[string]bool{}
	for _, ref := range refs {
		if !seen[ref] {
			r.Evidence = append(r.Evidence, ref)
			seen[ref] = true
		}
	}
	return r
}

func runtimeCommand(cfg Config, prompt, promptPath string) (experimentruntime.Command, error) {
	command, err := experimentruntime.BuildCommand(cfg.Profile, cfg.Workspace, prompt)
	if err != nil {
		return command, err
	}
	command.Program = cfg.RuntimeBinary
	if cfg.Profile.Runtime == "opencode" {
		command.Args = append(command.Args[:len(command.Args)-2], "--file", promptPath, "--", "Execute the complete stage assignment in the attached prompt.txt. Return the requested JSON object.")
	}
	return command, nil
}

func (d *Driver) environment(home string) ([]string, error) {
	return d.environmentFor(home, d.Config.Profile)
}
func (d *Driver) environmentFor(home string, profile experimentruntime.Profile) ([]string, error) {
	values := map[string]string{"HOME": home, "CODEX_HOME": filepath.Join(home, ".codex"), "PATH": os.Getenv("PATH"), "LANG": "C.UTF-8", "XDG_CONFIG_HOME": filepath.Join(home, "config"), "XDG_DATA_HOME": filepath.Join(home, "data"), "XDG_CACHE_HOME": filepath.Join(home, "cache"), "OPENCODE_CONFIG_PROJECT_DISABLE": "1", "OPENCODE_DISABLE_CLAUDE_CODE": "1", "OPENCODE_CONFIG_DIR": filepath.Join(home, "config", "opencode")}
	shared := filepath.Join(d.Config.OutputDirectory, "build-cache")
	values["MAVEN_ARGS"] = "-Dmaven.repo.local=" + filepath.Join(shared, "maven") + " -Dgpg.skip=true"
	values["npm_config_cache"] = filepath.Join(shared, "npm")
	values["GOCACHE"] = filepath.Join(shared, "go-build")
	values["GOMODCACHE"] = filepath.Join(shared, "go-mod")
	for _, name := range []string{"JAVA_HOME", "DOCKER_HOST", "KUBECONFIG", "PLAYWRIGHT_BROWSERS_PATH", "GOCACHE", "GOMODCACHE", "MAVEN_ARGS", "NODE_OPTIONS", "JAVA_TOOL_OPTIONS", "npm_config_cache"} {
		if value := os.Getenv(name); value != "" {
			values[name] = value
		}
	}
	for k, v := range d.Config.Environment {
		values[k] = v
	}
	if profile.Runtime == "opencode" && !d.Config.Hosted {
		config, err := experimentruntime.OpenCodeConfig(profile, d.Config.Workspace)
		if err != nil {
			return nil, err
		}
		values["OPENCODE_CONFIG_CONTENT"] = config
	}
	keys := make([]string, 0, len(values))
	for k := range values {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var env []string
	for _, k := range keys {
		env = append(env, k+"="+values[k])
	}
	return env, nil
}

func copyLogin(source, dest string) error {
	if source == "" {
		return fmt.Errorf("Codex requires an explicit subscription login file")
	}
	data, err := os.ReadFile(source)
	if err != nil {
		return fmt.Errorf("cannot read the selected login file")
	}
	var auth struct {
		Mode   string          `json:"auth_mode"`
		APIKey *string         `json:"OPENAI_API_KEY"`
		Tokens json.RawMessage `json:"tokens"`
	}
	if json.Unmarshal(data, &auth) != nil || auth.Mode != "chatgpt" || len(auth.Tokens) == 0 || string(auth.Tokens) == "null" || (auth.APIKey != nil && *auth.APIKey != "") {
		return fmt.Errorf("the selected file is not a subscription login")
	}
	// The credential is copied only into this invocation's private home and removed on return.
	if err := os.MkdirAll(filepath.Dir(dest), 0700); err != nil {
		return err
	}
	return os.WriteFile(dest, data, 0600)
}

func (d *Driver) verifyArtifacts(response workcycle.Response, outputDir string) error {
	var refs []string
	for _, list := range evidenceLists(&response) {
		refs = append(refs, (*list)...)
	}
	if d.artifacts == nil {
		d.artifacts = map[string]string{}
	}
	for _, ref := range refs {
		if !filepath.IsAbs(ref) {
			return fmt.Errorf("evidence path must be absolute: %s", ref)
		}
		path, err := filepath.EvalSymlinks(ref)
		if err != nil {
			return err
		}
		if !inside(outputDir, path) {
			return fmt.Errorf("result evidence must be produced by the current invocation")
		}
		info, err := os.Stat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return fmt.Errorf("evidence must be a nonempty regular file: %s", ref)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		sum := sha256.Sum256(data)
		digest := hex.EncodeToString(sum[:])
		if previous, exists := d.artifacts[path]; exists && previous != digest {
			return fmt.Errorf("previously recorded evidence changed: %s", path)
		}
		d.artifacts[path] = digest
	}
	return d.checkArtifacts()
}

func (d *Driver) checkArtifacts() error {
	for _, group := range []struct {
		root      string
		artifacts map[string]string
	}{{d.evidence, d.artifacts}, {d.priorEvidence, d.priorArtifacts}} {
		for path, expected := range group.artifacts {
			resolved, err := filepath.EvalSymlinks(path)
			if err != nil || !inside(group.root, resolved) {
				return fmt.Errorf("recorded evidence disappeared or escaped: %s", path)
			}
			sum, err := hashEvidence(resolved, io.Discard)
			if err != nil {
				return err
			}
			if sum != expected {
				return fmt.Errorf("recorded evidence changed: %s", path)
			}
		}
	}
	return nil
}

func hashEvidence(path string, dest io.Writer) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err = io.Copy(io.MultiWriter(hash, dest), file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func (d *Driver) Save(ctx context.Context, report workcycle.Report) error {
	d.checkpoint++
	path := filepath.Join(d.Config.OutputDirectory, fmt.Sprintf("checkpoint-%04d.json", d.checkpoint))
	var validationErr error
	if report.Successful() {
		if err := d.checkArtifacts(); err != nil {
			validationErr = err
		}
		current, err := d.Snapshot(ctx)
		if err != nil {
			validationErr = err
		} else if current.ID != report.Candidate.ID {
			validationErr = fmt.Errorf("candidate changed before saving the final report")
		}
		if report.Workflow != "agent" {
			if err := workcontext.Verify(d.Config.Workspace, d.Config.TaskDirectory, d.Packet); err != nil {
				validationErr = err
			}
		}
	}
	if validationErr == nil && report.Successful() {
		validationErr = d.saveReceipt(ctx, report)
	}
	if validationErr != nil {
		report.Status = "rejected"
		report.Error = validationErr.Error()
	}
	if err := writeJSON(path, report); err != nil {
		return err
	}
	if err := writeJSON(filepath.Join(d.Config.OutputDirectory, "report.json"), report); err != nil {
		return err
	}
	return validationErr
}

func (d *Driver) saveReceipt(ctx context.Context, report workcycle.Report) error {
	if report.Documentation != nil {
		if err := d.saveDocumentationDraft(ctx, report); err != nil {
			return err
		}
	}
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	sum := sha256.Sum256(append(data, '\n'))
	return writeJSON(filepath.Join(d.Config.OutputDirectory, "receipt.json"), struct {
		ContextSHA256 string            `json:"context_sha256"`
		ReportSHA256  string            `json:"report_sha256"`
		CandidateID   string            `json:"candidate_id"`
		Evidence      map[string]string `json:"evidence_sha256"`
		PriorEvidence map[string]string `json:"prior_evidence_sha256,omitempty"`
	}{d.Packet.SHA256, hex.EncodeToString(sum[:]), report.Candidate.ID, d.artifacts, d.priorArtifacts})
}

func (d *Driver) saveDocumentationDraft(ctx context.Context, report workcycle.Report) error {
	doc := report.Documentation
	draft := map[string]any{"mode": "local-draft", "task_id": report.Request.TaskID, "status": report.Status, "candidate_id": report.Candidate.ID, "decision": doc.Decision, "reason": doc.Reason, "separate_findings": doc.SeparateFindings, "issue_comment": doc.IssueComment, "github_effects_executed": false, "documentation_entry": report.Request.DocumentationEntry}
	if report.Status == "docs-no-change" {
		draft["proposed_actions"] = []string{"post evidence-backed issue comment", "close issue only if still open and decision remains current", "set project status Done after confirmed completion"}
	} else {
		draft["pr_title"] = doc.PRTitle
		draft["pr_body"] = doc.PRBody
		draft["proposed_actions"] = []string{"publish a documentation-only draft pull request", "wait for human merge", "revalidate source issue and documentation basis before completion"}
		patch, err := git(ctx, d.Config.Workspace, "diff", "--binary", "--no-ext-diff", "--no-textconv", d.base, "--")
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(d.Config.OutputDirectory, "candidate.patch"), patch, 0600); err != nil {
			return err
		}
		for _, name := range report.Candidate.ChangedPaths {
			source := filepath.Join(d.Config.Workspace, name)
			info, err := os.Lstat(source)
			if os.IsNotExist(err) {
				continue
			}
			if err != nil {
				return err
			}
			if !info.Mode().IsRegular() {
				return fmt.Errorf("documentation candidate contains a nonregular file: %s", name)
			}
			data, err := os.ReadFile(source)
			if err != nil {
				return err
			}
			dest := filepath.Join(d.Config.OutputDirectory, "candidate-files", name)
			if err := os.MkdirAll(filepath.Dir(dest), 0700); err != nil {
				return err
			}
			if err := os.WriteFile(dest, data, 0600); err != nil {
				return err
			}
		}
		draft["changed_paths"] = report.Candidate.ChangedPaths
	}
	return writeJSON(filepath.Join(d.Config.OutputDirectory, "delivery-draft.json"), draft)
}
