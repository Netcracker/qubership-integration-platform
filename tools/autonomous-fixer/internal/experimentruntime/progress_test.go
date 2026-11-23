package experimentruntime

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func readProgress(t *testing.T, path string) Progress {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var p Progress
	if err := json.Unmarshal(data, &p); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestProgressParsesSplitEventsAndPreservesCommandCompletion(t *testing.T) {
	dir := t.TempDir()
	latest := filepath.Join(t.TempDir(), "latest.json")
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "codex", Stage: "implement", LatestPath: latest})
	if err != nil {
		t.Fatal(err)
	}
	w := progressWriter{recorder: p, stream: "stdout"}
	first := `{"type":"item.started","item":{"id":"cmd1","type":"command_`
	second := "execution\",\"command\":\"go test ./...\",\"status\":\"in_progress\"}}\n"
	if _, err := w.Write([]byte(first)); err != nil {
		t.Fatal(err)
	}
	initial := readProgress(t, latest)
	if initial.EventCount != 0 || initial.MalformedLines != 0 || initial.StdoutBytes != int64(len(first)) {
		t.Fatal(initial)
	}
	_, _ = w.Write([]byte(second))
	active := readProgress(t, latest)
	if len(active.ActiveCommands) != 1 || active.ActiveCommands[0].Command != "go test ./..." {
		t.Fatal(active)
	}
	done := `{"type":"item.completed","item":{"id":"cmd1","type":"command_execution","command":"go test ./...","status":"completed","exit_code":1,"aggregated_output":"test failed"}}`
	_, _ = w.Write([]byte(done))
	if err := p.finish("completed_unverified", 0); err != nil {
		t.Fatal(err)
	}
	final := readProgress(t, latest)
	if final.EventCount != 2 || final.LastCommand == nil || final.LastCommand.ExitCode == nil || *final.LastCommand.ExitCode != 1 || final.LastCommand.OutputExcerpt != "test failed" || len(final.ActiveCommands) != 0 {
		t.Fatal(final)
	}
	if final.StdoutBytes != int64(len(first)+len(second)+len(done)) {
		t.Fatal("bytes lost")
	}
	log, err := os.ReadFile(filepath.Join(dir, "progress.jsonl"))
	if err != nil || bytes.Count(log, []byte("\n")) < 4 {
		t.Fatal("progress history missing", err)
	}
}

func TestProgressHeartbeatRecordsSilenceWithoutStopping(t *testing.T) {
	dir := t.TempDir()
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "codex", Stage: "reproduce", HeartbeatInterval: 20 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	p.started(0)
	w := progressWriter{recorder: p, stream: "stdout"}
	_, _ = w.Write([]byte("{\"type\":\"turn.started\"}\n"))
	deadline := time.Now().Add(3 * time.Second)
	var observed Progress
	for time.Now().Before(deadline) {
		observed = readProgress(t, filepath.Join(dir, "progress.json"))
		if observed.IdleSeconds >= 0.04 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := p.finish("completed_unverified", 0); err != nil {
		t.Fatal(err)
	}
	if observed.IdleSeconds < 0.04 || observed.Status != "running" || observed.EventCount != 1 {
		t.Fatalf("silence lost or runtime stopped: %+v", observed)
	}
}

func TestProgressStdoutAndStderrAreConcurrent(t *testing.T) {
	dir := t.TempDir()
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "codex", Stage: "verify", HeartbeatInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for _, stream := range []string{"stdout", "stderr"} {
		wg.Add(1)
		go func(stream string) {
			defer wg.Done()
			w := progressWriter{recorder: p, stream: stream}
			for i := 0; i < 25; i++ {
				_, _ = w.Write([]byte("{\"type\":\"item.updated\"}\n"))
			}
		}(stream)
	}
	wg.Wait()
	if err := p.finish("completed_unverified", 0); err != nil {
		t.Fatal(err)
	}
	observed := readProgress(t, filepath.Join(dir, "progress.json"))
	if observed.EventCount != 25 || observed.StdoutBytes != observed.StderrBytes || observed.LastStderrAt == nil {
		t.Fatal(observed)
	}
}

func TestRunWithProgressPreservesRawCaptureAndExitStatus(t *testing.T) {
	dir := t.TempDir()
	input := "{\"type\":\"turn.started\"}\n{\"type\":\"turn.failed\"}\n"
	command := Command{Program: "/bin/sh", Args: []string{"-c", `printf '%s' "$1"; printf 'diagnostic' >&2; exit 3`, "fixture", input}}
	r, err := RunWithProgress(context.Background(), command, dir, nil, nil, nil, ProgressOptions{Runtime: "codex", Stage: "review"})
	if err == nil || r.ExitCode != 3 || r.Classification != "process_error" {
		t.Fatalf("%+v %v", r, err)
	}
	raw, e := os.ReadFile(r.StdoutPath)
	if e != nil || string(raw) != input {
		t.Fatalf("raw events changed %q: %v", raw, e)
	}
	observed := readProgress(t, filepath.Join(dir, "progress.json"))
	if observed.Status != "process_error" || observed.Stage != "review" || observed.PID <= 0 || observed.EventCount != 2 || observed.StderrBytes != 10 {
		t.Fatal(observed)
	}
}

func TestProgressWriteFailureDoesNotDiscardRuntimeOutput(t *testing.T) {
	dir := t.TempDir()
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "codex"})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(dir, "progress.json")); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "progress.json"), 0700); err != nil {
		t.Fatal(err)
	}
	w := progressWriter{recorder: p, stream: "stdout"}
	input := []byte("{\"type\":\"turn.started\"}\n")
	n, err := w.Write(input)
	if err != nil || n != len(input) {
		t.Fatal("observer interrupted runtime output", n, err)
	}
	if err := p.finish("completed_unverified", 0); err == nil {
		t.Fatal("capture failure not reported")
	}
	raw, err := os.ReadFile(filepath.Join(dir, "progress.jsonl"))
	if err != nil || !strings.Contains(string(raw), "turn.started") {
		t.Fatal("progress history lost", err)
	}
}

