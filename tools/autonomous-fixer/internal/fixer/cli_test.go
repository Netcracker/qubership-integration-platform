package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

type nativeCLI struct {
	t      *testing.T
	api    *testAPI
	binary string
}
type nativeEvent struct {
	name       string
	payload    GitHubPayload
	inputs     map[string]string
	runID      int64
	checkpoint string
}
type nativeResult struct {
	summary, outputs map[string]string
	err              error
	stderr           string
}

func newNativeCLI(t *testing.T) *nativeCLI {
	t.Helper()
	api := testGitHubServer(t)
	binary := filepath.Join(t.TempDir(), "agent")
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "build", "-trimpath", "-ldflags", "-X main.githubAPIURL="+api.Server.URL, "-o", binary, "./cmd/agent")
	cmd.Dir = "../.."
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build native agent: %v\n%s", err, out)
	}
	return &nativeCLI{t: t, api: api, binary: binary}
}
func (c *nativeCLI) comment(id int64, body string) nativeEvent {
	c.api.mu.Lock()
	defer c.api.mu.Unlock()
	user := GitHubUser{ID: 13, Login: "bob", Type: "User"}
	comment := Comment{ID: id, Body: body, User: user, CreatedAt: "2026-11-06T10:00:00Z", UpdatedAt: "2026-11-06T10:00:00Z"}
	c.api.Comments = append(c.api.Comments, comment)
	return nativeEvent{name: "issue_comment", payload: GitHubPayload{Action: "created", Repository: Repository{FullName: c.api.Repository}, Issue: c.api.Issue, Comment: comment, Sender: user}}
}
func (c *nativeCLI) call(mode string, event nativeEvent) nativeResult {
	c.t.Helper()
	c.t.Logf("native agent %s (%s)", mode, event.name)
	dir := c.t.TempDir()
	eventPath, outputPath := filepath.Join(dir, "event.json"), filepath.Join(dir, "output")
	body, err := json.Marshal(event.payload)
	if err != nil {
		c.t.Fatal(err)
	}
	if err := os.WriteFile(eventPath, body, 0600); err != nil {
		c.t.Fatal(err)
	}
	if err := os.WriteFile(outputPath, nil, 0600); err != nil {
		c.t.Fatal(err)
	}
	checkpoint := event.checkpoint
	if checkpoint == "" {
		checkpoint = filepath.Join(dir, "checkpoint", "checkpoint.json")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.binary, mode)
	cmd.Dir = dir
	cmd.Env = []string{"HTTP_PROXY=http://127.0.0.1:1", "HTTPS_PROXY=http://127.0.0.1:1", "NO_PROXY=127.0.0.1,localhost,::1", "AGENT_ENABLED=true", "AGENT_MODE=fake", "GITHUB_TOKEN=local-test-token", "AGENT_VARIABLES_READ_TOKEN=variables-test-token", "GITHUB_REPOSITORY=org/repo", "GITHUB_REF=refs/heads/main", "GITHUB_ACTOR=bob", "GITHUB_TRIGGERING_ACTOR=bob", "AGENT_SCOPE_PATHS=engine", "GITHUB_EVENT_NAME=" + event.name, "GITHUB_EVENT_PATH=" + eventPath, "GITHUB_OUTPUT=" + outputPath, "AGENT_CHECKPOINT_PATH=" + checkpoint, "GITHUB_RUN_ID=" + strconv.FormatInt(event.runID, 10), "GITHUB_RUN_ATTEMPT=1"}
	for key, value := range event.inputs {
		cmd.Env = append(cmd.Env, "INPUT_"+strings.ToUpper(key)+"="+value)
	}
	var stderr strings.Builder
	cmd.Stderr = &stderr
	stdout, callErr := cmd.Output()
	result := nativeResult{summary: map[string]string{}, outputs: map[string]string{}, err: callErr, stderr: stderr.String()}
	if len(stdout) > 0 {
		if err := json.Unmarshal(stdout, &result.summary); err != nil {
			c.t.Fatalf("invalid stdout: %s: %v", stdout, err)
		}
	}
	output, err := os.ReadFile(outputPath)
	if err != nil {
		c.t.Fatal(err)
	}
	for _, line := range strings.Split(strings.TrimSpace(string(output)), "\n") {
		if key, value, ok := strings.Cut(line, "="); ok {
			result.outputs[key] = value
		}
	}
	if callErr == nil && result.summary["mode"] != mode {
		c.t.Fatalf("missing native summary: %+v", result)
	}
	return result
}
func (c *nativeCLI) ok(mode string, event nativeEvent) nativeResult {
	c.t.Helper()
	result := c.call(mode, event)
	if result.err != nil {
		c.t.Fatalf("%s failed: %v: %s (%v)", mode, result.err, result.stderr, result.summary)
	}
	return result
}
func (c *nativeCLI) worker() nativeEvent {
	c.t.Helper()
	state := actionReadState(c.t, c.api)
	e := state.Executions[len(state.Executions)-1]
	return nativeEvent{name: "workflow_dispatch", payload: GitHubPayload{Repository: Repository{FullName: c.api.Repository}}, runID: c.api.Runs[len(c.api.Runs)-1].ID, inputs: map[string]string{"issue": "1", "execution": e.ID, "generation": strconv.Itoa(e.Generation), "contract_version": strconv.Itoa(e.ContractVersion), "trusted_sha": e.TrustedSHA}}
}

