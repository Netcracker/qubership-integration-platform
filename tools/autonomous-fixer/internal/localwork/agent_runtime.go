package localwork

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) Author(ctx context.Context, input workcycle.AgentAuthorRequest) (workcycle.AgentAuthorResult, error) {
	var result workcycle.AgentAuthorResult
	if err := d.validateAgentContext(input.Request); err != nil {
		return result, err
	}
	if d.authorArchive != "" && d.authorSessionID == "" {
		return result, fmt.Errorf("author repair cannot continue: the prior runtime emitted no explicit session ID; preserved work is in %s", d.authorArchive)
	}
	dir, evidence, err := d.agentStep("author")
	if err != nil {
		return result, err
	}
	home := filepath.Join(d.Config.OutputDirectory, "author-home")
	if err = os.MkdirAll(home, 0700); err != nil {
		return result, err
	}
	if d.authorSessionID != "" && (d.authorSessionRuntime != d.Config.Profile.Runtime || d.authorProfile != d.Config.Profile) {
		return result, fmt.Errorf("author continuation requires the original runtime and profile")
	}
	delegation := "Do not launch another model or delegate work."
	if d.Config.AuthorHelperProfile != nil {
		socket, stop, err := startAuthorHelper(ctx, func(ctx context.Context, number int, question string) (string, error) {
			return d.runAuthorHelper(ctx, input.Request, dir, number, question)
		})
		if err != nil {
			return result, fmt.Errorf("start author helper interface: %w", err)
		}
		defer stop()
		delegation = authorHelperInstructions(socket)
	}
	var prompt string
	switch {
	case d.Config.Workflow == "fix-issue":
		prompt = d.nativeAuthorPrompt(evidence)
	case d.authorSessionID != "":
		prompt = agentAuthorContinuationPrompt(input, evidence, delegation)
	default:
		prompt = agentAuthorPrompt(input, evidence, d.base, d.Config.TaskDirectory, d.Config.ProductCheckBinary, delegation)
	}
	if err = writeJSON(filepath.Join(dir, "invocation.json"), input); err != nil {
		return result, err
	}
	priorSession := d.authorSessionID
	text, err := d.runAgent(ctx, "author", dir, home, prompt, d.Config.Profile, priorSession, false, nil)
	result.Text = text
	if err != nil {
		return result, err
	}
	controllerEvidence, err := d.agentControllerEvidence(dir)
	if err != nil {
		return result, err
	}
	summary := filepath.Join(controllerEvidence, "summary.md")
	if err = d.storeAgentEvidence(summary, []byte(text)); err != nil {
		return result, err
	}
	if err = writeJSON(filepath.Join(dir, "evidence-sha256.json"), map[string]string{summary: d.artifacts[summary]}); err != nil {
		return result, err
	}
	result.Evidence = []string{summary, evidence}
	return result, nil
}

func (d *Driver) Assess(ctx context.Context, input workcycle.AgentAssessmentRequest) (workcycle.AgentAssessment, error) {
	result, err := d.assessAgentTurn(ctx, input)
	if err == nil && result.Decision == "verification-blocked" && result.CoverageValidated && managedChecksPassed(input) {
		result, err = d.reassessVerification(ctx, input, result)
	}
	if err != nil || result.Decision != "approve" || result.CoverageAudit == nil || result.CoverageAudit.Mode != "recheck" {
		return result, err
	}
	for _, row := range result.Coverage {
		if result.CoverageAudit.Assessments[row.CaseID] == "mismatch" {
			input.VerificationRecheck = append(input.VerificationRecheck, workcycle.VerificationRecheck{CaseID: row.CaseID, Observed: row.Observed})
		}
	}
	if len(input.VerificationRecheck) == 0 {
		return result, nil
	}
	current, err := d.Snapshot(ctx)
	if err != nil || current.ID != input.Candidate.ID {
		result.Decision = "verification-blocked"
		result.Summary = "Could not confirm the candidate before the verification recheck."
		return result, err
	}
	checked, err := d.assessAgentTurn(ctx, input)
	result.CoverageAudit.Recheck = &checked
	result.Evidence = append(result.Evidence, checked.Evidence...)
	if err != nil || checked.Decision != "approve" {
		result.Decision, result.Summary, result.Findings = checked.Decision, checked.Summary, checked.Findings
		if result.Decision == "" {
			result.Decision = "verification-blocked"
			result.Summary = "The verification recheck did not complete."
		}
		return result, err
	}
	for i, row := range result.Coverage {
		for _, replacement := range checked.Coverage {
			if row.CaseID == replacement.CaseID {
				result.Coverage[i] = replacement
				break
			}
		}
	}
	result.Summary += " Focused verification recheck: " + checked.Summary
	return result, nil
}

