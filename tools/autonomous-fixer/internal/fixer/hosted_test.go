package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func actionHostedWorker(t *testing.T, api *testAPI, cfg ActionConfig) (ActionConfig, *Checkpoint) {
	t.Helper()
	state := actionReadState(t, api)
	execution := state.Executions[len(state.Executions)-1]
	cfg.EventName = "workflow_dispatch"
	cfg.RunID, cfg.RunAttempt = api.Runs[len(api.Runs)-1].ID, 1
	cfg.Inputs = map[string]string{"issue": "1", "execution": execution.ID, "generation": strconv.Itoa(execution.Generation), "contract_version": strconv.Itoa(execution.ContractVersion), "trusted_sha": execution.TrustedSHA, "mode": "hosted"}
	if result := actionCall(t, "admit", cfg); result.Outputs["admitted"] != "true" {
		t.Fatal("hosted worker was not admitted", result)
	}
	work := actionCall(t, "work", cfg)
	if work.Checkpoint == nil || work.Checkpoint.Mode != "hosted" || work.Checkpoint.Repository != api.Repository || work.Outputs["base_sha"] != state.Contract.BaseSHA {
		t.Fatal("hosted checkpoint is missing or does not match the trusted base", work)
	}
	return cfg, work.Checkpoint
}

func actionStartHosted(t *testing.T, api *testAPI) (ActionConfig, *Checkpoint) {
	t.Helper()
	api.Variables["AGENT_MODE"] = "hosted"
	cfg := actionComment(api, actionConfig(t, api), 10, "alice", "/agent fix")
	cfg.Mode = "hosted"
	actionCall(t, "control", cfg)
	api.Runs[0].Path += "@main"
	return actionHostedWorker(t, api, cfg)
}

func sealFixture(t *testing.T, checkpoint Checkpoint, status, summary string) (HostedResult, string) {
	t.Helper()
	dir := t.TempDir()
	write := func(name string, data any) {
		t.Helper()
		body, err := json.Marshal(data)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), body, 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("report.json", map[string]any{"status": status, "workflow": "fix-issue", "summary": summary})
	if status == "author-reported-fixed" {
		write("candidate.json", map[string]any{"version": 1, "baseSha": checkpoint.Contract.BaseSHA, "files": []map[string]string{{"path": "engine/a", "mode": "100644", "content": "aGVsbG8="}}})
	}
	result, err := SealHostedResult(checkpoint, dir)
	if err != nil {
		t.Fatal(err)
	}
	write("hosted-result.json", result)
	result, err = LoadHostedResult(dir)
	if err != nil {
		t.Fatal(err)
	}
	return result, dir
}

func actionHostedComplete(api *testAPI, cfg ActionConfig, result HostedResult) ActionConfig {
	var run WorkflowRun
	for i := range api.Runs {
		if api.Runs[i].ID == result.Checkpoint.Run.RunID {
			api.Runs[i].Status, api.Runs[i].Conclusion = "completed", "success"
			run = api.Runs[i]
		}
	}
	api.Artifacts = []map[string]any{{"id": 800, "name": hostedArtifactName(result.Checkpoint.Run), "workflow_run": map[string]any{"id": run.ID, "head_sha": run.HeadSHA}}}
	cfg.EventName, cfg.Payload.WorkflowRun, cfg.HostedResult = "workflow_run", run, &result
	return cfg
}

