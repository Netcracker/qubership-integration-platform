package fixer

import (
	"context"
	"testing"
)

func TestHostedReconcilesCompletedRunOnceWithHumanDispatch(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	sealed, _ := sealFixture(t, *checkpoint, "author-reported-fixed", "Fixed and tested the change.\nOutcome: fixed")
	callback := actionHostedComplete(api, cfg, sealed)
	cfg = callback
	cfg.EventName = "workflow_dispatch"
	cfg.Actor, cfg.TriggeringActor = "bob", "bob"
	cfg.Payload.WorkflowRun = WorkflowRun{}
	cfg.Inputs = map[string]string{"issue": "1", "operation": "reconcile-run", "worker_run": "500", "worker_attempt": "1"}
	if route := actionCall(t, "route", cfg); route.Outputs["download"] != "true" || route.Outputs["artifact_id"] != "800" {
		t.Fatal("manual reconciliation did not route the registered artifact", route)
	}
	result := actionCall(t, "control", cfg)
	if result.Result.State.Phase != "awaiting-publication" || result.Outputs["automatic_publish"] == "true" {
		t.Fatal("manual reconciliation must recover the candidate for manual publication", result)
	}
	actionCall(t, "control", callback)
	actionCall(t, "control", cfg)
	state := actionReadState(t, api)
	if state.Revision != result.Result.State.Revision || len(api.Runs) != 1 {
		t.Fatal("duplicate observer changed canonical state or restarted work", state)
	}
	manual := cfg
	manual.Inputs = map[string]string{"issue": "1", "candidate_run": "500", "candidate_attempt": "1"}
	if publish := actionCall(t, "publish", manual); publish.Outputs["process"] != "true" {
		t.Fatal("manual recovery did not allow publication", publish)
	}
}

func TestHostedReconciliationAuthenticatesWorkerAndOperator(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	sealed, _ := sealFixture(t, *checkpoint, "author-reported-fixed", "Fixed and tested the change.\nOutcome: fixed")
	cfg = actionHostedComplete(api, cfg, sealed)
	cfg.EventName = "workflow_dispatch"
	cfg.Payload.WorkflowRun = WorkflowRun{}
	cfg.Inputs = map[string]string{"issue": "1", "operation": "reconcile-run", "worker_run": "500", "worker_attempt": "1", "candidate_run": "500", "candidate_attempt": "1"}
	cfg.Actor, cfg.TriggeringActor = "mallory", "mallory"
	ctx := context.Background()
	if _, err := RunAction(ctx, "route", cfg); err == nil {
		t.Fatal("an unauthorized user could reconcile a worker")
	}
	cfg.Actor, cfg.TriggeringActor = api.Bot.Login, api.Bot.Login
	cfg.Payload.Sender = api.Bot
	cfg.Payload.Sender.ID++
	if _, err := RunAction(ctx, "route", cfg); err == nil {
		t.Fatal("an unverified bot could reconcile a worker")
	}
	cfg.Payload.Sender = api.Bot
	cfg.Inputs["worker_attempt"] = "2"
	if _, err := RunAction(ctx, "route", cfg); err == nil {
		t.Fatal("the notifier could reconcile a different run attempt")
	}
	cfg.Inputs["worker_attempt"] = "1"
	api.CompletionReadMisses = 1
	api.Variables["AGENT_AUTO_PUBLISH"] = "true"
	if result := actionCall(t, "control", cfg); result.Outputs["automatic_publish"] != "true" || result.Result.State.Phase != "awaiting-publication" {
		t.Fatal("the validated notification did not record the candidate", result)
	}
	if publish := actionCall(t, "publish", cfg); publish.Outputs["process"] != "true" {
		t.Fatal("trusted completion notification did not authorize publication", publish)
	}
	api.Variables["AGENT_ALLOWED_ACTORS"] = "bob"
	if _, err := RunAction(ctx, "publish", cfg); err == nil {
		t.Fatal("the bot replaced the revoked initiating actor")
	}
}
