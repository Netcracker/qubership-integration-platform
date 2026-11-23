package fixer

import (
	"context"
	"reflect"
	"sync"
	"testing"
)

func controllerTestHandle(t *testing.T, c *Controller, e Event) Result {
	t.Helper()
	r, err := c.Handle(context.Background(), e)
	if err != nil {
		t.Fatal(err)
	}
	return r
}
func TestControllerPersistsIntentBeforeDispatch(t *testing.T) {
	a, c := controllerTestSetup()
	a.faults["dispatch-after-accept"] = 1
	r := controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	if r.Disposition != "accepted" || a.count("perform:dispatch") != 1 {
		t.Fatalf("unexpected result: %+v, %v", r, a.trace)
	}
	if !reflect.DeepEqual(a.trace[:4], []string{"read", "write", "read", "perform:dispatch"}) {
		t.Fatal(a.trace)
	}
	controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	if a.count("perform:dispatch") != 1 {
		t.Fatal(a.trace)
	}
}
func TestControllerWriteFailures(t *testing.T) {
	for _, fault := range []string{"before-write", "read-after-write-unknown", "after-write"} {
		t.Run(fault, func(t *testing.T) {
			a, c := controllerTestSetup()
			a.faults[fault] = 1
			r := controllerTestHandle(t, c, controllerTestCommand("fix", 1))
			if fault == "after-write" {
				if r.Disposition != "accepted" || a.count("perform:dispatch") != 1 {
					t.Fatalf("%+v", r)
				}
				return
			}
			if r.Disposition != "recovery-required" || a.count("perform:dispatch") != 0 {
				t.Fatalf("%+v", r)
			}
			controllerTestHandle(t, c, controllerTestCommand("fix", 2))
			if a.count("perform:dispatch") != 0 {
				t.Fatal(a.trace)
			}
			if fault == "read-after-write-unknown" {
				recovered, err := NewController(a).Reconcile(context.Background())
				if err != nil || recovered.Disposition != "recovery-required" || a.count("perform:dispatch") != 0 {
					t.Fatalf("%+v %v", recovered, err)
				}
			}
		})
	}
}
func TestControllerReconcilesWithoutRepeatingMutation(t *testing.T) {
	a, c := controllerTestSetup()
	a.faults["observe-unknown"] = 1
	r := controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	if r.Disposition != "recovery-required" {
		t.Fatal(r)
	}
	r, err := NewController(a).Reconcile(context.Background())
	if err != nil || r.Disposition != "reconciled" || r.State.Effects[0].Status != "confirmed" || a.count("perform:dispatch") != 1 {
		t.Fatalf("%+v %v", r, err)
	}
}
func TestControllerSerializesConcurrentCommands(t *testing.T) {
	a, c := controllerTestSetup()
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			_, err := c.Handle(context.Background(), controllerTestCommand("fix", 1))
			if err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if a.count("perform:dispatch") != 1 {
		t.Fatal(a.trace)
	}
}
func TestControllerRejectsPublicAcknowledgement(t *testing.T) {
	_, c := controllerTestSetup()
	r := controllerTestHandle(t, c, Event{Type: "effect-confirmed"})
	if r.Disposition != "ignored" || r.Reason != "internal-event" {
		t.Fatal(r)
	}
}
func TestControllerBindsTaskIdentity(t *testing.T) {
	a, c := controllerTestSetup()
	controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	a.state.IssueNumber = 43
	r := controllerTestHandle(t, c, controllerTestCommand("stop", 2))
	if r.Reason != "task-context-mismatch" || a.count("perform:cancel") != 0 {
		t.Fatal(r)
	}
}
func TestControllerAcknowledgedCancelRemainsPending(t *testing.T) {
	a, c := controllerTestSetup()
	controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	a.cancelPending = true
	r := controllerTestHandle(t, c, controllerTestCommand("stop", 2))
	if r.State.Phase != "stopping" || a.count("perform:cancel") != 1 {
		t.Fatalf("%+v %v", r, a.trace)
	}
	r, err := c.Reconcile(context.Background())
	if err != nil || r.State.Phase != "stopping" || a.count("perform:cancel") != 1 {
		t.Fatalf("%+v %v", r, err)
	}
}
func TestControllerRecoveryRequiresOriginalOrExpectedState(t *testing.T) {
	a, c := controllerTestSetup()
	a.faults["read-after-write-unknown"] = 1
	controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	a.state.Revision++
	r, err := c.Reconcile(context.Background())
	if err != nil || r.Reason != "state-write-unconfirmed" {
		t.Fatalf("%+v %v", r, err)
	}
}
func TestControllerCanceledWait(t *testing.T) {
	_, c := controllerTestSetup()
	c.gate <- struct{}{}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := c.Handle(ctx, controllerTestCommand("fix", 1))
	<-c.gate
	if err != context.Canceled {
		t.Fatal(err)
	}
}

func TestControllerRejectsInvalidCanonicalState(t *testing.T) {
	a, c := controllerTestSetup()
	a.state = &State{SchemaVersion: 999}
	result := controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	if result.Reason != "invalid-state" || a.count("perform:dispatch") != 0 {
		t.Fatalf("%+v %v", result, a.trace)
	}
}

func TestControllerCallbackTrustComesFromAdapter(t *testing.T) {
	a, c := controllerTestSetup()
	started := controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	a.context.TrustedWorkflow = false
	result := controllerTestHandle(t, c, Event{Type: "run-started", Source: Source{Kind: "workflow", RunID: 1, RunAttempt: 1, CallbackID: 1}, ExecutionID: started.State.Executions[0].ID, Generation: started.State.Generation, ContractVersion: started.State.Contract.Version})
	if result.Disposition != "ignored" || result.State.Executions[0].Run != nil {
		t.Fatalf("%+v", result)
	}
}

func TestControllerReconciliationClearsFailedWriteLatch(t *testing.T) {
	a, c := controllerTestSetup()
	a.faults["before-write"] = 1
	controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	result, err := c.Reconcile(context.Background())
	if err != nil || result.Disposition != "reconciled" || result.State != nil {
		t.Fatalf("%+v %v", result, err)
	}
	result = controllerTestHandle(t, c, controllerTestCommand("fix", 1))
	if result.Disposition != "accepted" || a.count("perform:dispatch") != 1 {
		t.Fatalf("%+v", result)
	}
}