func (d *Driver) assessAgentTurn(ctx context.Context, input workcycle.AgentAssessmentRequest) (workcycle.AgentAssessment, error) {
	var result workcycle.AgentAssessment
	if input.Stage != workcycle.ReviewStage && input.Stage != workcycle.VerifyStage && input.Stage != workcycle.VerificationReassessmentStage {
		return result, fmt.Errorf("independent assessment requires review or verify stage")
	}
	if input.Stage == workcycle.ReviewStage && d.ReviewParallelism() > 0 {
		results, err := d.AssessReviewBatch(ctx, []workcycle.AgentAssessmentRequest{input})
		if len(results) == 1 {
			result = results[0]
		}
		return result, err
	}
	if err := d.validateAgentContext(input.Request); err != nil {
		return result, err
	}
	dir, evidence, err := d.agentStep(string(input.Stage))
	if err != nil {
		return result, err
	}
	return d.assessAgentStep(ctx, input, dir, evidence, nil)
}

func (d *Driver) assessAgentStep(ctx context.Context, input workcycle.AgentAssessmentRequest, dir, evidence string, view *readOnlyReviewWorkspace) (workcycle.AgentAssessment, error) {
	var result workcycle.AgentAssessment
	home := filepath.Join(dir, "home")
	if err := os.Mkdir(home, 0700); err != nil {
		return result, err
	}
	if input.Stage == workcycle.VerifyStage {
		input.VerificationToken = "verify-" + bytesDigest([]byte(dir))[:20]
	}
	taskDirectory, productCheck := d.Config.TaskDirectory, d.Config.ProductCheckBinary
	input.CodeOnly = view != nil
	if view != nil {
		input.Evidence = nil
		taskDirectory, productCheck = view.taskDirectory, ""
	}
	if err := writeJSON(filepath.Join(dir, "invocation.json"), input); err != nil {
		return result, err
	}
	prompt := agentAssessmentPrompt(input, evidence, d.base, taskDirectory, productCheck)
	started := time.Now()
	text, runErr := d.runAgent(ctx, string(input.Stage), dir, home, prompt, d.Config.ProfileForStage(input.Stage), "", true, view)
	if runErr != nil {
		result.Decision = "verification-blocked"
		result.Summary = "The independent runtime did not complete: " + runErr.Error()
	} else if err := decodeAgentAssessment(text, &result); err != nil {
		d.failureKind = "agent_protocol"
		result.Decision = "verification-blocked"
		result.Summary = "The independent runtime did not return a valid assessment: " + err.Error()
	}
	if result.Decision == "approve" && len(result.Findings) > 0 {
		result.Decision = "changes-requested"
	}
	var proofHashes map[string]string
	var err error
	validateCoverage := input.Stage == workcycle.VerifyStage && (result.Decision == "approve" ||
		(result.Decision == "verification-blocked" && managedChecksPassed(input)))
	if validateCoverage {
		proofHashes, err = d.validateVerification(input, result.Coverage, evidence, started)
		if err != nil && result.Decision == "approve" {
			result.Decision = "verification-blocked"
			result.Summary = "Final verification has incomplete evidence. " + err.Error()
		}
	}
	controllerEvidence, err := d.agentControllerEvidence(dir)
	if err != nil {
		return result, errors.Join(runErr, err)
	}
	rawPath := filepath.Join(controllerEvidence, "response-raw.txt")
	assessmentPath := filepath.Join(controllerEvidence, "assessment.json")
	result.Evidence = []string{rawPath, assessmentPath, evidence}
	hashes, err := d.archiveVerificationEvidence(controllerEvidence, result.Coverage, proofHashes)
	if err != nil {
		result.Decision = "verification-blocked"
		result.Summary = "Cannot archive final verification evidence. " + err.Error()
		hashes = map[string]string{}
	}
	result.CoverageValidated = len(hashes) > 0
	if input.Stage == workcycle.VerifyStage && len(input.VerificationRecheck) == 0 && result.Decision == "approve" {
		result.CoverageAudit = d.auditCoverage(ctx, input, result.Coverage)
		if result.CoverageAudit != nil && result.CoverageAudit.Evidence != "" {
			result.Evidence = append(result.Evidence, result.CoverageAudit.Evidence)
		}
	}
	if err = writeJSON(filepath.Join(dir, "assessment.json"), result); err != nil {
		return result, errors.Join(runErr, err)
	}
	assessmentData, err := os.ReadFile(filepath.Join(dir, "assessment.json"))
	if err != nil {
		return result, errors.Join(runErr, err)
	}
	if err = d.storeAgentEvidence(rawPath, []byte(text)); err != nil {
		return result, errors.Join(runErr, err)
	}
	if err = d.storeAgentEvidence(assessmentPath, assessmentData); err != nil {
		return result, errors.Join(runErr, err)
	}
	hashes[rawPath], hashes[assessmentPath] = d.artifacts[rawPath], d.artifacts[assessmentPath]
	if err = writeJSON(filepath.Join(dir, "evidence-sha256.json"), hashes); err != nil {
		return result, errors.Join(runErr, err)
	}
	return result, runErr
}

