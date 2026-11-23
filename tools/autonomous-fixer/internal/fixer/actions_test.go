package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"testing"
)

func actionConfig(t *testing.T, api *testAPI) ActionConfig {
	t.Helper()
	client, err := NewGitHubClient(api.Repository, "test-token", api.Server.Client(), api.Server.URL)
	if err != nil {
		t.Fatal(err)
	}
	variables, err := NewGitHubClient(api.Repository, "variables-test-token", api.Server.Client(), api.Server.URL)
	if err != nil {
		t.Fatal(err)
	}
	return ActionConfig{Client: client, Variables: variables, Repository: api.Repository, Enabled: true, Mode: "fake", Ref: "refs/heads/main", ScopePaths: []string{"engine"}, Payload: GitHubPayload{Repository: Repository{FullName: api.Repository}}}
}
func actionComment(api *testAPI, cfg ActionConfig, id int64, login, body string) ActionConfig {
	user := GitHubUser{ID: 12, Login: login, Type: "User"}
	if login == "bob" {
		user.ID = 13
	}
	comment := Comment{ID: id, Body: body, User: user, CreatedAt: "2026-11-06T10:00:00Z", UpdatedAt: "2026-11-06T10:00:00Z"}
	api.Comments = append(api.Comments, comment)
	cfg.EventName = "issue_comment"
	cfg.Payload.Issue = api.Issue
	cfg.Payload.Comment = comment
	cfg.Payload.Sender = user
	cfg.Payload.Action = "created"
	return cfg
}
func actionCall(t *testing.T, mode string, cfg ActionConfig) ActionResult {
	t.Helper()
	result, err := RunAction(context.Background(), mode, cfg)
	if err != nil {
		t.Fatalf("%s: %v", mode, err)
	}
	return result
}
func actionReadState(t *testing.T, api *testAPI) *State {
	t.Helper()
	for _, comment := range api.Comments {
		if comment.User.ID == api.Bot.ID {
			state, err := DecodeStateComment(comment.Body)
			if err != nil {
				t.Fatal(err)
			}
			return state
		}
	}
	t.Fatal("canonical comment missing")
	return nil
}
func actionStart(t *testing.T, api *testAPI) (ActionConfig, *Checkpoint) {
	t.Helper()
	cfg := actionComment(api, actionConfig(t, api), 10, "alice", "/agent fix")
	if actionCall(t, "route", cfg).Outputs["process"] != "true" {
		t.Fatal("fix was not routed")
	}
	actionCall(t, "control", cfg)
	state := actionReadState(t, api)
	execution := state.Executions[0]
	cfg.EventName = "workflow_dispatch"
	cfg.RunID = api.Runs[0].ID
	cfg.RunAttempt = 1
	cfg.Inputs = map[string]string{"issue": "1", "execution": execution.ID, "generation": strconv.Itoa(execution.Generation), "contract_version": strconv.Itoa(execution.ContractVersion), "trusted_sha": execution.TrustedSHA}
	if actionCall(t, "admit", cfg).Outputs["admitted"] != "true" {
		t.Fatal("worker was not admitted")
	}
	work := actionCall(t, "work", cfg)
	if work.Checkpoint == nil {
		t.Fatal("checkpoint missing")
	}
	return cfg, work.Checkpoint
}
func actionComplete(api *testAPI, cfg ActionConfig, checkpoint *Checkpoint) ActionConfig {
	api.Runs[0].Status = "completed"
	api.Runs[0].Conclusion = "success"
	run := api.Runs[0]
	api.Artifacts = []map[string]any{{"id": int64(700), "name": fmt.Sprintf("agent-checkpoint-%d-%d", run.ID, run.RunAttempt), "expired": false, "workflow_run": map[string]any{"id": run.ID, "head_sha": run.HeadSHA}}}
	cfg.EventName = "workflow_run"
	cfg.Payload.WorkflowRun = run
	cfg.Checkpoint = checkpoint
	return cfg
}
func TestActionRESTCheckpointAnswerAndStop(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStart(t, api)
	cfg = actionComplete(api, cfg, checkpoint)
	route := actionCall(t, "route", cfg)
	if route.Outputs["download"] != "true" || route.Outputs["process"] != "true" {
		t.Fatal(route.Outputs)
	}
	actionCall(t, "control", cfg)
	state := actionReadState(t, api)
	if state.Phase != "waiting-human" || len(state.Decisions) != 1 || state.Decisions[0].Status != "open" {
		t.Fatalf("expected question: %+v", state)
	}
	question := state.Decisions[0]
	answer := actionComment(api, cfg, 11, "bob", fmt.Sprintf("/agent answer %s %d %d continue", question.ID, question.Version, question.ContractVersion))
	actionCall(t, "control", answer)
	if len(api.Runs) != 2 {
		t.Fatalf("answer did not dispatch next cycle: %d", len(api.Runs))
	}
	stop := actionComment(api, cfg, 12, "bob", "/agent stop")
	actionCall(t, "control", stop)
	before := len(api.Runs)
	late := actionCall(t, "control", cfg)
	if late.Result != nil && late.Result.State != nil && late.Result.State.Desired != "stopped" {
		t.Fatal("late completion resumed stopped state")
	}
	if actionCall(t, "route", cfg).Outputs["download"] != "false" {
		t.Fatal("late checkpoint was downloadable")
	}
	if len(api.Runs) != before {
		t.Fatal("late completion dispatched work")
	}
}
func TestActionRESTAcceptsLegacyStateAndCheckpoint(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStart(t, api)
	state := actionReadState(t, api)
	state.Contract.RequirementsSHA256 = ""
	state.Contract.Requirements = api.Issue.Requirements()
	body, err := EncodeStateComment(state)
	if err != nil {
		t.Fatal(err)
	}
	for i := range api.Comments {
		if api.Comments[i].User.ID == api.Bot.ID {
			api.Comments[i].Body = body
			break
		}
	}
	checkpoint.Contract = state.Contract
	cfg = actionComplete(api, cfg, checkpoint)
	actionCall(t, "control", cfg)
	if state := actionReadState(t, api); state.Phase != "waiting-human" {
		t.Fatalf("legacy checkpoint did not reach the next phase: %s", state.Phase)
	}
}