func TestHostedRegistersThenPublishesSelectedCandidate(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	sealed, _ := sealFixture(t, *checkpoint, "author-reported-fixed", "Fixed and exercised the reported behavior.\nOutcome: fixed")
	cfg = actionHostedComplete(api, cfg, sealed)
	if route := actionCall(t, "route", cfg); route.Outputs["download"] != "true" || route.Outputs["artifact_id"] != "800" {
		t.Fatal("unexpected candidate artifact route", route)
	}
	if result := actionCall(t, "control", cfg); result.Outputs["hosted_candidate"] != "true" || result.Outputs["candidate_run"] != "500" || result.Result.State.Phase != "awaiting-publication" {
		t.Fatal("the candidate was not available for publication", result)
	}
	cfg.Inputs["candidate_run"], cfg.Inputs["candidate_attempt"] = "500", "1"
	ctx := context.Background()
	api.Variables["AGENT_AUTO_PUBLISH"] = "false"
	if _, err := RunAction(ctx, "route-publish", cfg); err == nil {
		t.Fatal("publication proceeded without automatic opt-in")
	}
	api.Variables["AGENT_AUTO_PUBLISH"] = "true"
	foreign := cfg
	foreign.Payload.WorkflowRun.HeadSHA = strings.Repeat("b", 40)
	foreign.Payload.WorkflowRun.ID++
	if _, err := RunAction(ctx, "route-publish", foreign); err == nil {
		t.Fatal("unregistered callback obtained publication authority")
	}
	if route := actionCall(t, "route-publish", cfg); route.Outputs["download"] != "true" || route.Outputs["artifact_id"] != "800" {
		t.Fatal("authorized workflow callback was not routed", route)
	}
	manual := cfg
	manual.EventName, manual.Actor, manual.TriggeringActor = "workflow_dispatch", "bob", "bob"
	if route := actionCall(t, "route-publish", manual); route.Outputs["candidate_sha256"] != sealed.CandidateSHA256 {
		t.Fatal("manual publication was not routed", route)
	}
	api.Variables["AGENT_ALLOWED_ACTORS"] = "bob"
	if _, err := RunAction(ctx, "publish", cfg); err == nil {
		t.Fatal("published after the initiating operator lost access")
	}
	api.Variables["AGENT_ALLOWED_ACTORS"] = "alice,bob"
	oldBody := api.Issue.Body
	edited := "The issue requirements changed while the candidate was waiting."
	api.Issue.Body = &edited
	if _, err := RunAction(ctx, "publish", cfg); err == nil {
		t.Fatal("published against stale issue requirements")
	}
	api.Issue.Body = oldBody
	stop := actionComment(api, cfg, 11, "bob", "/agent stop")
	if _, err := RunAction(ctx, "publish", cfg); err == nil || !strings.Contains(err.Error(), "stop command") {
		t.Fatal("published while a stop comment was pending", stop, err)
	}
	api.Comments = api.Comments[:len(api.Comments)-1]
	api.Artifacts[0]["id"] = 801
	if _, err := RunAction(ctx, "publish", cfg); err == nil {
		t.Fatal("published a replacement artifact")
	}
	api.Artifacts[0]["id"] = 800
	if allow := actionCall(t, "publish", cfg); allow.Outputs["process"] != "true" || allow.Outputs["base_sha"] != checkpoint.Contract.BaseSHA {
		t.Fatal("publication authority was not issued", allow)
	}
	sha, branch := strings.Repeat("b", 40), hostedBranch(1, checkpoint.Run)
	cfg.HostedPublication = &HostedPublication{Number: 10, SHA: sha, Branch: branch}
	api.DraftPullRequest = map[string]any{"number": 10, "state": "open", "draft": true, "html_url": "https://github.com/org/repo/pull/10",
		"head": map[string]any{"ref": branch, "sha": sha, "repo": map[string]any{"full_name": api.Repository}},
		"base": map[string]any{"ref": "main", "sha": checkpoint.Contract.BaseSHA, "repo": map[string]any{"full_name": api.Repository}}}
	if result := actionCall(t, "confirm-publish", cfg); result.Result.State.Phase != "waiting-ci" ||
		result.Result.State.HostedPublication.Number != 10 || result.Result.State.HostedPublication.BaseRef != "main" ||
		result.Result.State.HostedPublication.BaseSHA != checkpoint.Contract.BaseSHA {
		t.Fatal("draft publication was not observed", result)
	}
	if route := actionCall(t, "route-publish", manual); route.Outputs["process"] != "false" || route.Result.Disposition != "already-published" {
		t.Fatal("published the same artifact again", route)
	}
}

