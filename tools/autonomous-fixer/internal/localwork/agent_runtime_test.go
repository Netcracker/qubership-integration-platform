package localwork

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func agentFixture(t *testing.T) Config {
	t.Helper()
	cfg := fixture(t)
	binary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestAgentRuntimeHelper -- \"$@\"\n"
	if err = os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.Environment["QIP_AGENT_HELPER"] = "1"
	return cfg
}

func TestAgentRuntimeHelper(t *testing.T) {
	if os.Getenv("QIP_AGENT_HELPER") != "1" {
		return
	}
	prompt, err := io.ReadAll(os.Stdin)
	if err != nil {
		os.Exit(3)
	}
	assessment := strings.Contains(string(prompt), "independent QIP assessor")
	if path := os.Getenv("QIP_AGENT_SUMMARY_PATH"); path != "" {
		if os.WriteFile(path, []byte("Agent observations that the controller must preserve."), 0600) != nil {
			os.Exit(8)
		}
	}
	home := os.Getenv("HOME")
	id := "author-session-123"
	if assessment {
		id = filepath.Base(filepath.Dir(home))
	}
	resumed := false
	for _, arg := range os.Args {
		resumed = resumed || arg == "resume"
	}
	if !assessment {
		value := "candidate"
		if resumed {
			value = "repaired"
		}
		if os.WriteFile("engine/code.txt", []byte(value), 0600) != nil {
			os.Exit(4)
		}
	}
	journal := filepath.Join(home, ".codex", "sessions", "author.jsonl")
	if os.MkdirAll(filepath.Dir(journal), 0700) != nil {
		os.Exit(5)
	}
	f, err := os.OpenFile(journal, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0600)
	if err != nil {
		os.Exit(6)
	}
	_, _ = fmt.Fprintf(f, "session %s resumed=%t\n", id, resumed)
	_ = f.Close()
	if os.Getenv("QIP_AGENT_NO_SESSION") != "1" {
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "thread.started", "thread_id": id})
	}
	text := "Fixed the consumer. Original image: /original/issue.png. Preflight: /external/preflight.json. Evidence: https://example.test/report\nNo structured response is needed."
	if outcome := os.Getenv("QIP_AGENT_OUTCOME"); outcome != "" && !assessment {
		text += "\nOutcome: " + outcome
	}
	if assessment {
		text = `{"decision":"approve","summary":"Checked the actual candidate and healthy peer.","findings":[]}`
		if _, cases, verify := strings.Cut(string(prompt), "Required verification cases:\n"); verify {
			var required []workcycle.VerificationScenario
			if json.NewDecoder(strings.NewReader(cases)).Decode(&required) != nil {
				os.Exit(9)
			}
			stage := filepath.Dir(home)
			evidenceDir := filepath.Join(filepath.Dir(stage), "evidence", filepath.Base(stage))
			evidence := filepath.Join(evidenceDir, "observed.txt")
			if os.WriteFile(evidence, []byte("Fixture result: observed return to owning folder."), 0600) != nil {
				os.Exit(10)
			}
			result := workcycle.AgentAssessment{Decision: "approve", Summary: "Fresh candidate and healthy peer passed.", Findings: []string{}}
			for _, c := range required {
				if c.ID != os.Getenv("QIP_AGENT_VERIFY_OMIT") {
					result.Coverage = append(result.Coverage, workcycle.VerificationResult{CaseID: c.ID, Observed: "Fresh fixture arrived at the owning folder.", Evidence: []string{filepath.Base(evidence)}})
				}
			}
			if os.Getenv("QIP_AGENT_VERIFY_SUPPLEMENTAL_BLOCK") == "1" {
				result.Decision = "verification-blocked"
				result.Summary = "The required observations passed. A supplemental repository-wide network link scan hit rate limits."
				result.Findings = []string{"The full Lychee scan did not complete."}
			}
			data, _ := json.Marshal(result)
			text = string(data)
			if strings.Contains(string(prompt), `"verification_recheck":`) && os.Getenv("QIP_AGENT_RECHECK_FAIL") == "1" {
				text = `{"decision":"verification-blocked","summary":"The required Sessions action could not be rerun.","findings":[],"coverage":[]}`
			}
		}
		if strings.Contains(string(prompt), `"stage": "verify-reassessment"`) && os.Getenv("QIP_AGENT_REASSESS_BLOCK") == "1" {
			text = `{"decision":"verification-blocked","summary":"The served product identity could not be confirmed.","findings":[]}`
		}
		if override := os.Getenv("QIP_AGENT_ASSESSMENT"); override != "" {
			text = override
		}
	}
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": text}})
	if os.Getenv("QIP_AGENT_FAILED") == "1" {
		fmt.Println(`{"type":"turn.failed","error":{"message":"native failure"}}`)
		os.Exit(7)
	}
	if os.Getenv("QIP_AGENT_INCOMPLETE") != "1" {
		fmt.Println(`{"type":"turn.completed"}`)
	}
	os.Exit(0)
}

