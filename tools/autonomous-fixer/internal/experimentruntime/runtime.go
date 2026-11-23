package experimentruntime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type Profile struct{ Runtime, Model, Effort string }
type Command struct {
	Program   string
	Args      []string
	Stdin     string
	Workspace string
}

// BuildCommand prepares a direct host invocation with fixed model and configuration settings.
func BuildCommand(p Profile, workspace, prompt string) (Command, error) {
	if !filepath.IsAbs(workspace) || p.Model == "" || strings.ContainsAny(p.Model+p.Effort, "\r\n\x00") {
		return Command{}, errors.New("absolute workspace and model are required")
	}
	c := Command{Program: p.Runtime, Workspace: workspace}
	switch p.Runtime {
	case "codex":
		switch p.Effort {
		case "none", "minimal", "low", "medium", "high", "xhigh":
		default:
			return Command{}, fmt.Errorf("unsupported Codex effort %q", p.Effort)
		}
		c.Args = []string{"--no-daemon", "exec", "--ignore-user-config", "--ignore-rules", "--json", "--color", "never", "--sandbox", "danger-full-access", "--cd", workspace, "--model", p.Model, "-c", "approval_policy=\"never\"", "-c", "model_reasoning_effort=" + strconv.Quote(p.Effort), "-c", "projects." + strconv.Quote(workspace) + ".trust_level=\"untrusted\"", "-c", "project_doc_max_bytes=65536"}
		for _, feature := range []string{"hooks", "multi_agent", "multi_agent_v2", "plugins", "remote_plugin", "skill_mcp_dependency_install"} {
			c.Args = append(c.Args, "--disable", feature)
		}
		c.Args = append(c.Args, "-")
		c.Stdin = prompt
	case "opencode":
		if !strings.Contains(p.Model, "/") || strings.Contains(p.Model, "#") || strings.ContainsAny(p.Effort, "# /\t") {
			return Command{}, errors.New("OpenCode requires provider/model and a separate variant")
		}
		model := p.Model
		if p.Effort != "" {
			model += "#" + p.Effort
		}
		c.Args = []string{"run", "--standalone", "--auto", "--format", "json", "--model", model, "--", prompt}
	default:
		return Command{}, fmt.Errorf("unsupported runtime %q", p.Runtime)
	}
	return c, nil
}

type Result struct {
	ExitCode         int
	StartedAt        time.Time
	FinishedAt       time.Time
	Duration         time.Duration
	ProcessUserCPU   time.Duration
	ProcessSystemCPU time.Duration
	Classification   string
	StdoutPath       string
	StderrPath       string
}

// Run records process completion only. Runtime events and task outcomes require separate validation.
// The supplied environment is explicit; credentials, if needed, must be supplied by the caller.
func Run(ctx context.Context, command Command, directory string, env []string, stdout, stderr io.Writer) (Result, error) {
	return run(ctx, command, directory, env, stdout, stderr, nil)
}

func RunWithProgress(ctx context.Context, command Command, directory string, env []string, stdout, stderr io.Writer, options ProgressOptions) (Result, error) {
	if options.Runtime == "command" && options.Command == "" {
		argv, err := json.Marshal(append([]string{command.Program}, command.Args...))
		if err != nil {
			return Result{}, err
		}
		options.Command = string(argv)
	}
	return run(ctx, command, directory, env, stdout, stderr, &options)
}

func run(ctx context.Context, command Command, directory string, env []string, stdout, stderr io.Writer, options *ProgressOptions) (r Result, err error) {
	r = Result{ExitCode: -1, Classification: "start_error"}
	if !filepath.IsAbs(directory) {
		return r, errors.New("capture directory must be absolute")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return r, err
	}
	r.StdoutPath = filepath.Join(directory, "stdout.jsonl")
	if options != nil && options.Runtime == "command" {
		r.StdoutPath = filepath.Join(directory, "stdout.log")
	}
	r.StderrPath = filepath.Join(directory, "stderr.log")
	out, err := os.OpenFile(r.StdoutPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return r, err
	}
	defer out.Close()
	errout, err := os.OpenFile(r.StderrPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return r, err
	}
	defer errout.Close()
	var progress *progressRecorder
	if options != nil {
		progress, err = newProgressRecorder(directory, *options)
		if err != nil {
			return r, err
		}
		defer func() {
			if progressErr := progress.finish(r.Classification, r.ExitCode); progressErr != nil {
				err = errors.Join(err, fmt.Errorf("record runtime progress: %w", progressErr))
				r.Classification = "capture_error"
			}
		}()
	}
	cmd := exec.CommandContext(ctx, command.Program, command.Args...)
	cmd.Dir = directory
	if command.Workspace != "" {
		if !filepath.IsAbs(command.Workspace) {
			return r, errors.New("command workspace must be absolute")
		}
		cmd.Dir = command.Workspace
	}
	cmd.Env = append([]string{}, env...)
	cmd.Stdin = strings.NewReader(command.Stdin)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		if errors.Is(err, syscall.ESRCH) {
			return os.ErrProcessDone
		}
		return err
	}
	cmd.WaitDelay = time.Second
	if stdout == nil {
		stdout = io.Discard
	}
	if stderr == nil {
		stderr = io.Discard
	}
	cmd.Stdout = io.MultiWriter(out, stdout)
	cmd.Stderr = io.MultiWriter(errout, stderr)
	if progress != nil {
		cmd.Stdout = io.MultiWriter(cmd.Stdout, progressWriter{recorder: progress, stream: "stdout"})
		cmd.Stderr = io.MultiWriter(cmd.Stderr, progressWriter{recorder: progress, stream: "stderr"})
	}
	started := time.Now()
	r.StartedAt = started.UTC()
	err = cmd.Start()
	if err == nil {
		if progress != nil {
			progress.started(cmd.Process.Pid)
		}
		err = cmd.Wait()
		// The leader can exit while detached children still occupy its process group.
		cleanupErr := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		if cleanupErr != nil && !errors.Is(cleanupErr, syscall.ESRCH) {
			err = errors.Join(err, fmt.Errorf("terminate remaining process group: %w", cleanupErr))
		}
	}
	finished := time.Now()
	r.FinishedAt = finished.UTC()
	r.Duration = finished.Sub(started)
	if cmd.ProcessState != nil {
		r.ProcessUserCPU = cmd.ProcessState.UserTime()
		r.ProcessSystemCPU = cmd.ProcessState.SystemTime()
		r.ExitCode = cmd.ProcessState.ExitCode()
		r.Classification = "completed_unverified"
		if err != nil {
			r.Classification = "process_error"
		}
	}
	if ctx.Err() != nil {
		r.Classification = "canceled"
		return r, ctx.Err()
	}
	return r, err
}
