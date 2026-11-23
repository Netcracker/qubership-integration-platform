package fixer

import (
	"context"
	"errors"
	"strings"
)

type controllerTestAdapter struct {
	state         *State
	context       TransitionContext
	trace         []string
	accepted      map[string]*EffectResult
	faults        map[string]int
	cancelPending bool
}

func controllerTestSetup() (*controllerTestAdapter, *Controller) {
	a := &controllerTestAdapter{context: TransitionContext{Task: Seed{TaskID: "task-42", Repository: "Netcracker/qubership-integration-platform", IssueNumber: 42, BaseSHA: strings.Repeat("a", 40), TrustedSHA: strings.Repeat("b", 40), Requirements: "Preserve a saved name.", ScopePaths: []string{"ui"}}, AllowedActors: "alice,bob", TrustedWorkflow: true}, accepted: map[string]*EffectResult{}, faults: map[string]int{}}
	return a, NewController(a)
}
func controllerTestCommand(kind string, id int64) Event {
	return Event{Type: kind, Actor: "alice", Source: Source{Kind: "comment", ID: id, Action: "created"}}
}
func (a *controllerTestAdapter) fault(name string) bool {
	if a.faults[name] > 0 {
		a.faults[name]--
		return true
	}
	return false
}
func (a *controllerTestAdapter) ReadState(context.Context) (StateRead, error) {
	a.trace = append(a.trace, "read")
	if a.fault("read-unknown") {
		return StateRead{Status: "unknown"}, nil
	}
	return StateRead{State: cloneState(a.state), Status: "ok"}, nil
}
func (a *controllerTestAdapter) WriteState(_ context.Context, state *State) error {
	a.trace = append(a.trace, "write")
	if a.fault("before-write") {
		return errors.New("before-write")
	}
	a.state = cloneState(state)
	if a.fault("read-after-write-unknown") {
		a.faults["read-unknown"]++
	}
	if a.fault("after-write") {
		return errors.New("after-write")
	}
	return nil
}
func (a *controllerTestAdapter) Context(context.Context, *Event) (TransitionContext, error) {
	return a.context, nil
}
func (a *controllerTestAdapter) Perform(_ context.Context, e Effect) error {
	a.trace = append(a.trace, "perform:"+e.Kind)
	if a.accepted[e.ID] != nil {
		return errors.New("duplicate-effect")
	}
	result := &EffectResult{}
	if e.Kind == "dispatch" {
		result.RunID = int64(len(a.accepted) + 1)
		result.RunAttempt = 1
	}
	a.accepted[e.ID] = result
	if a.fault("dispatch-after-accept") {
		return errors.New("lost-response")
	}
	return nil
}
func (a *controllerTestAdapter) Observe(_ context.Context, e Effect) (Observation, error) {
	a.trace = append(a.trace, "observe:"+e.Kind)
	if a.fault("observe-unknown") {
		return Observation{Status: "unknown"}, nil
	}
	if e.Kind == "cancel" && a.cancelPending {
		return Observation{Status: "pending"}, nil
	}
	if result := a.accepted[e.ID]; result != nil {
		return Observation{Status: "found", Result: result}, nil
	}
	return Observation{Status: "absent"}, nil
}
func (a *controllerTestAdapter) count(operation string) int {
	count := 0
	for _, entry := range a.trace {
		if entry == operation {
			count++
		}
	}
	return count
}