func TestAgentAuthorAcceptsNaturalProseAndExternalReferences(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	result, err := d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(result.Text, "/original/issue.png") || !strings.Contains(result.Text, "/external/preflight.json") || !strings.Contains(result.Text, "https://") {
		t.Fatal(result)
	}
	if len(result.Evidence) != 2 {
		t.Fatal(result)
	}
	if err = d.checkArtifacts(); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"response-schema.json", "assessment-schema.json", "declaration-resolution.json"} {
		if _, err = os.Stat(filepath.Join(d.Config.OutputDirectory, "step-0001-author", name)); !os.IsNotExist(err) {
			t.Fatalf("author created a declaration gate: %s", name)
		}
	}
	var command experimentruntime.Command
	readAgentJSON(t, filepath.Join(d.Config.OutputDirectory, "step-0001-author", "command.json"), &command)
	if strings.Contains(strings.Join(command.Args, " "), "--output-schema") {
		t.Fatal(command.Args)
	}
	value, _ := os.ReadFile(filepath.Join(d.Config.Workspace, "engine/code.txt"))
	if string(value) != "candidate" {
		t.Fatal("author could not edit source")
	}
}

func TestAgentAuthorPreservesAgentSummaryAndHashesControllerCopy(t *testing.T) {
	cfg := agentFixture(t)
	agentSummary := filepath.Join(cfg.OutputDirectory, "evidence", "step-0001-author", "summary.md")
	cfg.Environment["QIP_AGENT_SUMMARY_PATH"] = agentSummary
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	result, err := d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request})
	if err != nil {
		t.Fatal(err)
	}
	agentData, err := os.ReadFile(agentSummary)
	if err != nil || string(agentData) != "Agent observations that the controller must preserve." {
		t.Fatal("controller overwrote the agent's summary", string(agentData), err)
	}
	controllerSummary := filepath.Join(d.evidence, "controller-step-0001-author", "summary.md")
	data, err := os.ReadFile(controllerSummary)
	if err != nil || string(data) != result.Text || d.artifacts[controllerSummary] != bytesDigest(data) {
		t.Fatal("controller summary was not preserved and hashed", string(data), err)
	}
	if result.Evidence[0] != controllerSummary || result.Evidence[1] != filepath.Dir(agentSummary) {
		t.Fatal("author evidence lost the controller copy or agent observations", result.Evidence)
	}
	if _, tracked := d.artifacts[agentSummary]; tracked {
		t.Fatal("controller claimed ownership of the agent's file")
	}
}

func TestAgentAuthorRepairsInExplicitSameSession(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err = d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request, Feedback: []string{"Preserve the healthy consumer."}}); err != nil {
			t.Fatal(err)
		}
	}
	first := filepath.Join(d.Config.OutputDirectory, "step-0001-author")
	second := filepath.Join(d.Config.OutputDirectory, "step-0002-author")
	var command experimentruntime.Command
	readAgentJSON(t, filepath.Join(second, "command.json"), &command)
	joined := strings.Join(command.Args, " ")
	if !strings.Contains(joined, "exec resume") || !strings.Contains(joined, "author-session-123") || command.Workspace != d.Config.Workspace {
		t.Fatal(command)
	}
	for _, forbidden := range []string{"--last", "--cd", "--sandbox", "--color", "--output-schema"} {
		for _, arg := range command.Args {
			if arg == forbidden {
				t.Fatalf("resume received unsupported or unsafe flag %s", forbidden)
			}
		}
	}
	var metadata struct {
		SessionID       string `json:"session_id"`
		Continuation    bool   `json:"continuation"`
		PreviousArchive string `json:"previous_archive"`
	}
	readAgentJSON(t, filepath.Join(second, "session.json"), &metadata)
	if !metadata.Continuation || metadata.SessionID != "author-session-123" || metadata.PreviousArchive != first {
		t.Fatal(metadata)
	}
	for i, dir := range []string{first, second} {
		raw, err := os.ReadFile(filepath.Join(dir, "home", ".codex", "sessions", "author.jsonl"))
		if err != nil || strings.Count(string(raw), "session ") != i+1 {
			t.Fatalf("native cumulative history lost: %s %v", raw, err)
		}
		if _, err = os.Stat(filepath.Join(dir, "home", ".codex", "auth.json")); !os.IsNotExist(err) {
			t.Fatal("credential entered archive")
		}
	}
	if _, err = os.Stat(filepath.Join(d.Config.OutputDirectory, "author-home", ".codex", "auth.json")); !os.IsNotExist(err) {
		t.Fatal("credential retained in shared author home")
	}
	value, _ := os.ReadFile(filepath.Join(d.Config.Workspace, "engine/code.txt"))
	if string(value) != "repaired" {
		t.Fatal(string(value))
	}
}

