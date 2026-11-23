package fixer

import "testing"

func TestActorPolicyFailsClosed(t *testing.T) {
	if !Allowed(" Alice,BOB,alice ", "ALICE", "bob") {
		t.Fatal("actor policy rejected allowed users")
	}
	for _, raw := range []string{"", "alice,", "alice,bad--login", "alice bob", "alice,Kate"} {
		if Allowed(raw, "alice") {
			t.Fatalf("actor policy accepted malformed allowlist %q", raw)
		}
	}
	if Allowed("alice", "alice ") || Allowed("alice", "alice", "bob") || Allowed("alice") {
		t.Fatal("actor policy did not fail closed")
	}
}
func TestPathPolicy(t *testing.T) {
	paths := []string{"engine/src/main.go", "engine/../secret", "tools/autonomous-fixer/a.go", ".github/workflows/agent-work.yaml", ".github/workflows/build.yaml", "ui/index.ts"}
	v := CheckPaths(paths, []string{"engine/", ".github/", "tools/"}, false)
	want := []string{"noncanonical-path", "protected-path", "protected-path", "ci-change", "outside-scope"}
	if len(v) != len(want) {
		t.Fatal(v)
	}
	for i := range want {
		if v[i].Reason != want[i] {
			t.Fatal(v)
		}
	}
	if len(CheckPaths([]string{".github/workflows/agent-work.yml"}, []string{".github"}, true)) != 1 {
		t.Fatal("CI permission bypassed controller protection")
	}
}

func TestRequirementsKeyIncludesBodyAtSameTimestamp(t *testing.T) {
	first := Event{Type: "requirements", Actor: "alice", Requirements: "First edit.", Source: Source{Kind: "issue", ID: 1, Revision: 1700000000}}
	second := first
	second.Requirements = "Second edit."
	if EventKey(first) == EventKey(second) || EventKey(first) != EventKey(first) {
		t.Fatal("requirements identity does not distinguish body edits")
	}
	s, c := initial(t)
	s = apply(t, s, first, c)
	s = apply(t, s, second, c)
	if s.Contract.Version != 3 || !s.Contract.matchesRequirements(second.Requirements) {
		t.Fatal("same timestamp suppressed current requirements")
	}
	r, err := Transition(s, second, c)
	if err != nil || r.Disposition != "ignored" || r.State != s {
		t.Fatal("exact duplicate was accepted", err)
	}
}

func TestAgentAndLinkedIssueWorkflowsRemainProtected(t *testing.T) {
	for _, name := range []string{".github/workflows/agent-docs.yaml", ".github/workflows/agent-docs.yml", ".github/workflows/agent-pre-ready.yaml", ".github/workflows/agent-future.yaml", ".github/workflows/pr-linked-issue.yaml", ".github/workflows/pr-linked-issue.yml"} {
		v := CheckPaths([]string{name}, []string{".github"}, true)
		if len(v) != 1 || v[0].Reason != "protected-path" {
			t.Fatal(v)
		}
	}
}
