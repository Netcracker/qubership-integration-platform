package experiment

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func readinessFixture(t *testing.T, root string, p Prepared) string {
	t.Helper()
	plan, e := Load(root)
	if e != nil {
		t.Fatal(e)
	}
	file := filepath.Join(t.TempDir(), "fixture-evidence.txt")
	if e = os.WriteFile(file, []byte("Synthetic execution evidence for a fake CLI, not product verification."), 0600); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(file)
	if e != nil {
		t.Fatal(e)
	}
	r := Readiness{SourceSHA: plan.Revision, Issue: p.Issue, Profile: p.Profile, ModelAccessEvidence: file, ContextEvidence: file, EnvironmentEvidence: file, EvidenceHashes: map[string]string{file: Hash(b)}, NetworkAccess: "shared-host-network"}
	path := filepath.Join(t.TempDir(), "readiness.json")
	if e = WriteJSON(path, r); e != nil {
		t.Fatal(e)
	}
	return path
}

func fakeRuntime(t *testing.T, body string) string {
	t.Helper()
	root := t.TempDir()
	if e := os.Mkdir(filepath.Join(root, "bin"), 0700); e != nil {
		t.Fatal(e)
	}
	for _, runtime := range []string{"codex", "opencode"} {
		version := map[string]string{"codex": "codex-cli 0.166.0", "opencode": "opencode v2.2.6"}[runtime]
		header := "#!/bin/sh\nset -eu\nif [ \"${1:-}\" = --version ]; then printf '%s\\n' '" + version + "'; exit 0; fi\n"
		if e := os.WriteFile(filepath.Join(root, "bin", runtime), []byte(header+body), 0700); e != nil {
			t.Fatal(e)
		}
	}
	return root
}

func TestExecuteFakeRuntimeAndRecordEvents(t *testing.T) {
	ctx := context.Background()
	root, _ := campaign(t)
	p, e := Prepare(ctx, root, "1215-o3-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	readiness := readinessFixture(t, root, p)
	node := fakeRuntime(t, `test -f AGENTS.md
test -d "$HOME"
printf 'fixed\n' > product.txt
printf '%s\n' '{"type":"text","part":{"type":"text","text":"candidate"}}' '{"type":"step_finish","part":{"reason":"stop"}}'
`)
	r, e := Execute(ctx, root, p.JobID, p.Role, ToolPaths{Node: node}, readiness, "", "")
	if e != nil {
		t.Fatal(e)
	}
	if r.Outcome != "unverified" || r.Process.ExitCode != 0 || !r.Events.TextObserved || r.Events.TerminalObserved {
		t.Fatalf("invalid evidence: %+v", r)
	}
	if _, e = Capture(ctx, root, p.JobID); e != nil {
		t.Fatal(e)
	}
	if _, e = Execute(ctx, root, p.JobID, p.Role, ToolPaths{Node: node}, readiness, "", ""); e == nil {
		t.Fatal("executed a run twice")
	}
}

func TestRuntimeErrorAtExitZeroRemainsFailure(t *testing.T) {
	root, _ := campaign(t)
	ctx := context.Background()
	p, e := Prepare(ctx, root, "1215-o3-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	node := fakeRuntime(t, `printf '%s\n' '{"type":"error","error":{"message":"provider unavailable"}}'
`)
	r, e := Execute(ctx, root, p.JobID, p.Role, ToolPaths{Node: node}, readinessFixture(t, root, p), "", "")
	if e != nil {
		t.Fatal(e)
	}
	if r.Outcome != "runtime_error" {
		t.Fatal("runtime error was lost")
	}
}

func TestReadinessRejectsAnotherModelAndModifiedEvidence(t *testing.T) {
	root, _ := campaign(t)
	p, e := Prepare(context.Background(), root, "1215-o3-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	path := readinessFixture(t, root, p)
	var r Readiness
	if e = ReadJSON(path, &r); e != nil {
		t.Fatal(e)
	}
	plan, e := Load(root)
	if e != nil {
		t.Fatal(e)
	}
	r.Profile.Effort = "high"
	if e = r.Validate(plan, p); e == nil {
		t.Fatal("accepted another profile")
	}
	r.Profile = p.Profile
	if e = os.WriteFile(r.ModelAccessEvidence, []byte("changed"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = r.Validate(plan, p); e == nil {
		t.Fatal("accepted changed evidence")
	}
}

func TestSubscriptionLoginIsIsolatedAndAPIKeysRejected(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "source.json")
	dest := filepath.Join(dir, "copy.json")
	original := []byte(`{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"access_token":"fixture-token"}}`)
	if e := os.WriteFile(source, original, 0600); e != nil {
		t.Fatal(e)
	}
	cleanup, e := copyChatGPTLogin(source, dest)
	if e != nil {
		t.Fatal(e)
	}
	cleanup()
	if _, e = os.Stat(dest); !os.IsNotExist(e) {
		t.Fatal("copied login remained")
	}
	b, e := os.ReadFile(source)
	if e != nil {
		t.Fatal(e)
	}
	if string(b) != string(original) {
		t.Fatal("source login changed")
	}
	if e = os.WriteFile(source, []byte(`{"auth_mode":"apikey","OPENAI_API_KEY":"fixture-token"}`), 0600); e != nil {
		t.Fatal(e)
	}
	if cleanup, e = copyChatGPTLogin(source, dest); e == nil {
		cleanup()
		t.Fatal("accepted API billing")
	}
}