func TestAgentMissingSessionPreservesWorkAndBlocksRepair(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Environment["QIP_AGENT_NO_SESSION"] = "1"
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	result, err := d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request})
	if err != nil || result.Text == "" {
		t.Fatal("completed work discarded for missing continuation metadata", result, err)
	}
	if _, err = d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request, Feedback: []string{"Repair the consumer."}}); err == nil {
		t.Fatal("repair silently started a new session")
	}
	dirs, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(dirs) != 1 {
		t.Fatal("repair invoked another native session", dirs)
	}
	var metadata map[string]any
	readAgentJSON(t, filepath.Join(dirs[0], "session.json"), &metadata)
	if metadata["continuation_warning"] == nil {
		t.Fatal("missing-session limitation not recorded", metadata)
	}
}

func TestAgentUsesFrozenContextWhenInstructionSourceChanges(t *testing.T) {
	d, err := Prepare(context.Background(), agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request}); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(d.Config.Workspace, "AGENTS.md"), []byte("Updated conventions under review."), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request, Feedback: []string{"Check the instruction change."}}); err != nil {
		t.Fatal("legitimate instruction candidate blocked author repair", err)
	}
	if _, err = d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.ReviewStage}); err != nil {
		t.Fatal("legitimate instruction candidate blocked independent review", err)
	}
	prompt, err := os.ReadFile(filepath.Join(d.Config.OutputDirectory, "step-0003-review", "prompt.txt"))
	if err != nil || !strings.Contains(string(prompt), "Project conventions.") || strings.Contains(string(prompt), "Updated conventions under review.") {
		t.Fatal("review lost frozen requirements", string(prompt), err)
	}
}

