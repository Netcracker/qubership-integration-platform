package tasktriage

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestDiscoveryUsesCommittedCodeAndBindsRevision(t *testing.T) {
	root := t.TempDir()
	if e := os.MkdirAll(filepath.Join(root, "engine"), 0700); e != nil {
		t.Fatal(e)
	}
	path := filepath.Join(root, "engine", "Log.java")
	if e := os.WriteFile(path, []byte("session_log_details baseline\n"), 0600); e != nil {
		t.Fatal(e)
	}
	for _, args := range [][]string{{"init", "-q"}, {"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "baseline"}} {
		c := exec.Command("git", args...)
		c.Dir = root
		if b, e := c.CombinedOutput(); e != nil {
			t.Fatalf("%s %v", b, e)
		}
	}
	if e := os.WriteFile(path, []byte("session_log_details uncommitted_secret\n"), 0600); e != nil {
		t.Fatal(e)
	}
	p, e := Discover(context.Background(), root, Packet{TaskID: "test", Phase: "intake", Task: "Add session_log_details to JSON logs."})
	if e != nil || len(p.SourceHints) != 1 || len(p.BaseSHA) != 40 {
		t.Fatalf("%+v %v", p, e)
	}
	if !strings.Contains(p.SourceHints[0], "baseline") || strings.Contains(p.SourceHints[0], "secret") {
		t.Fatal(p.SourceHints)
	}
	p.BaseSHA = strings.Repeat("0", 40)
	if _, e = Discover(context.Background(), root, p); e == nil {
		t.Fatal("accepted wrong source revision")
	}
}

func TestDiscoveryFindsExistingEnumForNewSnakeCaseField(t *testing.T) {
	root := t.TempDir()
	if e := os.MkdirAll(filepath.Join(root, "engine/src/main"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(root, "engine/src/main/Details.java"), []byte("public enum SessionLogDetails { OFF, FULL }\n"), 0600); e != nil {
		t.Fatal(e)
	}
	for _, args := range [][]string{{"init", "-q"}, {"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "baseline"}} {
		c := exec.Command("git", args...)
		c.Dir = root
		if b, e := c.CombinedOutput(); e != nil {
			t.Fatalf("%s %v", b, e)
		}
	}
	p, e := Discover(context.Background(), root, Packet{TaskID: "t", Phase: "intake", Task: "Add field session_log_details to JSON logs, with the existing enum values."})
	if e != nil || len(p.SourceHints) != 1 || !strings.Contains(p.SourceHints[0], "SessionLogDetails") {
		t.Fatalf("%+v %v", p, e)
	}
}

func TestDiscoverySelectsNamedDefinitionAndCallerInsteadOfSubstringNoise(t *testing.T) {
	root := t.TempDir()
	files := map[string]string{
		"ui/src/pages/Chains.tsx":        "import React from 'react';\nexport const Chains = () => {\n  return <div>Chains</div>;\n};\n",
		"ui/src/routes.tsx":              "const routes = [{path: '/chains', element: <Chains />}];\n",
		"ui/src/components/ai/events.ts": "const chainListChanged = 'chainListChanged';\n",
		"ui/pom.xml":                     "<url>https://example.org/Chains</url>\n",
	}
	for name, body := range files {
		path := filepath.Join(root, name)
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, args := range [][]string{{"init", "-q"}, {"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "baseline"}} {
		cmd := exec.Command("git", args...)
		cmd.Dir = root
		if b, err := cmd.CombinedOutput(); err != nil {
			t.Fatal(string(b), err)
		}
	}
	p, err := Discover(context.Background(), root, Packet{TaskID: "878", Phase: "intake", Task: "Add navigation on ui/src/pages/Chains.tsx"})
	if err != nil {
		t.Fatal(err)
	}
	hints := strings.Join(p.SourceHints, "\n")
	if !strings.Contains(hints, "<div>Chains</div>") || !strings.Contains(hints, "routes.tsx") || strings.Contains(hints, "chainListChanged") || strings.Contains(hints, "pom.xml") {
		t.Fatal(hints)
	}
	if len(p.ConsumerHints) != 1 || p.ConsumerHints[0].Consumer != "vscode-webview" {
		t.Fatal(p.ConsumerHints)
	}
}
