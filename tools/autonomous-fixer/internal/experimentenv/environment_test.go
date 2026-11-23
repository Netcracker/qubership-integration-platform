package experimentenv

import (
	"context"
	"testing"
)

func baseline() Inventory {
	return Inventory{Context: "docker-desktop", Namespace: "cip-e2e", NamespaceUID: "uid", Deployments: []Workload{{Name: "engine", Containers: []Container{{Name: "engine", Image: "engine@sha256:abc"}}}}, Pods: []Workload{{Name: "one", Containers: []Container{{Name: "engine", Image: "engine@sha256:abc", ImageID: "sha256:abc"}}}}}
}
func TestBaselineIdentityAndImages(t *testing.T) {
	want := baseline()
	got := baseline()
	got.Pods[0].Name = "restarted"
	if e := VerifyBaseline(want, got); e != nil {
		t.Fatal(e)
	}
	got.NamespaceUID = "other"
	if e := VerifyBaseline(want, got); e == nil {
		t.Fatal("namespace replacement accepted")
	}
	got = baseline()
	got.Pods[0].Containers[0].ImageID = "sha256:other"
	if e := VerifyBaseline(want, got); e == nil {
		t.Fatal("changed image ID accepted")
	}
}
func TestDiscoverRequiresExplicitAllocation(t *testing.T) {
	if _, e := Discover(context.Background(), Config{}); e == nil {
		t.Fatal("implicit context accepted")
	}
}
func TestCleanupPlanUsesRegisteredRun(t *testing.T) {
	r := Registry{Context: "docker-desktop", Namespace: "cip-e2e", NamespaceUID: "uid", RunToken: "p00001", Artifacts: []Artifact{{Kind: "chain", ID: "chain-id", Name: "e2e-p00001-chain"}}}
	plan, e := CleanupPlan(r)
	if e != nil || plan.Executes {
		t.Fatal(plan, e)
	}
	r.Artifacts[0].Name = "another-chain"
	if _, e := CleanupPlan(r); e == nil {
		t.Fatal("unowned artifact accepted")
	}
}

func TestRestoreCommandsAreBounded(t *testing.T) {
	inv := baseline()
	inv.Deployments[0].Containers[0].Image = "ghcr.io/netcracker/engine:e2e-123456789abc"
	commands, e := BuildRestoreCommands(inv)
	if e != nil {
		t.Fatal(e)
	}
	if len(commands) != 3 || commands[0].ExpectedOutput != "uid" {
		t.Fatal(commands)
	}
	for _, bad := range []string{"--all", "engine; touch /tmp/bad", "../engine"} {
		inv.Deployments[0].Name = bad
		if _, e := BuildRestoreCommands(inv); e == nil {
			t.Fatal("unsafe name accepted", bad)
		}
	}
	inv = baseline()
	inv.Deployments[0].Containers[0].Image = "engine:latest --all"
	if _, e := BuildRestoreCommands(inv); e == nil {
		t.Fatal("unsafe image accepted")
	}
}

func TestCleanupRunTokenMatchesE2E(t *testing.T) {
	for _, token := range []string{"hosted-0001", "123456", "ABC123", "abcde", "abcdefg"} {
		r := Registry{Context: "docker-desktop", Namespace: "cip-e2e", NamespaceUID: "uid", RunToken: token}
		if _, e := CleanupPlan(r); e == nil {
			t.Fatal("invalid e2e token accepted", token)
		}
	}
	r := Registry{Context: "docker-desktop", Namespace: "cip-e2e", NamespaceUID: "uid", RunToken: "a12345", Artifacts: []Artifact{{Kind: "service", ID: "service", Name: "e2e-a12345-service"}, {Kind: "environment", ID: "env", Name: "production", Scope: "service"}}}
	if _, e := CleanupPlan(r); e != nil {
		t.Fatal(e)
	}
}