func TestActionRESTRejectsChangedCheckpointAndForeignArtifact(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStart(t, api)
	cfg = actionComplete(api, cfg, checkpoint)
	before := actionReadState(t, api).Revision
	wrong := *checkpoint
	wrong.Run.RunID++
	cfg.Checkpoint = &wrong
	if _, err := RunAction(context.Background(), "control", cfg); err == nil {
		t.Fatal("accepted checkpoint from another run")
	}
	wrong = *checkpoint
	wrong.ContractVersion++
	cfg.Checkpoint = &wrong
	if _, err := RunAction(context.Background(), "control", cfg); err == nil {
		t.Fatal("accepted checkpoint from another contract")
	}
	if actionReadState(t, api).Revision != before {
		t.Fatal("invalid checkpoint advanced state")
	}
	api.Artifacts[0]["workflow_run"] = map[string]any{"id": api.Runs[0].ID, "head_sha": "foreign"}
	route, err := RunAction(context.Background(), "route", cfg)
	if err == nil || route.Outputs["download"] != "false" {
		t.Fatal("allowed foreign artifact download", route, err)
	}
}
func TestActionRESTAuthorizationAndBranchGates(t *testing.T) {
	api := testGitHubServer(t)
	cfg := actionComment(api, actionConfig(t, api), 10, "mallory", "/agent fix")
	if actionCall(t, "route", cfg).Outputs["process"] != "false" {
		t.Fatal("routed unauthorized actor")
	}
	result := actionCall(t, "control", cfg)
	if result.Result != nil && result.Result.Disposition == "accepted" {
		t.Fatal("accepted unauthorized actor")
	}
	cfg = actionComment(api, cfg, 11, "alice", "/agent fix")
	api.Comments[len(api.Comments)-1].UpdatedAt = "2026-11-06T10:01:00Z"
	if actionCall(t, "route", cfg).Outputs["process"] != "false" {
		t.Fatal("routed edited comment")
	}
	cfg.EventName = "workflow_dispatch"
	cfg.Inputs = map[string]string{"issue": "1"}
	cfg.Actor = "mallory"
	cfg.TriggeringActor = "mallory"
	if actionCall(t, "route", cfg).Outputs["process"] != "false" {
		t.Fatal("routed unauthorized manual dispatch")
	}
	if _, err := RunAction(context.Background(), "control", cfg); err == nil {
		t.Fatal("accepted unauthorized manual dispatch")
	}
	cfg.Actor = "alice"
	if actionCall(t, "route", cfg).Outputs["process"] != "false" {
		t.Fatal("routed a manual rerun from an unauthorized triggering actor")
	}
	cfg.Ref = "refs/heads/feature"
	if _, err := RunAction(context.Background(), "route", cfg); err == nil {
		t.Fatal("accepted nondefault branch")
	}
	if len(api.Runs) != 0 {
		t.Fatal("authorization gate dispatched work")
	}
}
func TestActionRESTWorkRechecksRevokedAllowlist(t *testing.T) {
	api := testGitHubServer(t)
	cfg, _ := actionStart(t, api)
	api.Variables["AGENT_ALLOWED_ACTORS"] = "bob"
	before := actionReadState(t, api).Revision
	if result, err := RunAction(context.Background(), "work", cfg); err == nil || result.Checkpoint != nil {
		t.Fatal("work continued after initiator revocation")
	}
	if actionReadState(t, api).Revision != before {
		t.Fatal("rejected work advanced state")
	}
}