func TestPlainCommandProgressDoesNotInterpretJSONOutput(t *testing.T) {
	dir := t.TempDir()
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "command", Stage: "check:unit", Command: "npm test", HeartbeatInterval: 20 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	p.started(0)
	w := progressWriter{recorder: p, stream: "stdout"}
	input := "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"id\":\"fake\",\"command\":\"not the assigned command\"}}\nplain output\n{partial"
	_, _ = w.Write([]byte(input))
	active := readProgress(t, filepath.Join(dir, "progress.json"))
	if active.MalformedLines != 0 || len(active.ActiveCommands) != 1 || active.ActiveCommands[0].ID != "process" || active.ActiveCommands[0].Command != "npm test" || active.LastEventType != "stdout.received" {
		t.Fatal(active)
	}
	if err := p.finish("process_error", 2); err != nil {
		t.Fatal(err)
	}
	final := readProgress(t, filepath.Join(dir, "progress.json"))
	if final.Stage != "check:unit" || len(final.ActiveCommands) != 0 || final.LastCommand == nil || *final.LastCommand.ExitCode != 2 || final.LastCommand.Status != "failed" || final.MalformedLines != 0 || final.LastCommand.OutputExcerpt != input {
		t.Fatal(final)
	}
}

func TestRunWithPlainCommandProgressCapturesAssignedArgv(t *testing.T) {
	dir := t.TempDir()
	latest := filepath.Join(t.TempDir(), "runtime-progress.json")
	input := "plain compiler output\n{not JSON}\n"
	cmd := Command{Program: "/bin/sh", Args: []string{"-c", `printf '%s' "$1"`, "fixture", input}}
	result, err := RunWithProgress(context.Background(), cmd, dir, nil, nil, nil, ProgressOptions{Runtime: "command", Stage: "check:build", LatestPath: latest})
	if err != nil || result.ExitCode != 0 {
		t.Fatal(result, err)
	}
	raw, err := os.ReadFile(result.StdoutPath)
	if err != nil || string(raw) != input || filepath.Base(result.StdoutPath) != "stdout.log" {
		t.Fatal("plain capture changed", string(raw), err)
	}
	observed := readProgress(t, latest)
	var argv []string
	if observed.LastCommand == nil {
		t.Fatal("missing command")
	}
	if err := json.Unmarshal([]byte(observed.LastCommand.Command), &argv); err != nil {
		t.Fatal(err)
	}
	if len(argv) != len(cmd.Args)+1 || argv[0] != cmd.Program || observed.LastCommand.Status != "completed" || *observed.LastCommand.ExitCode != 0 || observed.MalformedLines != 0 || observed.LastEventType != "command.completed" {
		t.Fatal(observed)
	}
}

func TestPlainCommandProgressPreservesSilenceAndStartFailure(t *testing.T) {
	dir := t.TempDir()
	p, err := newProgressRecorder(dir, ProgressOptions{Runtime: "command", Stage: "check:slow", Command: "silent test", HeartbeatInterval: 20 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	p.started(0)
	deadline := time.Now().Add(3 * time.Second)
	var active Progress
	for time.Now().Before(deadline) {
		active = readProgress(t, filepath.Join(dir, "progress.json"))
		if active.IdleSeconds >= 0.04 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := p.finish("completed_unverified", 0); err != nil {
		t.Fatal(err)
	}
	if active.Status != "running" || active.IdleSeconds < 0.04 || len(active.ActiveCommands) != 1 {
		t.Fatal(active)
	}
	dir = t.TempDir()
	result, err := RunWithProgress(context.Background(), Command{Program: "/nonexistent-command"}, dir, nil, nil, nil, ProgressOptions{Runtime: "command", Stage: "check:missing"})
	if err == nil || result.Classification != "start_error" {
		t.Fatal(result, err)
	}
	final := readProgress(t, filepath.Join(dir, "progress.json"))
	if final.LastCommand == nil || final.LastCommand.Status != "start_error" || *final.LastCommand.ExitCode != -1 {
		t.Fatal(final)
	}
}