// The fake REST server simulates GitHub run completion and artifact metadata; files are transferred locally.
func (c *nativeCLI) complete(conclusion string, checkpoint string) nativeEvent {
	c.api.mu.Lock()
	defer c.api.mu.Unlock()
	run := &c.api.Runs[0]
	run.Status = "completed"
	run.Conclusion = conclusion
	if checkpoint != "" {
		c.api.Artifacts = []map[string]any{{"id": 700, "name": fmt.Sprintf("agent-checkpoint-%d-%d", run.ID, run.RunAttempt), "expired": false, "workflow_run": map[string]any{"id": run.ID, "head_sha": run.HeadSHA}}}
	}
	return nativeEvent{name: "workflow_run", payload: GitHubPayload{Repository: Repository{FullName: c.api.Repository}, WorkflowRun: *run}, checkpoint: checkpoint}
}
func requireNativeFlag(t *testing.T, result nativeResult, key, value string) {
	t.Helper()
	if result.outputs[key] != value {
		t.Fatalf("%s: want %q, got %+v", key, value, result.outputs)
	}
}

func TestNativeCLIAuthorizesTriageWithoutAPICredentials(t *testing.T) {
	c := newNativeCLI(t)
	for _, example := range []struct {
		allowlist string
		trigger   string
		allowed   bool
	}{
		{"Alice, BOB", "alice", true},
		{"alice,bob,", "alice", false},
		{"alice,bob", "mallory", false},
		{"alice,bob", "", false},
	} {
		cmd := exec.Command(c.binary, "authorize")
		cmd.Env = []string{"AGENT_ALLOWED_ACTORS=" + example.allowlist, "GITHUB_ACTOR=bob", "GITHUB_TRIGGERING_ACTOR=" + example.trigger}
		if output, err := cmd.CombinedOutput(); (err == nil) != example.allowed {
			t.Fatalf("allowlist %q, triggering actor %q, error %v, output %q", example.allowlist, example.trigger, err, output)
		}
	}
}