func TestActionRESTControlRechecksAuthorizationAfterRouting(t *testing.T) {
	api := testGitHubServer(t)
	cfg := actionComment(api, actionConfig(t, api), 10, "alice", "/agent fix")
	if actionCall(t, "route", cfg).Outputs["process"] != "true" {
		t.Fatal("fix was not routed")
	}
	api.Variables["AGENT_ALLOWED_ACTORS"] = "bob"
	result := actionCall(t, "control", cfg)
	if result.Result == nil || result.Result.Disposition != "ignored" || len(api.Runs) != 0 {
		t.Fatal("control continued after the actor was removed", result)
	}
	api.Variables["AGENT_ALLOWED_ACTORS"] = "alice,"
	if actionCall(t, "route", cfg).Outputs["process"] != "false" {
		t.Fatal("routed a command with a malformed allowlist")
	}
	delete(api.Variables, "AGENT_ALLOWED_ACTORS")
	if result, err := RunAction(context.Background(), "route", cfg); err == nil || result.Outputs["process"] != "false" {
		t.Fatal("routed a command without a live allowlist", result, err)
	}
}

func TestActionRESTRequirementsEditSurvivesUnrelatedIssueUpdate(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStart(t, api)
	originalBody := api.Issue.Body
	revised := "Updated acceptance criteria."
	api.Issue.Body, api.Issue.UpdatedAt = &revised, "2026-11-06T10:01:00Z"
	cfg.EventName, cfg.Payload.Action = "issues", "edited"
	cfg.Payload.Issue, cfg.Payload.Sender = api.Issue, api.Issue.User
	cfg.Payload.Changes = map[string]json.RawMessage{"body": json.RawMessage(`{}`)}
	oldEdit := cfg
	oldEdit.Payload.Issue.Body, oldEdit.Payload.Issue.UpdatedAt = originalBody, "2026-11-06T10:00:59Z"
	api.Issue.UpdatedAt = "2026-11-06T10:02:00Z"

	if result := actionCall(t, "route", cfg); result.Outputs["process"] != "true" {
		t.Fatal("the current requirements edit was skipped after the issue timestamp changed")
	}
	wrongIssue := cfg
	wrongIssue.Payload.Issue.ID++
	if result := actionCall(t, "route", wrongIssue); result.Outputs["process"] != "false" {
		t.Fatal("the route accepted an edit to a different issue identity")
	}
	first := actionCall(t, "control", cfg).Result
	if first == nil || first.Disposition != "accepted" || first.State.Contract.Version != checkpoint.ContractVersion+1 ||
		first.State.Contract.RequirementsSHA256 != requirementsSHA256(api.Issue.Requirements()) {
		t.Fatalf("the current edit did not update the contract: %+v", first)
	}
	api.Issue.UpdatedAt = "2026-11-06T10:03:00Z"
	if result := actionCall(t, "control", cfg).Result; result == nil || result.Reason != "Event already handled." || result.State.Revision != first.State.Revision {
		t.Fatalf("the controller did not deduplicate the edit: %+v", result)
	}

	api.Issue.Body, api.Issue.UpdatedAt = originalBody, "2026-11-06T10:04:00Z"
	if result := actionCall(t, "route", cfg); result.Outputs["process"] != "false" {
		t.Fatal("the route accepted the superseded edit")
	}
	cfg.Payload.Issue = api.Issue
	api.Issue.UpdatedAt = "2026-11-06T10:05:00Z"
	revert := actionCall(t, "control", cfg).Result
	if revert == nil || revert.Disposition != "accepted" || revert.State.Contract.Version != checkpoint.ContractVersion+2 ||
		revert.State.Contract.RequirementsSHA256 != checkpoint.Contract.RequirementsSHA256 {
		t.Fatalf("the controller did not accept the revert: %+v", revert)
	}
	if result := actionCall(t, "control", oldEdit).Result; result == nil || result.Disposition != "ignored" || result.State.Revision != revert.State.Revision {
		t.Fatalf("the delayed edit changed the contract after the revert: %+v", result)
	}
}

