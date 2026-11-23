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
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func fixture(t *testing.T) Config {
	t.Helper()
	root := t.TempDir()
	workspace := filepath.Join(root, "workspace")
	task := filepath.Join(root, "task")
	for _, dir := range []string{workspace, task, filepath.Join(workspace, ".agents/skills/fix-issue"), filepath.Join(workspace, "engine")} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	files := map[string]string{
		filepath.Join(workspace, "AGENTS.md"):                         "Project conventions.",
		filepath.Join(workspace, ".agents/skills/fix-issue/SKILL.md"): "Complete the contract and independent review.",
		filepath.Join(workspace, "engine/code.txt"):                   "baseline",
		filepath.Join(task, "task.md"):                                "Fix the preserved value. See the required policy annex.",
		filepath.Join(task, "policy.md"):                              "Preserve literal values in the classic engine.",
	}
	for path, text := range files {
		if err := os.WriteFile(path, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, args := range [][]string{{"init", "-q"}, {"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "baseline"}} {
		if _, err := git(context.Background(), workspace, args...); err != nil {
			t.Fatal(err)
		}
	}
	binary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	wrapper := filepath.Join(root, "runtime")
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestRuntimeHelper\n"
	if err := os.WriteFile(wrapper, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	catalog := filepath.Join(root, "catalog.json")
	if err := os.WriteFile(catalog, []byte(`{"models":[{"slug":"fixture","context_window":200000,"supported_reasoning_levels":[{"effort":"medium"}]}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	login := filepath.Join(root, "login.json")
	if err := os.WriteFile(login, []byte(`{"auth_mode":"chatgpt","tokens":{}}`), 0600); err != nil {
		t.Fatal(err)
	}
	return Config{Workflow: "staged", Workspace: workspace, TaskDirectory: task, OutputDirectory: filepath.Join(root, "output"), RuntimeBinary: wrapper, RuntimeVersion: "fixture runtime", Profile: experimentruntime.Profile{Runtime: "codex", Model: "fixture", Effort: "medium"}, ModelCatalog: catalog, LoginFile: login, Context: workcontext.Selection{Attachments: []string{"policy.md"}}, TaskID: "task", RequiredCriteria: []string{"literal"}, RequiredConsumers: []string{"classic"}, RequiredCoverage: []workcycle.Coverage{{CriterionID: "literal", Consumer: "classic"}}, RequiredChecks: []workcycle.Check{{ID: "module", Scope: "engine"}}, EnvironmentID: "same-env", Environment: map[string]string{"QIP_WORK_TEST_HELPER": "1"}}
}

func TestRuntimeHelper(t *testing.T) {
	if os.Getenv("QIP_WORK_TEST_HELPER") != "1" {
		return
	}
	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		os.Exit(3)
	}
	prompt := string(data)
	start := strings.Index(prompt, "Controller input:\n") + len("Controller input:\n")
	end := strings.Index(prompt, "\n\nBase commit for source comparison:")
	if end < 0 {
		end = strings.Index(prompt, "\n\nSource base commit:")
	}
	var inv workcycle.Invocation
	if start < len("Controller input:\n") || end < start || json.Unmarshal([]byte(prompt[start:end]), &inv) != nil {
		os.Exit(4)
	}
	root := filepath.Dir(filepath.Dir(os.Getenv("HOME")))
	evidence := filepath.Join(root, "evidence", filepath.Base(filepath.Dir(os.Getenv("HOME"))), "result.log")
	if os.WriteFile(evidence, []byte("measured "+string(inv.Stage)), 0600) != nil {
		os.Exit(5)
	}
	refs := []string{evidence}
	r := workcycle.Response{CandidateID: inv.Candidate.ID, Evidence: refs, EnvironmentID: inv.Request.EnvironmentID}
	if strings.HasPrefix(string(inv.Stage), "docs-") {
		if os.Getenv("QIP_WORK_TEST_DOCS_NESTED_ONLY") == "1" {
			r.Evidence = nil
		}
		if os.Getenv("QIP_WORK_TEST_DOCS_BAD_ECHO") == "1" {
			r.CandidateID = "mistyped-model-echo"
		}
		r.Documentation = &workcycle.DocumentationDecision{Decision: "needed", Reason: "missing_guidance", Summary: "Document the reader behavior", Claims: []workcycle.DocClaim{{ID: "reader", Behavior: "reader behavior", Attribution: "change_related", NeedsChange: true, SourceEvidence: refs, DocumentationEvidence: refs, Action: "update help"}}, PRTitle: "docs(help): explain reader behavior", PRBody: "Explain the existing behavior. Related #1", IssueComment: "Documentation matches the implementation; no help update is needed."}
		if os.Getenv("QIP_WORK_TEST_DOCS_NO_CHANGE") == "1" {
			r.Documentation.Decision = "no_change"
			r.Documentation.Reason = "no_explanation_needed"
			r.Documentation.Claims[0].NeedsChange = false
		}
		if os.Getenv("QIP_WORK_TEST_DOCS_SEPARATE") == "1" {
			proof := filepath.Join(filepath.Dir(evidence), "separate.log")
			if os.WriteFile(proof, []byte("Independent preexisting documentation error."), 0600) != nil {
				os.Exit(8)
			}
			r.Documentation.SeparateFindings = []workcycle.DocClaim{{ID: "old-defect", Behavior: "An unrelated existing instruction is inaccurate.", Attribution: "preexisting_independent", NeedsChange: true, SourceEvidence: []string{proof}, DocumentationEvidence: []string{proof}, Action: "Record for separate triage."}}
		}
		if inv.Stage == "docs-write" && (os.Getenv("QIP_WORK_TEST_DOCS_NO_CHANGE") != "1" || os.Getenv("QIP_WORK_TEST_DOCS_NO_CHANGE_MUTATES") == "1") {
			if os.WriteFile("help/docs/readme.md", []byte("# Help\n\nUpdated behavior.\n"), 0600) != nil {
				os.Exit(7)
			}
		}
		if inv.Stage == "docs-verify" {
			for _, check := range inv.Request.RequiredChecks {
				r.Checks = append(r.Checks, workcycle.Result{ID: check.ID, Outcome: "pass", Evidence: refs})
			}
		}
		if (inv.Stage == "docs-review" || inv.Stage == "docs-verify") && os.Getenv("QIP_WORK_TEST_DOCS_MUTATE_REVIEW") == "1" {
			_ = os.WriteFile("help/docs/readme.md", []byte("# Unauthorized reviewer edit\n"), 0600)
		}
		if os.Getenv("QIP_WORK_TEST_BAD_ECHO") == "1" {
			r.CandidateID = "mistyped-opaque-id"
		}
		text, _ := json.Marshal(r)
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": string(text)}})
		fmt.Println(`{"type":"turn.completed"}`)
		os.Exit(0)
	}
	switch inv.Stage {
	case workcycle.ContractStage:
		r.Contract = &workcycle.Contract{Scenarios: []workcycle.Scenario{{ID: "literal-classic", CriterionID: "literal", Consumer: "classic", Behavior: "preserve literal", Procedure: "run actual request", Baseline: "fail"}}, Checks: []workcycle.Check{{ID: "module", Scope: "engine"}}}
	case workcycle.ReproduceStage:
		r.DataID = "dataset-A"
		r.Scenarios = []workcycle.Result{{ID: "literal-classic", Outcome: "fail", Evidence: refs}}
	case workcycle.ImplementStage:
		value := "candidate-bug"
		if len(inv.Findings) > 0 {
			value = "candidate-fixed"
		}
		if os.WriteFile("engine/code.txt", []byte(value), 0600) != nil {
			os.Exit(6)
		}
	case workcycle.ReviewStage:
		current, _ := os.ReadFile("engine/code.txt")
		if string(current) == "candidate-bug" {
			r.Findings = []workcycle.Finding{{ID: "lost-default", ScenarioID: "literal-classic", Location: "engine/code.txt:1", Description: "Default value lost", Evidence: refs}}
		}
	case workcycle.VerifyStage:
		r.DataID = "dataset-B"
		r.Scenarios = []workcycle.Result{{ID: "literal-classic", Outcome: "pass", Evidence: refs}}
		r.Checks = []workcycle.Result{{ID: "module", Outcome: "pass", Evidence: refs}}
		if os.Getenv("QIP_WORK_TEST_VERIFY_DATA") != "" {
			r.DataID = os.Getenv("QIP_WORK_TEST_VERIFY_DATA")
		}
		if os.Getenv("QIP_WORK_TEST_VERIFY_BLOCKED") == "1" {
			r.Question = &workcycle.Question{Kind: "verification-blocked", Text: "Restore the candidate preview.", Needed: "Rerun with fresh product data.", Evidence: refs}
		}
		if os.Getenv("QIP_WORK_TEST_LATE_EDIT") == "1" {
			_ = os.WriteFile("engine/code.txt", []byte("late edit"), 0600)
		}
		if os.Getenv("QIP_WORK_TEST_MISSING_CHECK") == "1" {
			r.Checks = nil
		}
		if os.Getenv("QIP_WORK_TEST_OLD_EVIDENCE") == "1" {
			old, _ := filepath.Glob(filepath.Join(root, "evidence", "step-*-implement", "result.log"))
			r.Checks[0].Evidence = []string{old[0]}
		}
	}
	if inv.Stage == workcycle.ImplementStage && os.Getenv("QIP_WORK_TEST_EVIDENCE_ROOT_TYPO") == "1" {
		r.Evidence = []string{filepath.Join(root, "mistyped-root", "evidence", filepath.Base(filepath.Dir(os.Getenv("HOME"))), "result.log")}
	}

	if inv.Stage == workcycle.ImplementStage && os.Getenv("QIP_WORK_TEST_MISSING_SUMMARY") == "1" {
		missing := filepath.Join(filepath.Dir(evidence), "implementation-observations.json")
		r.Evidence = append(r.Evidence, missing)
		r.Checks = []workcycle.Result{{ID: "module", Outcome: "pass", Evidence: refs}}
		r.Handoff = &workcycle.Handoff{Summary: "The focused check used the current candidate.", Evidence: []string{missing}}
		if os.Getenv("QIP_WORK_TEST_MISSING_MEASUREMENT") == "1" {
			r.Checks[0].Evidence = []string{missing}
		}
	}
	if os.Getenv("QIP_WORK_TEST_BAD_ECHO") == "1" {
		r.CandidateID = "mistyped-opaque-id"
	}
	if os.Getenv("QIP_WORK_TEST_RECONNECT") == "1" {
		fmt.Println(`{"type":"error","message":"Reconnecting... 2/5 (request timed out)"}`)
	}
	text, _ := json.Marshal(r)
	if os.Getenv("QIP_WORK_TEST_FENCED_JSON") == "1" {
		text = []byte("```json\n" + string(text) + "\n```")
	}
	if os.Getenv("QIP_WORK_TEST_RECONNECT_BAD_FINAL") == "1" {
		text = []byte("{broken}")
	}
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": string(text)}})
	if os.Getenv("QIP_WORK_TEST_RECONNECT_NO_COMPLETION") == "1" {
		os.Exit(0)
	}
	fmt.Println(`{"type":"turn.completed"}`)
	os.Exit(0)
}

func TestLocalCycleUsesFreshSessionsAndRepairsCandidate(t *testing.T) {
	cfg := fixture(t)
	r, err := Execute(context.Background(), cfg)
	if err != nil || r.Status != "verified" {
		t.Fatalf("%s: %v", r.Status, err)
	}
	data, _ := os.ReadFile(filepath.Join(cfg.Workspace, "engine/code.txt"))
	if string(data) != "candidate-fixed" {
		t.Fatal(string(data))
	}
	if len(r.Observations) != 7 {
		t.Fatalf("stages: %+v", r.Observations)
	}
	homes, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "home"))
	if len(homes) != 7 {
		t.Fatal(homes)
	}
	for _, home := range homes {
		if _, err := os.Stat(filepath.Join(home, ".codex/auth.json")); !os.IsNotExist(err) {
			t.Fatal("credential retained")
		}
	}
	prompts, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-review", "prompt.txt"))
	for _, path := range prompts {
		b, _ := os.ReadFile(path)
		s := string(b)
		if !strings.Contains(s, "Preserve literal values in the classic engine.") || !strings.Contains(s, "Complete the contract and independent review.") {
			t.Fatal("context omitted")
		}
		data, err := os.ReadFile(filepath.Join(filepath.Dir(path), "invocation.json"))
		if err != nil {
			t.Fatal(err)
		}
		var invocation workcycle.Invocation
		if err := json.Unmarshal(data, &invocation); err != nil {
			t.Fatal(err)
		}
		if len(invocation.Handoff) != 0 || len(invocation.Findings) != 0 || strings.Contains(string(data), `"observations"`) {
			t.Fatal("review inherited author history")
		}
		command, err := runtimeCommand(cfg, s, path)
		if err != nil {
			t.Fatal(err)
		}
		for _, arg := range command.Args {
			if arg == "resume" {
				t.Fatal("review resumed an author session")
			}
		}
	}
}

func TestLocalCycleRejectsStaleAndIncompleteVerification(t *testing.T) {
	for _, key := range []string{"QIP_WORK_TEST_LATE_EDIT", "QIP_WORK_TEST_MISSING_CHECK", "QIP_WORK_TEST_OLD_EVIDENCE"} {
		t.Run(key, func(t *testing.T) {
			cfg := fixture(t)
			cfg.Environment[key] = "1"
			r, err := Execute(context.Background(), cfg)
			if err == nil || r.Status == "verified" {
				t.Fatalf("accepted invalid result: %+v %v", r, err)
			}
			if _, e := os.Stat(filepath.Join(cfg.OutputDirectory, "report.json")); e != nil {
				t.Fatal("failed attempt not archived", e)
			}
		})
	}
}

func TestContextDriftStopsBeforeRuntime(t *testing.T) {
	cfg := fixture(t)
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg.TaskDirectory, "policy.md"), []byte("changed policy"), 0600); err != nil {
		t.Fatal(err)
	}
	_, err = d.Run(context.Background(), workcycle.Invocation{Request: d.Request, Stage: workcycle.ContractStage})
	if err == nil {
		t.Fatal("context drift accepted")
	}
	steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(steps) != 0 {
		t.Fatal("runtime prepared despite drift")
	}
}

func TestSnapshotTracksUncommittedAndUntrackedChanges(t *testing.T) {
	d, err := Prepare(context.Background(), fixture(t))
	if err != nil {
		t.Fatal(err)
	}
	a, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(d.Config.Workspace, "engine/new.txt"), []byte("new"), 0600)
	b, err := d.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if a.ID == b.ID || len(b.ChangedPaths) != 1 || b.ChangedPaths[0] != "engine/new.txt" {
		t.Fatal(b)
	}
}

func TestEvidenceCannotBeRewritten(t *testing.T) {
	d, err := Prepare(context.Background(), fixture(t))
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(d.evidence, "result.log")
	_ = os.WriteFile(p, []byte("before"), 0600)
	r := workcycle.Response{Evidence: []string{p}}
	if err := d.verifyArtifacts(r, d.evidence); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(p, []byte("after"), 0600)
	if err := d.verifyArtifacts(r, d.evidence); err == nil {
		t.Fatal("overwritten evidence accepted")
	}
}

func TestPrepareRejectsIncompleteContext(t *testing.T) {
	cfg := fixture(t)
	_ = os.Remove(filepath.Join(cfg.TaskDirectory, "policy.md"))
	if _, err := Prepare(context.Background(), cfg); err == nil {
		t.Fatal("missing annex accepted")
	}
	if _, err := os.Stat(cfg.OutputDirectory); !os.IsNotExist(err) {
		t.Fatal("started before context validation")
	}
}

func TestEngineeringSourceBindingDoesNotDependOnModelHashCopying(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_BAD_ECHO"] = "1"
	report, e := Execute(context.Background(), cfg)
	if e != nil || report.Status != "verified" {
		t.Fatalf("%+v %v", report, e)
	}
	files, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "response-binding.json"))
	if len(files) != 7 {
		t.Fatal(files)
	}
	for _, file := range files {
		var binding map[string]any
		b, _ := os.ReadFile(file)
		if e = json.Unmarshal(b, &binding); e != nil {
			t.Fatal(e)
		}
		if binding["model_echo"] != "mistyped-opaque-id" || binding["candidate_id"] == binding["model_echo"] {
			t.Fatal(binding)
		}
	}
}

func TestLocalCycleRepairsEvidenceRootTypo(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_EVIDENCE_ROOT_TYPO"] = "1"
	r, err := Execute(context.Background(), cfg)
	if err != nil || r.Status != "verified" {
		t.Fatalf("%+v: %v", r, err)
	}
	raw, _ := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0003-implement", "response.json"))
	if !strings.Contains(string(raw), "mistyped-root") {
		t.Fatal("raw response lost")
	}
}

func TestLocalCycleAcceptsRecoveredReconnect(t *testing.T) {
	cfg := fixture(t)
	cfg.Environment["QIP_WORK_TEST_RECONNECT"] = "1"
	r, err := Execute(context.Background(), cfg)
	if err != nil || r.Status != "verified" {
		t.Fatalf("status %s: %v", r.Status, err)
	}
	raw, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0001-contract", "stdout.jsonl"))
	if err != nil || !strings.Contains(string(raw), "Reconnecting...") {
		t.Fatal("reconnect record lost", err)
	}
}

func TestRecoveredReconnectDoesNotAcceptMalformedOrIncompleteFinal(t *testing.T) {
	for _, key := range []string{"QIP_WORK_TEST_RECONNECT_BAD_FINAL", "QIP_WORK_TEST_RECONNECT_NO_COMPLETION"} {
		t.Run(key, func(t *testing.T) {
			cfg := fixture(t)
			cfg.Environment["QIP_WORK_TEST_RECONNECT"] = "1"
			cfg.Environment[key] = "1"
			r, err := Execute(context.Background(), cfg)
			if err == nil || r.Status == "verified" {
				t.Fatalf("accepted invalid final: %+v %v", r, err)
			}
		})
	}
}