func TestNativeCLIAcknowledgesDispatchBeforeAdmission(t *testing.T) {
	c := newNativeCLI(t)
	c.api.DispatchProvisionalTitle = true
	start := c.comment(10, "/agent fix")
	control := c.ok("control", start)
	if control.summary["phase"] != "queued" || control.summary["dispatch_receipt"] != "acknowledged" || control.summary["dispatch_run_id"] != "500" || control.summary["dispatch_http_status"] != "200" {
		t.Fatalf("missing dispatch receipt before the run is ready: %+v", control.summary)
	}
	state := actionReadState(t, c.api)
	if state.Effects[0].Status != "confirmed" || state.Effects[0].Result.RunID != 500 || state.Executions[0].Run != nil {
		t.Fatalf("dispatch receipt changed admission: %+v", state)
	}
	if c.api.count("GET", "/repos/org/repo/actions/runs/500") != 0 {
		t.Fatal("control tried to read the run before admission")
	}
	c.ok("control", start)
	c.api.DispatchProvisionalTitle = false
	worker := c.worker()
	c.api.Runs[0].HeadSHA = strings.Repeat("b", 40)
	invalid := c.call("admit", worker)
	if invalid.err == nil || !strings.Contains(invalid.stderr, "provenance") {
		t.Fatalf("admitted a worker with a mismatched commit: %+v", invalid)
	}
	c.api.Runs[0].HeadSHA = c.api.CurrentSHA
	requireNativeFlag(t, c.ok("admit", worker), "admitted", "true")
	if c.api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 {
		t.Fatal("admission or repeated delivery triggered a second dispatch")
	}
}

func TestNativeCLIDispatchFailureReportsOnlySafeDetails(t *testing.T) {
	c := newNativeCLI(t)
	c.api.DispatchRejectStatus = 403
	start := c.comment(10, "/agent fix")
	control := c.call("control", start)
	if control.err == nil || control.summary["disposition"] != "recovery-required" || control.summary["dispatch_receipt"] != "rejected" || control.summary["dispatch_http_status"] != "403" {
		t.Fatalf("missing dispatch rejection details: %+v", control)
	}
	if strings.Contains(control.stderr+fmt.Sprint(control.summary), "private diagnostic body") {
		t.Fatal("the CLI reported the raw GitHub error body")
	}
	c.call("control", start)
	if len(c.api.Runs) != 0 || c.api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 {
		t.Fatal("replaying a rejected dispatch retried the POST")
	}
}