func TestActionRESTReadyLabelMustBeRestoredBeforeContinuing(t *testing.T) {
	api := testGitHubServer(t)
	cfg, _ := actionStart(t, api)
	api.Issue.Labels = []Label{}
	cfg.EventName = "issues"
	cfg.Payload.Issue = api.Issue
	cfg.Payload.Label = Label{Name: "ready-for-agent"}
	cfg.Payload.Action = "unlabeled"
	cfg.Payload.Sender = api.Issue.User
	api.Events = []json.RawMessage{json.RawMessage(`{"id":50,"event":"unlabeled","actor":{"id":12,"login":"alice","type":"User"},"label":{"name":"ready-for-agent"},"created_at":"2026-11-06T10:00:00Z"}`)}
	actionCall(t, "control", cfg)
	cfg.EventName = "workflow_run"
	cfg.Payload.WorkflowRun = api.Runs[0]
	actionCall(t, "control", cfg)
	if state := actionReadState(t, api); state.Phase != "stopped" {
		t.Fatal("removing the label did not stop work", state)
	}
	for i, command := range []string{"/agent resume", "/agent retry"} {
		cfg = actionComment(api, cfg, int64(11+i), "bob", command)
		result := actionCall(t, "control", cfg)
		if result.Result == nil || result.Result.Disposition != "ignored" || len(api.Runs) != 1 {
			t.Fatal("continued work without the label", command, result)
		}
	}
	api.Issue.Labels = []Label{{Name: "ready-for-agent"}}
	cfg.EventName = "issues"
	cfg.Payload.Issue = api.Issue
	cfg.Payload.Action = "labeled"
	api.Events = append(api.Events, json.RawMessage(`{"id":51,"event":"labeled","actor":{"id":13,"login":"bob","type":"User"},"label":{"name":"ready-for-agent"},"created_at":"2026-11-06T10:00:00Z"}`))
	actionCall(t, "control", cfg)
	if len(api.Runs) != 1 {
		t.Fatal("restoring the label continued work")
	}
	cfg = actionComment(api, cfg, 13, "bob", "/agent resume")
	result := actionCall(t, "control", cfg)
	if result.Result == nil || result.Result.Disposition != "accepted" || len(api.Runs) != 2 {
		t.Fatal("resume failed after restoring the label", result)
	}
}