func TestAgentAssessmentsUseIndependentSessionsAndStageProfiles(t *testing.T) {
	cfg := agentFixture(t)
	reviewer := cfg.Profile
	reviewer.Model = "reviewer"
	cfg.ReviewProfile = &reviewer
	if err := os.WriteFile(cfg.ModelCatalog, []byte(`{"models":[{"slug":"fixture","context_window":200000,"supported_reasoning_levels":[{"effort":"medium"}]},{"slug":"reviewer","context_window":200000,"supported_reasoning_levels":[{"effort":"medium"}]}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request}); err != nil {
		t.Fatal(err)
	}
	for _, stage := range []workcycle.Stage{workcycle.ReviewStage, workcycle.ReviewStage, workcycle.VerifyStage} {
		result, err := d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: stage, Lens: "compatibility", Evidence: []string{"/external/live.log"}})
		if err != nil || result.Decision != "approve" {
			t.Fatalf("%+v %v", result, err)
		}
		dir := filepath.Join(cfg.OutputDirectory, fmt.Sprintf("step-%04d-%s", d.step, stage))
		var command experimentruntime.Command
		readAgentJSON(t, filepath.Join(dir, "command.json"), &command)
		var profile experimentruntime.Profile
		readAgentJSON(t, filepath.Join(dir, "profile.json"), &profile)
		want := cfg.Profile
		if stage == workcycle.ReviewStage {
			want = reviewer
		}
		if profile != want || strings.Contains(strings.Join(command.Args, " "), "resume") {
			t.Fatal(profile, command.Args)
		}
		raw, _ := os.ReadFile(filepath.Join(dir, "home", ".codex", "sessions", "author.jsonl"))
		if strings.Contains(string(raw), "author-session-123") || strings.Count(string(raw), "session ") != 1 {
			t.Fatal("independent session inherited author history", string(raw))
		}
		if _, err := os.Stat(filepath.Join(dir, "home", ".codex", "auth.json")); !os.IsNotExist(err) {
			t.Fatal("review credential retained")
		}
	}
}

func TestAgentRuntimeFailureCannotApprove(t *testing.T) {
	for _, mode := range []string{"QIP_AGENT_FAILED", "QIP_AGENT_INCOMPLETE"} {
		t.Run(mode, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Environment[mode] = "1"
			d, err := Prepare(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			result, err := d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.ReviewStage})
			if err == nil || result.Decision != "verification-blocked" {
				t.Fatalf("accepted failed runtime: %+v %v", result, err)
			}
			raw, readErr := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0001-review", "response-raw.txt"))
			if readErr != nil || !strings.Contains(string(raw), `"decision":"approve"`) {
				t.Fatal("raw failed response lost", string(raw), readErr)
			}
			if _, err := d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request}); err == nil {
				t.Fatal("author accepted failed native run")
			}
		})
	}
}

func TestAgentMalformedAssessmentIsBlockedAndSaved(t *testing.T) {
	for _, text := range []string{"Checked it; looks fine.", `{"decision":"approve","summary":"Done"}`, `{"decision":"approve","summary":"","findings":[]}`, `{"decision":"unknown","summary":"Done","findings":[]}`} {
		t.Run(text, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Environment["QIP_AGENT_ASSESSMENT"] = text
			d, err := Prepare(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			result, err := d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.VerifyStage})
			if err != nil || result.Decision != "verification-blocked" {
				t.Fatalf("%+v %v", result, err)
			}
			dir := filepath.Join(cfg.OutputDirectory, "step-0001-verify")
			raw, _ := os.ReadFile(filepath.Join(dir, "response-raw.txt"))
			if string(raw) != text {
				t.Fatal("original notes lost", string(raw))
			}
			var archived workcycle.AgentAssessment
			readAgentJSON(t, filepath.Join(dir, "assessment.json"), &archived)
			if archived.Decision != result.Decision {
				t.Fatal(archived)
			}
		})
	}
}

func TestAgentAssessmentHashesIndependentVerdictAndPreservesRawNotes(t *testing.T) {
	for _, text := range []string{`{"decision":"approve","summary":"Measured the candidate.","findings":[]}`, "The environment is unavailable; preserve these notes."} {
		t.Run(text, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Environment["QIP_AGENT_ASSESSMENT"] = text
			d, err := Prepare(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			result, err := d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.VerifyStage})
			if err != nil {
				t.Fatal(err)
			}
			stage := filepath.Join(cfg.OutputDirectory, "step-0001-verify")
			controller := filepath.Join(d.evidence, "controller-step-0001-verify")
			var hashes map[string]string
			readAgentJSON(t, filepath.Join(stage, "evidence-sha256.json"), &hashes)
			for _, name := range []string{"response-raw.txt", "assessment.json"} {
				original, err := os.ReadFile(filepath.Join(stage, name))
				if err != nil {
					t.Fatal(err)
				}
				path := filepath.Join(controller, name)
				copy, err := os.ReadFile(path)
				if err != nil || string(copy) != string(original) || hashes[path] != bytesDigest(copy) || d.artifacts[path] != hashes[path] {
					t.Fatal("independent decision evidence is not bound to the saved bytes", name, err)
				}
			}
			raw, _ := os.ReadFile(filepath.Join(controller, "response-raw.txt"))
			if string(raw) != text || result.Evidence[0] != filepath.Join(controller, "response-raw.txt") || result.Evidence[1] != filepath.Join(controller, "assessment.json") {
				t.Fatal("raw independent notes or evidence references changed", string(raw), result)
			}
			var assessment workcycle.AgentAssessment
			readAgentJSON(t, filepath.Join(controller, "assessment.json"), &assessment)
			if assessment.Decision != result.Decision {
				t.Fatal("hashed assessment differs from returned decision", assessment, result)
			}
			if err = d.checkArtifacts(); err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join(controller, "assessment.json"), []byte("changed independent decision"), 0600); err != nil {
				t.Fatal(err)
			}
			if err = d.checkArtifacts(); err == nil {
				t.Fatal("changed independent verdict escaped final evidence validation")
			}
		})
	}
}

func TestAgentApprovalWithFindingsRequestsChanges(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Environment["QIP_AGENT_ASSESSMENT"] = `{"decision":"approve","summary":"A regression remains.","findings":["The healthy consumer fails."]}`
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	result, err := d.Assess(context.Background(), workcycle.AgentAssessmentRequest{Request: d.Request, Stage: workcycle.ReviewStage})
	if err != nil || result.Decision != "changes-requested" || len(result.Findings) != 1 {
		t.Fatalf("%+v %v", result, err)
	}
}

func TestAgentContinuationCommandUsesExplicitOpenCodeSession(t *testing.T) {
	cfg := agentFixture(t)
	profile := experimentruntime.Profile{Runtime: "opencode", Model: "provider/fixture", Effort: "high"}
	command, err := agentRuntimeCommand(cfg, profile, "prose", "/archive/prompt.txt", "session-42")
	if err != nil || !strings.Contains(strings.Join(command.Args, " "), "--session session-42") {
		t.Fatal(command, err)
	}
	id, err := agentSessionID([]byte(`{"type":"text","sessionID":"session-42"}`), "opencode")
	if err != nil || id != "session-42" {
		t.Fatal(id, err)
	}
}

func readAgentJSON(t *testing.T, path string, value any) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, value); err != nil {
		t.Fatal(err)
	}
}