func TestHostedLongIssueRequirementsAndResume(t *testing.T) {
	api := testGitHubServer(t)
	body := strings.Repeat("Details about the reported behavior.\r\n", 1400)
	api.Issue.Body = &body
	cfg, first := actionStartHosted(t, api)
	if first.Contract.Requirements != "" || first.Contract.RequirementsSHA256 != requirementsSHA256(api.Issue.Requirements()) {
		t.Fatal("the checkpoint does not contain compact issue requirements")
	}

	revised := "Updated acceptance criteria.\n" + body
	api.Issue.Body = &revised
	if result, err := RunAction(context.Background(), "work", cfg); err == nil || result.Checkpoint != nil {
		t.Fatal("the worker admitted requirements changed since dispatch")
	}
	stop := actionComment(api, cfg, 11, "alice", "/agent stop")
	actionCall(t, "control", stop)
	api.Issue.State = "closed"
	cfg.EventName, cfg.Payload.WorkflowRun = "workflow_run", api.Runs[0]
	actionCall(t, "control", cfg)
	if state := actionReadState(t, api); state.Phase != "stopped" {
		t.Fatalf("the worker did not stop: %s", state.Phase)
	}

	api.Issue.State = "open"
	api.Issue.UpdatedAt = "2026-11-06T10:04:00Z"
	cfg.EventName, cfg.Payload.Action = "issues", "edited"
	cfg.Payload.Issue, cfg.Payload.Sender = api.Issue, api.Issue.User
	cfg.Payload.Changes = map[string]json.RawMessage{"body": json.RawMessage(`{}`)}
	if result := actionCall(t, "control", cfg); result.Result.Disposition != "accepted" || len(api.Runs) != 1 {
		t.Fatal("the requirements edit did not update the stopped task")
	}
	resume := actionComment(api, cfg, 12, "alice", "/agent resume")
	actionCall(t, "control", resume)
	_, next := actionHostedWorker(t, api, resume)
	if next.ContractVersion != first.ContractVersion+1 ||
		next.Contract.Requirements != "" || next.Contract.RequirementsSHA256 != requirementsSHA256(api.Issue.Requirements()) {
		t.Fatal("the resumed checkpoint does not match the revised requirements")
	}
}

func TestHostedReportsQuestionAndCarriesAuthorizedAnswer(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	question := "Should saving an invalid entry reject it or show a warning?"
	sealed, _ := sealFixture(t, *checkpoint, "needs-human", question+"\nOutcome: needs-human")
	cfg = actionHostedComplete(api, cfg, sealed)
	result := actionCall(t, "control", cfg)
	state := result.Result.State
	if state.Phase != "waiting-human" || result.Outputs["hosted_candidate"] != "" || state.HostedCandidate != nil || len(state.Decisions) != 1 || state.Decisions[0].Question != question {
		t.Fatal("the author question was lost or treated as a candidate", result)
	}
	decision := state.Decisions[0]
	answer := "Reject saving the invalid entry."
	cfg = actionComment(api, cfg, 11, "bob", fmt.Sprintf("/agent answer %s %d %d %s", decision.ID, decision.Version, decision.ContractVersion, answer))
	actionCall(t, "control", cfg)
	_, next := actionHostedWorker(t, api, cfg)
	if next.Generation <= checkpoint.Generation || len(next.Answers) != 1 {
		t.Fatal("the next worker lost the authorized response", next)
	}
	got := next.Answers[0]
	if got.Answer != answer || got.Actor != "bob" || got.Question != question || got.QuestionID != decision.ID || got.QuestionVersion != decision.Version || got.ContractVersion != decision.ContractVersion || got.Source != `["comment","11"]` {
		t.Fatal("the response identity differs from the saved owner decision", got)
	}
}

func TestHostedReportAndPackageAreBoundToArtifact(t *testing.T) {
	api := testGitHubServer(t)
	_, checkpoint := actionStartHosted(t, api)
	_, dir := sealFixture(t, *checkpoint, "author-reported-fixed", "Author result")
	path := filepath.Join(dir, "report.json")
	if err := os.WriteFile(path, []byte(`{"workflow":"fix-issue","status":"author-reported-fixed","summary":"Rewritten"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadHostedResult(dir); err == nil {
		t.Fatal("accepted modified report bytes")
	}
	packagePath := filepath.Join(dir, "candidate.json")
	spoof := fmt.Sprintf(`{"version":1,"baseSha":%q,"files":[{"path":".github/workflows/agent-work.yaml"}]}`, checkpoint.Contract.BaseSHA)
	if err := os.WriteFile(packagePath, []byte(spoof), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := SealHostedResult(*checkpoint, dir); err == nil || !strings.Contains(err.Error(), "approved scope") {
		t.Fatal("sealed a candidate with a protected path", err)
	}
}
