package localwork

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAgentParallelReviewsUseOneSourceCopyAndIndependentSessions(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	cfg.ReviewParallelism = 2
	cfg.RequiredConsumers = append(cfg.RequiredConsumers, "micro")
	cfg.RequiredCoverage = append(cfg.RequiredCoverage, workcycle.Coverage{CriterionID: "literal", Consumer: "micro"})
	cfg.Environment["KUBECONFIG"] = "/stand/credentials"
	cfg.Environment["DOCKER_HOST"] = "unix:///stand/docker.sock"
	cfg.Environment["CIP_TARGET"] = "stand-private"
	t.Setenv("KUBECONFIG", "/host/credentials")
	t.Setenv("DOCKER_HOST", "unix:///host/docker.sock")

	testBinary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	quoted := "'" + strings.ReplaceAll(testBinary, "'", "'\\''") + "'"
	script := `#!/bin/sh
case "$1" in
	--version) echo 'fixture runtime'; exit ;;
	sandbox) exec git rev-parse HEAD ;;
esac
case " $* " in
	*qip_code_review*)
		export QIP_READ_ONLY_REVIEW_FIXTURE=1
		exec ` + quoted + ` -test.run='^TestReadOnlyReviewRuntimeFixture$' -- "$@" ;;
	*) exec ` + quoted + ` -test.run='^TestAgentRuntimeHelper$' -- "$@" ;;
esac
`
	if err = os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, report.Error, err)
	}
	if len(report.Observations) != 4 || report.Observations[1].Lens != "correctness" ||
		report.Observations[2].Lens != "compatibility" || report.Observations[3].Stage != workcycle.VerifyStage {
		t.Fatal("reviews did not join in lens order before the separate verifier", report.Observations)
	}
	var workspace string
	for _, step := range []string{"step-0002-review", "step-0003-review"} {
		dir := filepath.Join(cfg.OutputDirectory, step)
		var command experimentruntime.Command
		readAgentJSON(t, filepath.Join(dir, "command.json"), &command)
		if !strings.Contains(strings.Join(command.Args, " "), "permissions.qip_code_review.network.enabled=false") ||
			slices.Contains(command.Args, "danger-full-access") {
			t.Fatal("review command did not request the isolated permission profile", command.Args)
		}
		if workspace == "" {
			workspace = command.Workspace
		}
		if command.Workspace != workspace || workspace == cfg.Workspace {
			t.Fatal("reviews did not share one isolated source", command.Workspace)
		}
		var invocation workcycle.AgentAssessmentRequest
		readAgentJSON(t, filepath.Join(dir, "invocation.json"), &invocation)
		if !invocation.CodeOnly || len(invocation.Evidence) > 0 {
			t.Fatal("review invocation leaked the author's evidence paths", invocation)
		}
		var session map[string]any
		readAgentJSON(t, filepath.Join(dir, "session.json"), &session)
		if session["session_id"] != step || session["native_home"] != filepath.Join(dir, "home") {
			t.Fatal("reviews shared a session or home", session)
		}
		var progress experimentruntime.Progress
		readAgentJSON(t, filepath.Join(dir, "progress.json"), &progress)
		if progress.Stage != "review" || progress.Status != "completed_unverified" {
			t.Fatal("review did not preserve its independent progress", progress)
		}
		if _, err = os.Stat(filepath.Join(dir, "home", ".codex", "auth.json")); !os.IsNotExist(err) {
			t.Fatal("review login survived process completion", err)
		}
	}
	var verify experimentruntime.Command
	readAgentJSON(t, filepath.Join(cfg.OutputDirectory, "step-0004-verify", "command.json"), &verify)
	if verify.Workspace != cfg.Workspace || !slices.Contains(verify.Args, "danger-full-access") {
		t.Fatal("final verifier did not receive the live workspace", verify)
	}
	var progress experimentruntime.Progress
	readAgentJSON(t, filepath.Join(cfg.OutputDirectory, "runtime-progress.json"), &progress)
	if progress.Stage != "verify" {
		t.Fatal("parallel reviewers interfered with the live progress record", progress)
	}
	roots, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "review-source-*"))
	if err != nil || len(roots) != 0 {
		t.Fatal("temporary review source remained after the batch", roots, err)
	}
}

