package fixer

import (
	"context"
	"strings"
	"testing"
)

func TestRESTControllerRecoversLostDispatchResponse(t *testing.T) {
	api := testGitHubServer(t)
	api.FailDispatchResponse = true
	adapter := api.adapter(t)
	controller := NewController(adapter)
	result, err := controller.Handle(context.Background(), Event{Type: "fix", Actor: "alice", Source: Source{Kind: "comment", ID: 3, Action: "created"}})
	if err != nil || result.Disposition == "recovery-required" {
		t.Fatalf("dispatch recovery: %+v %v", result, err)
	}
	if result.State == nil || len(result.State.Executions) != 1 || len(result.State.Effects) != 1 || result.State.Effects[0].Result == nil || result.State.Effects[0].Result.RunID != 500 {
		t.Fatalf("run was not observed: %+v", result.State)
	}
	fresh := NewController(api.adapter(t))
	if result, err = fresh.Reconcile(context.Background()); err != nil || result.Disposition == "recovery-required" {
		t.Fatalf("reconcile: %+v %v", result, err)
	}
	if api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 {
		t.Fatal("repeated accepted dispatch")
	}
}

func TestRESTControllerRetriesDispatchReadbackWithoutRedispatch(t *testing.T) {
	for _, test := range []struct {
		name       string
		missingID  bool
		malformed  bool
		lost       bool
		receipt    string
		httpStatus int
	}{
		{"Acknowledgement has no body", true, false, false, "acknowledged", 204},
		{"Acknowledgement has no usable run ID", false, true, false, "acknowledged", 200},
		{"Dispatch response is lost", false, false, true, "unconfirmed", 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			api := testGitHubServer(t)
			api.DispatchWithoutID, api.DispatchMalformed, api.FailDispatchResponse = test.missingID, test.malformed, test.lost
			api.DispatchReadMisses = 2
			adapter := api.adapter(t)
			controller := NewController(adapter)
			result, err := controller.Handle(context.Background(), Event{Type: "fix", Actor: "alice", Source: Source{Kind: "comment", ID: 3, Action: "created"}})
			if err != nil || result.Disposition != "accepted" || result.State.Effects[0].Status != "confirmed" || result.State.Effects[0].Result.RunID != 500 {
				t.Fatalf("dispatch readback did not confirm the run: %+v %v", result, err)
			}
			if api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 || api.count("GET", "/repos/org/repo/actions/workflows/agent-work.yaml/runs") != 3 {
				t.Fatal("the controller must retry only the observation", api.Trace)
			}
			if d := adapter.dispatch; d == nil || d.Receipt != test.receipt || d.DispatchHTTPStatus != test.httpStatus || d.RunID != 500 || d.Readback != "observed" {
				t.Fatalf("dispatch diagnostic does not describe the response and recovery: %+v", d)
			}
		})
	}
}