func (d *Driver) agentControllerEvidence(dir string) (string, error) {
	path := filepath.Join(d.evidence, "controller-"+filepath.Base(dir))
	if err := os.Mkdir(path, 0700); err != nil {
		return "", err
	}
	return path, nil
}

func (d *Driver) storeAgentEvidence(path string, data []byte) error {
	if err := os.WriteFile(path, data, 0600); err != nil {
		return err
	}
	if d.artifacts == nil {
		d.artifacts = map[string]string{}
	}
	d.artifacts[path] = bytesDigest(data)
	return nil
}

func (d *Driver) validateAgentContext(request workcycle.Request) error {
	d.failureKind = ""
	if request.Context != d.Packet.Text {
		return fmt.Errorf("invocation context differs from the prepared packet")
	}
	return nil
}

func (d *Driver) agentStep(stage string) (string, string, error) {
	d.step++
	dir := filepath.Join(d.Config.OutputDirectory, fmt.Sprintf("step-%04d-%s", d.step, stage))
	if err := os.Mkdir(dir, 0700); err != nil {
		return "", "", err
	}
	evidence := filepath.Join(d.evidence, filepath.Base(dir))
	if err := os.Mkdir(evidence, 0700); err != nil {
		return "", "", err
	}
	return dir, evidence, nil
}

