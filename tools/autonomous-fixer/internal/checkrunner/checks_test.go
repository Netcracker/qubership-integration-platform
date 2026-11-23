package checkrunner

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestReuseBindsSourceDependenciesRecipeAndEvidence(t *testing.T) {
	workspace := t.TempDir()
	evidence := t.TempDir()
	dep := filepath.Join(workspace, "dependency.js")
	os.WriteFile(dep, []byte("v1"), 0600)
	r := Recipe{ID: "unit", Scope: "ui", Command: []string{"sh", "-c", "echo run"}, CWD: ".", DependencyRoots: []string{"dependency.js"}, ToolchainCommands: [][]string{{"sh", "-c", "printf version"}}}
	run := func(candidate string) Record {
		t.Helper()
		record, e := Run(context.Background(), workspace, candidate, evidence, r, os.Environ())
		if e != nil {
			t.Fatal(e)
		}
		return record
	}
	first := run("a")
	second := run("a")
	if first.ReusedFrom != "" || second.ReusedFrom != first.Path {
		t.Fatal(first, second)
	}
	if run("b").ReusedFrom != "" {
		t.Fatal("source change reused")
	}
	os.WriteFile(dep, []byte("v2"), 0600)
	if run("a").ReusedFrom != "" {
		t.Fatal("dependency change reused")
	}
	r.ToolchainCommands = [][]string{{"sh", "-c", "printf version2"}}
	if run("a").ReusedFrom != "" {
		t.Fatal("toolchain change reused")
	}
	r.Command = []string{"sh", "-c", "echo other"}
	newest := run("a")
	if newest.ReusedFrom != "" {
		t.Fatal("recipe change reused")
	}
	os.WriteFile(newest.Stdout, []byte("tampered"), 0600)
	if _, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ()); e == nil {
		t.Fatal("changed evidence accepted")
	}
}
func TestFailedCheckIsNeverReused(t *testing.T) {
	workspace, evidence := t.TempDir(), t.TempDir()
	r := Recipe{ID: "unit", Scope: "ui", CWD: ".", Command: []string{"sh", "-c", "echo failure; exit 1"}, ToolchainCommands: [][]string{{"sh", "-c", "printf version"}}}
	for i := 0; i < 2; i++ {
		record, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
		if e != nil || record.ExitCode != 1 || record.ReusedFrom != "" {
			t.Fatal(record, e)
		}
	}
}
func TestSymlinkedDependencyChangesInvalidate(t *testing.T) {
	workspace, evidence, external := t.TempDir(), t.TempDir(), t.TempDir()
	file := filepath.Join(external, "index.js")
	os.WriteFile(file, []byte("one"), 0600)
	os.Symlink(external, filepath.Join(workspace, "dependency"))
	r := Recipe{ID: "unit", Scope: "ui", CWD: ".", Command: []string{"sh", "-c", "echo pass"}, DependencyRoots: []string{"dependency"}, ToolchainCommands: [][]string{{"sh", "-c", "printf version"}}}
	first, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
	if e != nil {
		t.Fatal(e)
	}
	os.WriteFile(file, []byte("two"), 0600)
	second, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
	if e != nil || second.ReusedFrom != "" || second.InputSHA256 == first.InputSHA256 {
		t.Fatal(second, e)
	}
}

func TestConfiguredPATHIdentifiesActualToolchain(t *testing.T) {
	workspace, evidence, tools := t.TempDir(), t.TempDir(), t.TempDir()
	tool := filepath.Join(tools, "check-tool")
	os.WriteFile(tool, []byte("#!/bin/sh\nif [ \"$1\" = --version ]; then echo v1; else echo pass; fi\n"), 0700)
	r := Recipe{ID: "unit", Scope: "ui", CWD: ".", Command: []string{"sh", "-c", "check-tool"}, ToolchainCommands: [][]string{{"check-tool", "--version"}}}
	env := []string{"PATH=" + tools + ":/usr/bin:/bin"}
	first, e := Run(context.Background(), workspace, "a", evidence, r, env)
	if e != nil || first.ExitCode != 0 {
		t.Fatal(first, e)
	}
	os.WriteFile(tool, []byte("#!/bin/sh\nif [ \"$1\" = --version ]; then echo v1; else exit 1; fi\n"), 0700)
	second, e := Run(context.Background(), workspace, "a", evidence, r, env)
	if e != nil || second.ExitCode != 1 || second.ReusedFrom != "" {
		t.Fatal(second, e)
	}
}
func TestWorkspaceLinkExcludesCoverageButIncludesDeclaredBuildInputs(t *testing.T) {
	workspace, evidence := t.TempDir(), t.TempDir()
	for _, dir := range []string{"ui/coverage", "node_modules/@qip", "schemas/dist"} {
		os.MkdirAll(filepath.Join(workspace, dir), 0700)
	}
	os.Symlink(filepath.Join(workspace, "ui"), filepath.Join(workspace, "node_modules/@qip/ui"))
	generated := filepath.Join(workspace, "schemas/dist/index.js")
	os.WriteFile(generated, []byte("v1"), 0600)
	r := Recipe{ID: "unit", Scope: "ui", CWD: ".", Command: []string{"sh", "-c", "echo coverage >> ui/coverage/report; echo pass"}, ToolchainCommands: [][]string{{"sh", "-c", "printf version"}}, DependencyRoots: []string{"node_modules"}, SourceModules: []string{"ui", "schemas"}, InputFiles: []string{"schemas/dist"}}
	first, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
	if e != nil || first.InputSHA256 == "" {
		t.Fatal(first, e)
	}
	second, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
	if e != nil || second.ReusedFrom == "" {
		t.Fatal(second, e)
	}
	os.WriteFile(generated, []byte("v2"), 0600)
	third, e := Run(context.Background(), workspace, "a", evidence, r, os.Environ())
	if e != nil || third.ReusedFrom != "" {
		t.Fatal(third, e)
	}
}

func TestCancellationStopsCheckDescendants(t *testing.T) {
	workspace, evidence := t.TempDir(), t.TempDir()
	r := Recipe{ID: "unit", Scope: "ui", CWD: ".", Command: []string{"sh", "-c", "(sleep 0.3; echo stale > child-marker) & echo ready; wait"}, ToolchainCommands: [][]string{{"true"}}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, e := Run(ctx, workspace, "a", evidence, r, os.Environ()); done <- e }()
	deadline := time.Now().Add(2 * time.Second)
	for {
		paths, _ := filepath.Glob(filepath.Join(evidence, "check-unit-*", "stdout.log"))
		ready := false
		for _, p := range paths {
			b, _ := os.ReadFile(p)
			ready = ready || strings.Contains(string(b), "ready")
		}
		if ready {
			break
		}
		if time.Now().After(deadline) {
			cancel()
			t.Fatal("check did not start")
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if e := <-done; e == nil {
		t.Fatal("cancellation accepted")
	}
	time.Sleep(400 * time.Millisecond)
	if _, e := os.Stat(filepath.Join(workspace, "child-marker")); !os.IsNotExist(e) {
		t.Fatal("check descendant survived cancellation")
	}
}