func TestRESTControllerLeavesInvisibleAcceptedDispatchPending(t *testing.T) {
	api := testGitHubServer(t)
	api.DispatchWithoutID = true
	api.DispatchReadMisses = 20
	ctx := context.Background()
	result, err := NewController(api.adapter(t)).Handle(ctx, Event{Type: "fix", Actor: "alice", Source: Source{Kind: "comment", ID: 3, Action: "created"}})
	if err != nil || result.Disposition != "accepted" || result.State.Phase != "queued" || result.State.Effects[0].Status != "pending" {
		t.Fatalf("accepted dispatch should wait for the run: %+v %v", result, err)
	}
	api.DispatchReadMisses = 0
	result, err = NewController(api.adapter(t)).Reconcile(ctx)
	if err != nil || result.Disposition != "reconciled" || result.State.Effects[0].Status != "confirmed" {
		t.Fatalf("admission could not reconcile the late run: %+v %v", result, err)
	}
	if api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 1 {
		t.Fatal("reconciling a late run repeated the dispatch")
	}
}
func TestRESTControllerRejectsDuplicateCanonicalComments(t *testing.T) {
	api := testGitHubServer(t)
	adapter := api.adapter(t)
	state, err := NewState(adapter.Task, "alice")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := EncodeStateComment(state)
	api.Comments = []Comment{{ID: 1, Body: body, User: GitHubUser{ID: api.Bot.ID, Type: "User"}}, {ID: 2, Body: body, User: GitHubUser{ID: 88, Type: "Bot"}}}
	read, err := adapter.ReadState(context.Background())
	if err != nil || read.Status != "ok" || read.State != nil {
		t.Fatalf("spoofed marker: %+v %v", read, err)
	}
	api.Comments = append(api.Comments, Comment{ID: 3, Body: body, User: api.Bot}, Comment{ID: 4, Body: body, User: api.Bot})
	result, err := NewController(adapter).Reconcile(context.Background())
	if err != nil || result.Disposition != "recovery-required" {
		t.Fatalf("duplicate state: %+v %v", result, err)
	}
	if api.count("POST", "/repos/org/repo/actions/workflows/agent-work.yaml/dispatches") != 0 {
		t.Fatal("dispatched with ambiguous state")
	}
}
func TestRESTWorkflowProvenanceUsesActualRun(t *testing.T) {
	api := testGitHubServer(t)
	adapter := api.adapter(t)
	result, err := NewController(adapter).Handle(context.Background(), Event{Type: "fix", Actor: "alice", Source: Source{Kind: "comment", ID: 1, Action: "created"}})
	if err != nil || result.State == nil {
		t.Fatal(result, err)
	}
	execution := result.State.Executions[0]
	event := Event{Type: "worker-result", ExecutionID: execution.ID, Source: Source{Kind: "workflow", RunID: api.Runs[0].ID, RunAttempt: 1}}
	transitionContext, err := adapter.Context(context.Background(), &event)
	if err != nil || !transitionContext.TrustedWorkflow {
		t.Fatal(transitionContext, err)
	}
	api.Runs[0].HeadSHA = strings.Repeat("b", 40)
	transitionContext, err = adapter.Context(context.Background(), &event)
	if err != nil || transitionContext.TrustedWorkflow {
		t.Fatal("trusted forged run SHA", transitionContext, err)
	}
}
func TestRESTCancellationWaitsForTerminalRun(t *testing.T) {
	api := testGitHubServer(t)
	api.CancellationPending = true
	adapter := api.adapter(t)
	result, err := NewController(adapter).Handle(context.Background(), Event{Type: "fix", Actor: "alice", Source: Source{Kind: "comment", ID: 1, Action: "created"}})
	if err != nil || result.State == nil {
		t.Fatal(result, err)
	}
	effect := Effect{ID: "cancel", Kind: "cancel", ExecutionID: result.State.Executions[0].ID}
	if err = adapter.Perform(context.Background(), effect); err != nil {
		t.Fatal(err)
	}
	observation, err := adapter.Observe(context.Background(), effect)
	if err != nil || observation.Status != "pending" {
		t.Fatalf("accepted cancellation is not terminal: %+v %v", observation, err)
	}
	api.Runs[0].Status = "completed"
	api.Runs[0].Conclusion = "cancelled"
	observation, err = adapter.Observe(context.Background(), effect)
	if err != nil || observation.Status != "found" || observation.Result.Conclusion != "cancelled" {
		t.Fatal(observation, err)
	}
	if api.count("POST", "/repos/org/repo/actions/runs/500/cancel") != 1 {
		t.Fatal("repeated cancellation")
	}
}
func TestRESTStopLabelSyncPreservesUnownedLabels(t *testing.T) {
	api := testGitHubServer(t)
	adapter := api.adapter(t)
	reason := StopReasons[0]
	api.Issue.Labels = append(api.Issue.Labels, Label{Name: "custom-label"}, Label{Name: "needs-human:custom-reason"}, Label{Name: "needs-human:" + StopReasons[1]})
	state, _ := NewState(adapter.Task, "alice")
	state.StopEpisodes = []StopEpisode{{ID: "episode", Reason: reason, Status: "open"}}
	if err := SyncStopLabels(context.Background(), adapter.Client, 1, state); err != nil {
		t.Fatal(err)
	}
	labels := map[string]bool{}
	for _, label := range api.Issue.Labels {
		labels[label.Name] = true
	}
	for _, name := range []string{"ready-for-agent", "custom-label", "needs-human:custom-reason", "needs-human:" + reason} {
		if !labels[name] {
			t.Errorf("removed or failed to add %q", name)
		}
	}
	if labels["needs-human:"+StopReasons[1]] {
		t.Fatal("retained obsolete owned label")
	}
}