func (d *Driver) runAgent(ctx context.Context, stage, dir, home, prompt string, profile experimentruntime.Profile, session string, assessment bool, view *readOnlyReviewWorkspace) (string, error) {
	if view == nil {
		if err := workcontext.VerifyTaskFiles(d.Config.TaskDirectory, d.Packet); err != nil {
			return "", err
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "prompt.txt"), []byte(prompt), 0600); err != nil {
		return "", err
	}
	if err := writeJSON(filepath.Join(dir, "profile.json"), profile); err != nil {
		return "", err
	}
	runtimeConfig := d.Config
	if view != nil {
		if profile.Runtime != "codex" || session != "" {
			return "", fmt.Errorf("read-only review requires a fresh Codex session")
		}
		runtimeConfig.Workspace = view.workspace
	}
	command, err := agentRuntimeCommand(runtimeConfig, profile, prompt, filepath.Join(dir, "prompt.txt"), session)
	if err != nil {
		return "", err
	}
	if d.Config.Workflow == "fix-issue" && profile.Runtime == "codex" {
		configureNativeReviewers(&command, *d.Config.ReviewProfile)
	}
	var env []string
	progressPath := filepath.Join(d.Config.OutputDirectory, "runtime-progress.json")
	if view != nil {
		view.configure(&command)
		env = reviewEnvironment(home)
		progressPath = ""
	} else {
		env, err = d.environmentFor(home, profile)
		if err != nil {
			return "", err
		}
	}
	version := exec.CommandContext(ctx, command.Program, "--version")
	version.Env = env
	output, err := version.Output()
	if err != nil {
		return "", fmt.Errorf("read runtime version: %w", err)
	}
	if strings.TrimSpace(string(output)) != d.Config.RuntimeVersion {
		return "", fmt.Errorf("runtime version differs from the configured version")
	}
	var providerUsage *openrouterusage.Recorder
	var stopRelay func()
	if d.Config.Hosted {
		providerUsage = &openrouterusage.Recorder{}
		relayURL, stop, e := startOpenRouterRelay(strings.TrimSpace(os.Getenv("OPENROUTER_API_KEY")), providerUsage, profile, *d.Config.ReviewProfile)
		if e != nil {
			return "", e
		}
		stopRelay = stop
		defer stopRelay()
		config, e := experimentruntime.HostedOpenCodeConfig(profile, *d.Config.ReviewProfile, d.Config.Workspace, relayURL)
		if e != nil {
			return "", e
		}
		env = append(env, "OPENCODE_CONFIG_CONTENT="+config)
	}
	if profile.Runtime == "codex" {
		if assessment {
			schema := filepath.Join(dir, "assessment-schema.json")
			if err = writeJSON(schema, agentAssessmentSchema(workcycle.Stage(stage))); err != nil {
				return "", err
			}
			command.Args = append(command.Args[:len(command.Args)-1], "--output-schema", schema, "-")
		}
		selection, e := experimentruntime.PinCatalog(&command, profile, d.Config.ModelCatalog, home)
		if e != nil {
			return "", e
		}
		if err = writeJSON(filepath.Join(dir, "model-catalog.json"), selection); err != nil {
			return "", err
		}
		if err = copyLogin(d.Config.LoginFile, filepath.Join(home, ".codex", "auth.json")); err != nil {
			return "", err
		}
		defer os.Remove(filepath.Join(home, ".codex", "auth.json"))
	}
	if err = writeJSON(filepath.Join(dir, "command.json"), command); err != nil {
		return "", err
	}
	process, runErr := experimentruntime.RunWithProgress(ctx, command, dir, env, nil, nil, experimentruntime.ProgressOptions{Runtime: profile.Runtime, Stage: stage, LatestPath: progressPath})
	archiveErr := writeJSON(filepath.Join(dir, "process.json"), process)
	if providerUsage != nil {
		stopRelay()
		archiveErr = errors.Join(archiveErr, writeJSON(filepath.Join(d.Config.OutputDirectory, openrouterusage.Filename), providerUsage.Report()))
	}
	if view == nil {
		archiveErr = errors.Join(archiveErr, workcontext.VerifyTaskFiles(d.Config.TaskDirectory, d.Packet))
	}
	if stage == "author" {
		archiveErr = errors.Join(archiveErr, snapshotAgentHome(home, filepath.Join(dir, "home")))
	}
	stream, readErr := os.ReadFile(process.StdoutPath)
	if readErr != nil {
		return "", errors.Join(runErr, archiveErr, readErr)
	}
	id, sessionErr := agentSessionID(stream, profile.Runtime)
	if stage == "author" && id != "" && sessionErr == nil {
		if session != "" && session != id {
			sessionErr = fmt.Errorf("runtime continued a different author session")
		} else {
			d.authorSessionID, d.authorSessionRuntime, d.authorProfile = id, profile.Runtime, profile
		}
	}
	metadata := map[string]any{"runtime": profile.Runtime, "session_id": id, "continuation": session != "", "previous_archive": "", "native_home": home, "history": "cumulative native history; deduplicate requests across author turns by session and usage record"}
	if stage == "author" {
		metadata["previous_archive"] = d.authorArchive
		if id == "" {
			metadata["continuation_warning"] = "The runtime emitted no explicit session ID. Completed author work is preserved; a repair requires a resumable session."
		}
		d.authorArchive = dir
		if sessionErr == nil {
			archiveErr = errors.Join(archiveErr, writeJSON(filepath.Join(d.Config.OutputDirectory, "author-session.json"), map[string]any{"session_id": d.authorSessionID, "runtime": profile.Runtime, "profile": profile, "native_home": home, "archive": dir}))
		}
	}
	archiveErr = errors.Join(archiveErr, writeJSON(filepath.Join(dir, "session.json"), metadata))
	if d.Config.Hosted && stage == "author" {
		observed, _ := agentsignature.ReadIdentity(dir, profile.Runtime)
		archiveErr = errors.Join(archiveErr, writeJSON(filepath.Join(dir, agentsignature.IdentityFilename), observed))
	}
	events, parseErr := experimentruntime.ParseEvents(strings.NewReader(string(stream)), profile.Runtime)
	archiveErr = errors.Join(archiveErr, writeJSON(filepath.Join(dir, "runtime-events.json"), events))
	text, finalErr := experimentruntime.FinalText(strings.NewReader(string(stream)), profile.Runtime)
	if d.Config.Hosted {
		text = sanitizeDiagnostic(text)
	}
	archiveErr = errors.Join(archiveErr, os.WriteFile(filepath.Join(dir, "response-raw.txt"), []byte(text), 0600))
	if runErr != nil || archiveErr != nil || parseErr != nil || finalErr != nil || sessionErr != nil {
		return text, errors.Join(runErr, archiveErr, parseErr, finalErr, sessionErr)
	}
	if !events.CompletionValid {
		return text, fmt.Errorf("runtime did not produce a complete response")
	}
	return text, nil
}

