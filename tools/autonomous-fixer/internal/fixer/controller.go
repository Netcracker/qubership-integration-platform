package fixer

import (
	"context"
	"reflect"
)

// The caller serializes controllers across processes; Adapter has no compare-and-swap contract.
type Controller struct {
	adapter   Adapter
	gate      chan struct{}
	latch     *Result
	lastRead  *State
	uncertain *controllerWrite
}
type controllerWrite struct{ original, expected *State }

func NewController(adapter Adapter) *Controller {
	return &Controller{adapter: adapter, gate: make(chan struct{}, 1)}
}
func (c *Controller) acquire(ctx context.Context) error {
	select {
	case c.gate <- struct{}{}:
		if err := ctx.Err(); err != nil {
			<-c.gate
			return err
		}
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (c *Controller) recovery(state *State, reason string) Result {
	result := Result{State: cloneState(state), Disposition: "recovery-required", Reason: reason}
	copy := result
	c.latch = &copy
	return result
}
func (c *Controller) read(ctx context.Context) StateRead {
	observed, err := c.adapter.ReadState(ctx)
	if err != nil {
		return StateRead{Status: "unknown", Reason: "state-unavailable"}
	}
	if observed.Status != "ok" {
		if observed.Reason == "" {
			observed.Reason = "state-unavailable"
		}
		return StateRead{Status: "unknown", Reason: observed.Reason}
	}
	if observed.State != nil {
		if ValidateState(observed.State) != nil {
			return StateRead{Status: "unknown", Reason: "invalid-state"}
		}
	}
	c.lastRead = cloneState(observed.State)
	return observed
}
func (c *Controller) persist(ctx context.Context, expected *State) bool {
	original := cloneState(c.lastRead)
	_ = c.adapter.WriteState(ctx, cloneState(expected))
	observed := c.read(ctx)
	confirmed := observed.Status == "ok" && reflect.DeepEqual(observed.State, expected)
	if !confirmed {
		c.uncertain = &controllerWrite{original, cloneState(expected)}
	}
	return confirmed
}
func controllerBinding(state *State, context TransitionContext) string {
	if state == nil {
		return ""
	}
	if context.Task.TaskID == "" {
		return "context-missing"
	}
	if state.Repository != context.Task.Repository || state.IssueNumber != context.Task.IssueNumber || state.TaskID != context.Task.TaskID {
		return "task-context-mismatch"
	}
	return ""
}
func (c *Controller) settle(ctx context.Context, state *State, executable map[string]bool, result Result) (Result, error) {
	if state == nil {
		result.State = nil
		return result, nil
	}
	pending := append([]Effect(nil), state.Effects...)
	for _, effect := range pending {
		if effect.Status != "pending" || (!continuesAfterGeneration(effect.Kind) && effect.Generation != state.Generation) {
			continue
		}
		if err := ctx.Err(); err != nil {
			return c.recovery(state, "effect-observation-unavailable"), err
		}
		if executable[effect.ID] {
			_ = c.adapter.Perform(ctx, effect)
		}
		observation, err := c.adapter.Observe(ctx, effect)
		if err != nil {
			observation = Observation{Status: "unknown"}
		}
		if observation.Status == "pending" {
			continue
		}
		if observation.Status != "found" {
			reason := "effect-observation-unavailable"
			if observation.Status == "absent" {
				reason = "effect-not-observed"
			}
			return c.recovery(state, reason), nil
		}
		confirmed, err := Transition(state, Event{Type: "effect-confirmed", EffectID: effect.ID, EffectResult: observation.Result}, TransitionContext{Internal: true})
		if err != nil {
			return Result{}, err
		}
		if !c.persist(ctx, confirmed.State) {
			return c.recovery(state, "state-write-unconfirmed"), nil
		}
		state = confirmed.State
	}
	result.State = state
	return result, nil
}
func (c *Controller) Handle(ctx context.Context, event Event) (Result, error) {
	if err := c.acquire(ctx); err != nil {
		return Result{}, err
	}
	defer func() { <-c.gate }()
	if c.latch != nil {
		result := *c.latch
		result.State = cloneState(result.State)
		return result, nil
	}
	observed := c.read(ctx)
	if observed.Status != "ok" {
		return c.recovery(nil, observed.Reason), nil
	}
	state := observed.State
	if event.Type == "effect-confirmed" {
		return Result{State: state, Disposition: "ignored", Reason: "internal-event"}, nil
	}
	context, err := c.adapter.Context(ctx, &event)
	if err != nil {
		return c.recovery(state, "context-unavailable"), nil
	}
	if binding := controllerBinding(state, context); binding != "" {
		return c.recovery(state, binding), nil
	}
	context.Internal = false
	result, err := Transition(state, event, context)
	if err != nil {
		return Result{}, err
	}
	previous := map[string]bool{}
	if state != nil {
		for _, effect := range state.Effects {
			previous[effect.ID] = true
		}
	}
	executable := map[string]bool{}
	if result.State != nil {
		for _, effect := range result.State.Effects {
			if effect.Status == "pending" && !previous[effect.ID] {
				executable[effect.ID] = true
			}
		}
	}
	if !reflect.DeepEqual(state, result.State) && !c.persist(ctx, result.State) {
		return c.recovery(state, "state-write-unconfirmed"), nil
	}
	return c.settle(ctx, result.State, executable, result)
}
func (c *Controller) Reconcile(ctx context.Context) (Result, error) {
	if err := c.acquire(ctx); err != nil {
		return Result{}, err
	}
	defer func() { <-c.gate }()
	observed := c.read(ctx)
	if observed.Status != "ok" {
		return c.recovery(nil, observed.Reason), nil
	}
	context, err := c.adapter.Context(ctx, nil)
	if err != nil {
		return c.recovery(observed.State, "context-unavailable"), nil
	}
	if binding := controllerBinding(observed.State, context); binding != "" {
		return c.recovery(observed.State, binding), nil
	}
	if c.uncertain != nil && !reflect.DeepEqual(observed.State, c.uncertain.original) && !reflect.DeepEqual(observed.State, c.uncertain.expected) {
		return c.recovery(observed.State, "state-write-unconfirmed"), nil
	}
	result, err := c.settle(ctx, observed.State, map[string]bool{}, Result{Disposition: "reconciled"})
	if err == nil && result.Disposition != "recovery-required" {
		c.latch = nil
		c.uncertain = nil
	}
	return result, err
}