func TestActionRESTMissingReadyLabelBlocksAnswerAndWorker(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStart(t, api)
	api.Issue.Labels = []Label{}
	if result, err := RunAction(context.Background(), "work", cfg); err == nil || result.Checkpoint != nil {
		t.Fatal("worker started after the label was removed", result, err)
	}
	api.Issue.Labels = []Label{{Name: "ready-for-agent"}}
	cfg = actionComplete(api, cfg, checkpoint)
	actionCall(t, "control", cfg)
	state := actionReadState(t, api)
	question := state.Decisions[0]
	api.Issue.Labels = []Label{}
	cfg = actionComment(api, cfg, 11, "bob", fmt.Sprintf("/agent answer %s %d %d continue", question.ID, question.Version, question.ContractVersion))
	result := actionCall(t, "control", cfg)
	if result.Result == nil || result.Result.Disposition != "ignored" || len(api.Runs) != 1 || actionReadState(t, api).Revision != state.Revision {
		t.Fatal("answer continued work after the label was removed", result)
	}
}

func TestActionRESTUnadmittedCompletionRequiresAnswer(t *testing.T) {
	for _, conclusion := range []string{"failure", "success", "cancelled"} {
		t.Run(conclusion, func(t *testing.T) {
			api := testGitHubServer(t)
			cfg := actionComment(api, actionConfig(t, api), 10, "alice", "/agent fix")
			actionCall(t, "control", cfg)
			api.Runs[0].Status = "completed"
			api.Runs[0].Conclusion = conclusion
			cfg.EventName = "workflow_run"
			cfg.Payload.WorkflowRun = api.Runs[0]
			route := actionCall(t, "route", cfg)
			if route.Outputs["process"] != "true" || route.Outputs["download"] != "false" {
				t.Fatalf("unadmitted completion routing: %+v", route.Outputs)
			}
			actionCall(t, "control", cfg)
			state := actionReadState(t, api)
			if state.Phase != "waiting-human" || len(state.Decisions) != 1 || state.Decisions[0].Reason != "environment-blocked" || state.Executions[0].Run != nil {
				t.Fatalf("unadmitted completion did not request a decision: %+v", state)
			}
			actionCall(t, "control", cfg)
			if state = actionReadState(t, api); len(state.Decisions) != 1 || len(api.Runs) != 1 {
				t.Fatal("duplicate completion created another question or execution")
			}
			retry := actionComment(api, cfg, 11, "alice", "/agent retry")
			if actionCall(t, "control", retry).Result.Disposition != "ignored" || len(api.Runs) != 1 {
				t.Fatal("retry bypassed the open question")
			}
			question := state.Decisions[0]
			answer := actionComment(api, cfg, 12, "bob", fmt.Sprintf("/agent answer %s %d %d continue", question.ID, question.Version, question.ContractVersion))
			actionCall(t, "control", answer)
			state = actionReadState(t, api)
			if len(api.Runs) != 2 || state.Phase != "queued" || state.Decisions[0].Status != "answered" {
				t.Fatalf("answer did not start a new execution: %+v", state)
			}
			actionCall(t, "control", cfg)
			if state = actionReadState(t, api); len(api.Runs) != 2 || len(state.Decisions) != 1 {
				t.Fatal("old completion changed the resumed task")
			}
		})
	}
}
