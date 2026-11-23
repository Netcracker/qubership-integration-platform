package experimentruntime

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestBuildCommand(t *testing.T) {
	c, err := BuildCommand(Profile{"codex", "gpt-6", "high"}, "/workspace", "prompt")
	if err != nil || c.Stdin != "prompt" || !strings.Contains(strings.Join(c.Args, " "), `model_reasoning_effort="high"`) {
		t.Fatalf("%+v %v", c, err)
	}
	c, err = BuildCommand(Profile{"opencode", "openai/gpt-6", "high"}, "/workspace", "-prompt")
	if err != nil || strings.Join(c.Args, " ") != "run --standalone --auto --format json --model openai/gpt-6#high -- -prompt" {
		t.Fatalf("%+v %v", c, err)
	}
	for _, p := range []Profile{{"other", "x", "high"}, {"codex", "x", "invalid"}, {"opencode", "x", "high"}, {"opencode", "a/b#high", "high"}} {
		if _, err := BuildCommand(p, "/workspace", "x"); err == nil {
			t.Fatalf("accepted %+v", p)
		}
	}
}

func TestRunCapturesWithoutInferringSuccess(t *testing.T) {
	t.Setenv("EXPERIMENT_SECRET", "must-not-inherit")
	shell, err := exec.LookPath("sh")
	if err != nil {
		t.Fatal(err)
	}
	var out, stderr bytes.Buffer
	dir := t.TempDir()
	result, err := Run(context.Background(), Command{Program: shell, Args: []string{"-c", `printf '{"type":"error"}\n'; printf diagnostic >&2; test -z "$EXPERIMENT_SECRET"; test "$HOME" = /explicit-home`}}, dir, []string{"HOME=/explicit-home"}, &out, &stderr)
	if err != nil || result.ExitCode != 0 || result.Classification != "completed_unverified" {
		t.Fatalf("%+v %v", result, err)
	}
	raw, err := os.ReadFile(result.StdoutPath)
	if err != nil || string(raw) != out.String() || stderr.String() != "diagnostic" {
		t.Fatalf("capture %q %v", raw, err)
	}
	if result.Duration <= 0 {
		t.Fatal("missing duration")
	}
	if result.StartedAt.Location() != time.UTC || result.FinishedAt.Location() != time.UTC {
		t.Fatal("timestamps must use UTC")
	}
	report, err := ParseEvents(bytes.NewReader(raw), "codex")
	if err != nil || report.Outcome != "runtime_error" {
		t.Fatalf("exit zero concealed event error: %+v %v", report, err)
	}
}

func TestRunCancellationTerminatesDescendant(t *testing.T) {
	shell, err := exec.LookPath("sh")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	marker := filepath.Join(t.TempDir(), "descendant-survived")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := make(chan struct{}, 1)
	go func() {
		select {
		case <-ready:
			cancel()
		case <-time.After(5 * time.Second):
			cancel()
		}
	}()
	result, err := Run(ctx, Command{Program: shell, Args: []string{"-c", `(sleep 0.3; touch "$1") & printf ready; wait`, "sh", marker}}, dir, nil, signalWriter{ready}, nil)
	if err == nil || result.Classification != "canceled" {
		t.Fatalf("%+v %v", result, err)
	}
	time.Sleep(400 * time.Millisecond)
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("descendant survived cancellation")
	}
}

func TestRunCompletionTerminatesDescendant(t *testing.T) {
	shell, err := exec.LookPath("sh")
	if err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(t.TempDir(), "descendant-survived")
	result, err := Run(context.Background(), Command{Program: shell, Args: []string{"-c", `(sleep 0.3; touch "$1") </dev/null >/dev/null 2>&1 & exit 0`, "sh", marker}}, t.TempDir(), nil, nil, nil)
	if err != nil || result.ExitCode != 0 || result.Classification != "completed_unverified" {
		t.Fatalf("%+v %v", result, err)
	}
	time.Sleep(400 * time.Millisecond)
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("descendant survived normal parent completion")
	}
}

type signalWriter struct{ ready chan struct{} }

func (w signalWriter) Write(p []byte) (int, error) {
	select {
	case w.ready <- struct{}{}:
	default:
	}
	return len(p), nil
}

func TestRunStartError(t *testing.T) {
	result, err := Run(context.Background(), Command{Program: "/nonexistent-experiment-runtime"}, t.TempDir(), nil, nil, nil)
	if err == nil || result.Classification != "start_error" || result.ExitCode != -1 {
		t.Fatalf("%+v %v", result, err)
	}
}

func TestRunUsesHostWorkspaceAndSeparateCaptureDirectory(t *testing.T) {
	workspace := t.TempDir()
	capture := t.TempDir()
	if err := os.WriteFile(filepath.Join(workspace, "payload.txt"), []byte("host-workspace"), 0600); err != nil {
		t.Fatal(err)
	}
	r, err := Run(context.Background(), Command{Program: "/bin/sh", Args: []string{"-c", "cat payload.txt"}, Workspace: workspace}, capture, []string{"PATH=/usr/bin:/bin"}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(r.StdoutPath)
	if err != nil || string(b) != "host-workspace" || filepath.Dir(r.StdoutPath) != capture {
		t.Fatalf("%q %+v %v", b, r, err)
	}
}
