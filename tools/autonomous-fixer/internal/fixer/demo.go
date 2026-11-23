package fixer

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

type DemoStep struct {
	Name        string `json:"name"`
	Phase       string `json:"phase"`
	Disposition string `json:"disposition"`
}
type DemoReport struct {
	Steps         []DemoStep `json:"steps"`
	Dispatches    int        `json:"dispatches"`
	Cancellations int        `json:"cancellations"`
	Publications  int        `json:"publications"`
}
type demoAdapter struct {
	state    *State
	accepted map[string]*EffectResult
	report   DemoReport
}

func (a *demoAdapter) ReadState(context.Context) (StateRead, error) {
	return StateRead{Status: "ok", State: cloneState(a.state)}, nil
}
func (a *demoAdapter) WriteState(_ context.Context, state *State) error {
	a.state = cloneState(state)
	return nil
}
func (a *demoAdapter) Context(context.Context, *Event) (TransitionContext, error) {
	return TransitionContext{Task: Seed{TaskID: "task-42", Repository: "Netcracker/qubership-integration-platform", IssueNumber: 42, BaseSHA: strings.Repeat("a", 40), TrustedSHA: strings.Repeat("b", 40), Requirements: "Preserve a saved name.", ScopePaths: []string{"ui"}}, AllowedActors: "alice", TrustedWorkflow: true}, nil
}
func (a *demoAdapter) Perform(_ context.Context, effect Effect) error {
	if _, exists := a.accepted[effect.ID]; exists {
		return errors.New("duplicate demo effect")
	}
	result := &EffectResult{}
	switch effect.Kind {
	case "dispatch":
		a.report.Dispatches++
		result.RunID = 1
		result.RunAttempt = 1
	case "cancel":
		a.report.Cancellations++
	case "publish":
		a.report.Publications++
		result.PullRequest = 1
	default:
		return fmt.Errorf("unknown demo effect: %s", effect.Kind)
	}
	a.accepted[effect.ID] = result
	if effect.Kind == "dispatch" {
		return errors.New("demo dispatch response lost")
	}
	return nil
}
func (a *demoAdapter) Observe(_ context.Context, effect Effect) (Observation, error) {
	if result, ok := a.accepted[effect.ID]; ok {
		return Observation{Status: "found", Result: result}, nil
	}
	return Observation{Status: "absent"}, nil
}

func RunDemo(ctx context.Context) (DemoReport, error) {
	adapter := &demoAdapter{accepted: map[string]*EffectResult{}}
	controller := NewController(adapter)
	command := func(kind string, id int64) Event {
		return Event{Type: kind, Actor: "alice", Source: Source{Kind: "comment", ID: id, Action: "created"}}
	}
	handle := func(name string, event Event) (Result, error) {
		result, err := controller.Handle(ctx, event)
		if err != nil {
			return result, err
		}
		if result.State == nil {
			return result, errors.New("demo transition returned no state")
		}
		adapter.report.Steps = append(adapter.report.Steps, DemoStep{Name: name, Phase: result.State.Phase, Disposition: result.Disposition})
		return result, nil
	}
	result, err := handle("Start; dispatch response lost", command("fix", 1))
	if err != nil {
		return adapter.report, err
	}
	result, err = handle("Deliver the same command again", command("fix", 1))
	if err != nil {
		return adapter.report, err
	}
	callback := func(kind string, id int64, state *State) Event {
		return Event{Type: kind, Source: Source{Kind: "workflow", RunID: 1, RunAttempt: 1, CallbackID: id}, ExecutionID: state.Executions[len(state.Executions)-1].ID, Generation: state.Generation, ContractVersion: state.Contract.Version}
	}
	result, err = handle("Admit the observed run", callback("run-started", 1, result.State))
	if err != nil {
		return adapter.report, err
	}
	beforeStop := cloneState(result.State)
	result, err = handle("Request stop", command("stop", 2))
	if err != nil {
		return adapter.report, err
	}
	completed := callback("run-finished", 2, beforeStop)
	completed.Result = "cancelled"
	result, err = handle("Observe run completion", completed)
	if err != nil {
		return adapter.report, err
	}
	late := callback("candidate", 3, beforeStop)
	late.Manifest = &Manifest{}
	result, err = handle("Receive the old candidate", late)
	if err != nil {
		return adapter.report, err
	}
	report := adapter.report
	if report.Dispatches != 1 || report.Cancellations != 1 || report.Publications != 0 || result.State.Phase != "stopped" || len(result.State.Executions) != 1 || report.Steps[3].Phase != "stopping" {
		return report, fmt.Errorf("demo recovery invariants failed: %+v", report)
	}
	return report, nil
}
