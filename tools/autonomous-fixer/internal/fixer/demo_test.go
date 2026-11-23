package fixer

import (
	"context"
	"reflect"
	"testing"
)

func TestDemoRecoveryTrace(t *testing.T) {
	first, err := RunDemo(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	second, err := RunDemo(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(first, second) {
		t.Fatal("demo report changed between runs")
	}
	if len(first.Steps) != 6 || first.Dispatches != 1 || first.Cancellations != 1 || first.Publications != 0 {
		t.Fatalf("%+v", first)
	}
	if first.Steps[1].Disposition != "ignored" || first.Steps[3].Phase != "stopping" || first.Steps[5].Phase != "stopped" || first.Steps[5].Disposition != "ignored" {
		t.Fatalf("%+v", first.Steps)
	}
}
func TestDemoHonorsCanceledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := RunDemo(ctx)
	if err != context.Canceled {
		t.Fatal(err)
	}
}