func agentRuntimeCommand(cfg Config, profile experimentruntime.Profile, prompt, promptPath, session string) (experimentruntime.Command, error) {
	command, err := experimentruntime.BuildCommand(profile, cfg.Workspace, prompt)
	if err != nil {
		return command, err
	}
	command.Program = cfg.RuntimeBinary
	if profile.Runtime == "opencode" {
		command.Args = command.Args[:len(command.Args)-2]
		if cfg.Hosted {
			command.Args = append(command.Args, "--agent", experimentruntime.HostedOpenCodeAuthor)
		}
		if session != "" {
			command.Args = append(command.Args, "--session", session)
		}
		command.Args = append(command.Args, "--file", promptPath, "--", "Complete the assignment in the attached prompt.txt.")
		return command, nil
	}
	if session == "" {
		return command, nil
	}
	// exec resume has its own CLI options; --cd, --sandbox, and --color belong to exec.
	args := []string{"--no-daemon", "exec", "resume", "--dangerously-bypass-approvals-and-sandbox"}
	for i := 2; i < len(command.Args)-1; i++ {
		switch command.Args[i] {
		case "--cd", "--sandbox", "--color":
			i++
		default:
			args = append(args, command.Args[i])
		}
	}
	command.Args = append(args, session, "-")
	return command, nil
}

func agentSessionID(stream []byte, runtime string) (string, error) {
	reader := bufio.NewReader(strings.NewReader(string(stream)))
	id := ""
	for {
		line, err := reader.ReadBytes('\n')
		var event struct {
			Type      string `json:"type"`
			ThreadID  string `json:"thread_id"`
			SessionID string `json:"sessionID"`
		}
		if len(line) > 0 && json.Unmarshal(line, &event) == nil {
			next := ""
			if runtime == "codex" && event.Type == "thread.started" {
				next = event.ThreadID
			} else if runtime == "opencode" {
				next = event.SessionID
			}
			if next != "" {
				if id != "" && id != next {
					return id, fmt.Errorf("runtime emitted conflicting session IDs")
				}
				id = next
			}
		}
		if err != nil {
			if err != io.EOF {
				return id, err
			}
			return id, nil
		}
	}
}

func snapshotAgentHome(source, target string) error {
	for _, name := range []string{".codex", filepath.Join("data", "opencode")} {
		root := filepath.Join(source, name)
		if _, err := os.Stat(root); os.IsNotExist(err) {
			continue
		}
		if err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if entry.Name() == "auth.json" || entry.Type()&os.ModeSymlink != 0 {
				return nil
			}
			rel, err := filepath.Rel(source, path)
			if err != nil {
				return err
			}
			dest := filepath.Join(target, rel)
			if entry.IsDir() {
				return os.MkdirAll(dest, 0700)
			}
			info, err := entry.Info()
			if err != nil || !info.Mode().IsRegular() {
				return err
			}
			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			return os.WriteFile(dest, data, 0600)
		}); err != nil {
			return err
		}
	}
	return nil
}

func agentAssessmentSchema(stage workcycle.Stage) map[string]any {
	required := []string{"decision", "summary", "findings"}
	properties := map[string]any{
		"decision": map[string]any{"type": "string", "enum": []string{"approve", "changes-requested", "needs-human", "verification-blocked"}},
		"summary":  map[string]any{"type": "string"},
		"findings": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
	}
	if stage == workcycle.VerifyStage {
		required = append(required, "coverage")
		properties["coverage"] = verificationCoverageSchema()
	}
	return map[string]any{"type": "object", "additionalProperties": false, "required": required, "properties": properties}
}

func decodeAgentAssessment(text string, result *workcycle.AgentAssessment) error {
	var verdict struct {
		Decision string                         `json:"decision"`
		Summary  string                         `json:"summary"`
		Findings *[]string                      `json:"findings"`
		Coverage []workcycle.VerificationResult `json:"coverage"`
	}
	if err := json.Unmarshal([]byte(text), &verdict); err != nil {
		return err
	}
	if strings.TrimSpace(verdict.Summary) == "" || verdict.Findings == nil {
		return fmt.Errorf("assessment requires summary and findings")
	}
	switch verdict.Decision {
	case "approve", "changes-requested", "needs-human", "verification-blocked":
	default:
		return fmt.Errorf("unknown assessment decision %s", strconv.Quote(verdict.Decision))
	}
	result.Decision, result.Summary, result.Findings = verdict.Decision, verdict.Summary, *verdict.Findings
	for i := range verdict.Coverage {
		verdict.Coverage[i].References = nil
		verdict.Coverage[i].Corrections = nil
	}
	result.Coverage = verdict.Coverage
	return nil
}