func TestReadOnlyReviewRuntimeFixture(t *testing.T) {
	if os.Getenv("QIP_READ_ONLY_REVIEW_FIXTURE") != "1" {
		return
	}
	for _, name := range []string{"KUBECONFIG", "DOCKER_HOST", "CIP_TARGET", "QIP_AGENT_HELPER"} {
		if os.Getenv(name) != "" {
			t.Fatalf("read-only review inherited %s", name)
		}
	}
	prompt, err := io.ReadAll(os.Stdin)
	if err != nil || !strings.Contains(string(prompt), "immutable source copy") || strings.Contains(string(prompt), "author-home") {
		t.Fatal("read-only review received live environment instructions", err)
	}
	data, err := os.ReadFile("engine/code.txt")
	if err != nil || string(data) != "candidate" {
		t.Fatal("review copy did not contain the candidate content", string(data), err)
	}
	home := os.Getenv("HOME")
	step := filepath.Base(filepath.Dir(home))
	output := filepath.Dir(filepath.Dir(home))
	if err = os.WriteFile(filepath.Join(output, "fixture-started-"+step), nil, 0600); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		started, err := filepath.Glob(filepath.Join(output, "fixture-started-step-*"))
		if err != nil || time.Now().After(deadline) {
			t.Fatal("both reviewers did not overlap", started, err)
		}
		if len(started) == 2 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "thread.started", "thread_id": step})
	_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"type": "item.completed", "item": map[string]string{
		"type": "agent_message", "text": `{"decision":"approve","summary":"Inspected the isolated source.","findings":[]}`,
	}})
	fmt.Println(`{"type":"turn.completed"}`)
	os.Exit(0)
}

func TestReadOnlyReviewSourceCopiesStagedRemovalAndUntrackedSource(t *testing.T) {
	ctx := context.Background()
	d, err := Prepare(ctx, agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := git(ctx, d.Config.Workspace, "rm", "engine/code.txt"); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(d.Config.Workspace, "engine"), 0755); err != nil {
		t.Fatal(err)
	}
	ignored := filepath.Join(d.Config.Workspace, ".git", "info", "exclude")
	if err := os.WriteFile(ignored, []byte("/stand-credentials\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"engine/new.txt", "stand-credentials"} {
		if err := os.WriteFile(filepath.Join(d.Config.Workspace, path), []byte("fixture"), 0640); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink("new.txt", filepath.Join(d.Config.Workspace, "engine", "reference.txt")); err != nil {
		t.Fatal(err)
	}
	candidate, err := d.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	isolated := t.TempDir()
	if err := d.copyReviewSource(ctx, isolated, candidate); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(isolated, "engine", "reference.txt"))
	if err != nil || string(data) != "fixture" {
		t.Fatal("untracked source or symlink did not reach the isolated copy", string(data), err)
	}
	for _, path := range []string{"engine/code.txt", "stand-credentials"} {
		if _, err := os.Stat(filepath.Join(isolated, path)); !os.IsNotExist(err) {
			t.Fatal("isolated copy contains staged deletion or ignored credential", path, err)
		}
	}
}

func TestParallelReviewFailureUsesFailedLensDiagnostics(t *testing.T) {
	ctx := context.Background()
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	cfg.ReviewParallelism = 2
	script := `#!/bin/sh
case "$1" in
	--version) echo 'fixture runtime'; exit ;;
	sandbox) exec git rev-parse HEAD ;;
esac
printf '%s\n' '{"type":"thread.started","thread_id":"fixture"}'
if grep -q '"lens": "correctness"'; then
	echo 'failed correctness reviewer' >&2
	printf '%s\n' '{"type":"item.completed","item":{"type":"agent_message","text":"malformed assessment"}}'
else
	echo 'healthy compatibility reviewer' >&2
	printf '%s\n' '{"type":"item.completed","item":{"type":"agent_message","text":"{\"decision\":\"approve\",\"summary\":\"Checked.\",\"findings\":[]}"}}'
fi
printf '%s\n' '{"type":"turn.completed"}'
`
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	d, err := Prepare(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := d.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	results, err := d.AssessReviewBatch(ctx, []workcycle.AgentAssessmentRequest{
		{Request: d.Request, Candidate: snapshot, Stage: workcycle.ReviewStage, Lens: "correctness"},
		{Request: d.Request, Candidate: snapshot, Stage: workcycle.ReviewStage, Lens: "compatibility"},
	})
	if err != nil || len(results) != 2 || results[0].Decision != "verification-blocked" || results[1].Decision != "approve" {
		t.Fatal(results, err)
	}
	failure := d.engineeringFailure(workcycle.AdviceInput{Stage: workcycle.ReviewStage, Failure: results[0].Summary})
	if failure.Kind != "agent_protocol" || !strings.Contains(failure.Log, "failed correctness reviewer") || strings.Contains(failure.Log, "healthy compatibility reviewer") {
		t.Fatal("failure routing used sibling diagnostics", failure)
	}
}