func TestNativeCLICheckpointAnswerStop(t *testing.T) {
	c := newNativeCLI(t)
	c.api.FailDispatchResponse = true
	start := c.comment(10, "/agent fix")
	requireNativeFlag(t, c.ok("route", start), "process", "true")
	c.ok("control", start)
	c.ok("control", start)
	if len(c.api.Runs) != 1 || c.api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 {
		t.Fatal("lost dispatch response caused duplicate work")
	}
	worker := c.worker()
	requireNativeFlag(t, c.ok("admit", worker), "admitted", "true")
	worker.checkpoint = filepath.Join(t.TempDir(), "work", "checkpoint.json")
	c.ok("work", worker)
	data, err := os.ReadFile(worker.checkpoint)
	if err != nil {
		t.Fatal(err)
	}
	var checkpoint Checkpoint
	if err := json.Unmarshal(data, &checkpoint); err != nil || checkpoint.Run.RunID != worker.runID {
		t.Fatalf("invalid persisted checkpoint: %s (%v)", data, err)
	}
	callbackPath := filepath.Join(t.TempDir(), "checkpoint.json")
	if err := os.WriteFile(callbackPath, data, 0600); err != nil {
		t.Fatal(err)
	}
	callback := c.complete("success", callbackPath)
	route := c.ok("route", callback)
	requireNativeFlag(t, route, "download", "true")
	requireNativeFlag(t, route, "process", "true")
	if route.outputs["artifact_name"] != fmt.Sprintf("agent-checkpoint-%d-1", worker.runID) {
		t.Fatal(route.outputs)
	}
	before := actionReadState(t, c.api).Revision
	if err := os.WriteFile(callbackPath, []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if result := c.call("control", callback); result.err == nil {
		t.Fatal("malformed checkpoint accepted")
	}
	wrong := checkpoint
	wrong.Generation++
	wrongData, _ := json.Marshal(wrong)
	if err := os.WriteFile(callbackPath, wrongData, 0600); err != nil {
		t.Fatal(err)
	}
	if result := c.call("control", callback); result.err == nil {
		t.Fatal("stale checkpoint accepted")
	}
	if actionReadState(t, c.api).Revision != before {
		t.Fatal("invalid checkpoint advanced canonical state")
	}
	if err := os.WriteFile(callbackPath, data, 0600); err != nil {
		t.Fatal(err)
	}
	result := c.ok("control", callback)
	if result.summary["phase"] != "waiting-human" {
		t.Fatal(result.summary)
	}
	state := actionReadState(t, c.api)
	if len(state.Decisions) != 1 || state.Decisions[0].Status != "open" {
		t.Fatal("checkpoint did not open question")
	}
	q := state.Decisions[0]
	c.api.FailDispatchResponse = false
	answer := c.comment(11, fmt.Sprintf("/agent answer %s %d %d continue", q.ID, q.Version, q.ContractVersion))
	requireNativeFlag(t, c.ok("route", answer), "process", "true")
	c.ok("control", answer)
	if len(c.api.Runs) != 2 || actionReadState(t, c.api).Decisions[0].Status != "answered" {
		t.Fatal("authorized answer did not resume")
	}
	stop := c.comment(12, "/agent stop")
	requireNativeFlag(t, c.ok("route", stop), "process", "true")
	c.ok("control", stop)
	c.api.mu.Lock()
	cancelledRun := c.api.Runs[1]
	c.api.mu.Unlock()
	if cancelledRun.Status != "completed" || cancelledRun.Conclusion != "cancelled" {
		t.Fatalf("second run was not cancelled: %+v", cancelledRun)
	}
	cancelledCallback := nativeEvent{name: "workflow_run", payload: GitHubPayload{Repository: Repository{FullName: c.api.Repository}, WorkflowRun: cancelledRun}}
	requireNativeFlag(t, c.ok("route", cancelledCallback), "download", "false")
	stopped := c.ok("control", cancelledCallback)
	if stopped.summary["phase"] != "stopped" || actionReadState(t, c.api).Phase != "stopped" {
		t.Fatalf("cancelled completion did not confirm stop: %+v", stopped.summary)
	}
	c.ok("control", callback)
	requireNativeFlag(t, c.ok("route", callback), "download", "false")
	if len(c.api.Runs) != 2 || actionReadState(t, c.api).Desired != "stopped" || actionReadState(t, c.api).Phase != "stopped" {
		t.Fatal("late completion resumed stopped task")
	}
}
func TestNativeCLIFailureBeforeAdmission(t *testing.T) {
	c := newNativeCLI(t)
	c.ok("control", c.comment(10, "/agent fix"))
	callback := c.complete("failure", "")
	route := c.ok("route", callback)
	requireNativeFlag(t, route, "process", "true")
	requireNativeFlag(t, route, "download", "false")
	result := c.ok("control", callback)
	if result.summary["phase"] != "waiting-human" {
		t.Fatal(result.summary)
	}
	state := actionReadState(t, c.api)
	if len(state.Decisions) != 1 || state.Decisions[0].Reason != "environment-blocked" || state.Executions[0].Run != nil {
		t.Fatal("unadmitted failure did not open recovery question")
	}
	c.ok("control", callback)
	q := state.Decisions[0]
	answer := c.comment(11, fmt.Sprintf("/agent answer %s %d %d continue", q.ID, q.Version, q.ContractVersion))
	requireNativeFlag(t, c.ok("route", answer), "process", "true")
	c.ok("control", answer)
	state = actionReadState(t, c.api)
	if len(c.api.Runs) != 2 || state.Phase != "queued" || state.Decisions[0].Status != "answered" {
		t.Fatal("answer did not recover unadmitted failure")
	}
	c.ok("control", callback)
	if len(c.api.Runs) != 2 || len(actionReadState(t, c.api).Decisions) != 1 {
		t.Fatal("old completion changed recovery")
	}
}
