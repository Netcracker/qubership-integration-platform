package experimentprobe

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentworkspace"
)

type Config struct {
	ModelCatalog                   string
	Profile                        experimentruntime.Profile
	NodeRoot, Root, CodexLoginFile string
}

type Report struct {
	Directory              string
	IsolationClaim         string
	Profile                experimentruntime.Profile
	AccessPassed           bool
	ContextPassed          bool
	ToolPassed             bool
	EffectiveModelVerified bool
	Outcome                string
	Process                experimentruntime.Result
	Events                 experimentruntime.EventReport
	Final                  map[string]string
}

type dependencies struct {
	run func(context.Context, experimentruntime.Command, string, []string, io.Writer, io.Writer) (experimentruntime.Result, error)
}

func Run(ctx context.Context, c Config) (Report, error) {
	return run(ctx, c, dependencies{experimentruntime.Run})
}

func run(ctx context.Context, c Config, d dependencies) (report Report, err error) {
	report.Profile = c.Profile
	report.IsolationClaim = "none; direct host execution with a fresh runtime configuration HOME"
	report.Outcome = "unverified"
	if err = validate(c); err != nil {
		return report, err
	}
	if err = os.MkdirAll(c.Root, 0700); err != nil {
		return report, err
	}
	report.Directory, err = os.MkdirTemp(c.Root, "capability-"+c.Profile.Runtime+"-")
	if err != nil {
		return report, err
	}
	defer func() { err = errors.Join(err, writeJSON(filepath.Join(report.Directory, "report.json"), report)) }()
	markers := map[string]string{}
	for _, key := range []string{"root_marker", "skill_marker", "payload_marker"} {
		var b [16]byte
		if _, err = rand.Read(b[:]); err != nil {
			return report, err
		}
		markers[key] = key + "_" + hex.EncodeToString(b[:])
	}
	source := filepath.Join(report.Directory, "fixture-source")
	if err = os.MkdirAll(filepath.Join(source, ".agents", "skills", "probe"), 0700); err != nil {
		return report, err
	}
	files := map[string]string{"AGENTS.md": "Follow the probe skill. In your final JSON, set root_marker to " + markers["root_marker"] + ".\n", ".agents/skills/probe/SKILL.md": "---\nname: probe\ndescription: Read the probe payload and report its markers.\n---\nRun cat payload.txt using your shell tool. In your final JSON, set skill_marker to " + markers["skill_marker"] + ". Set payload_marker to the file contents.\n", "payload.txt": markers["payload_marker"] + "\n"}
	for name, contents := range files {
		if err = os.WriteFile(filepath.Join(source, name), []byte(contents), 0600); err != nil {
			return report, err
		}
	}
	for _, args := range [][]string{{"init", "--initial-branch=probe-source"}, {"add", "--all"}, {"-c", "user.name=Capability probe", "-c", "user.email=probe@localhost", "commit", "--quiet", "-m", "chore: create capability fixture"}} {
		if err = git(ctx, source, args...); err != nil {
			return report, err
		}
	}
	snapshot, e := experimentworkspace.Create(ctx, source, "HEAD", filepath.Join(report.Directory, "workspace"), "probe")
	if e != nil {
		return report, e
	}
	home := filepath.Join(report.Directory, "home")
	if err = os.Mkdir(home, 0700); err != nil {
		return report, err
	}
	prompt := "Follow the repository instructions. Load the probe skill, execute its requested shell command, and return only a JSON object with root_marker, skill_marker, and payload_marker. Do not guess marker values."
	command, e := experimentruntime.BuildCommand(c.Profile, snapshot.Directory, prompt)
	if e != nil {
		return report, e
	}
	if c.Profile.Runtime == "codex" && c.ModelCatalog != "" {
		selection, e := experimentruntime.PinCatalog(&command, c.Profile, c.ModelCatalog, home)
		if e != nil {
			return report, e
		}
		if e = writeJSON(filepath.Join(report.Directory, "model-catalog.json"), selection); e != nil {
			return report, e
		}
	}
	if err = os.WriteFile(filepath.Join(report.Directory, "prompt.txt"), []byte(prompt), 0600); err != nil {
		return report, err
	}
	if err = writeJSON(filepath.Join(report.Directory, "command.json"), command); err != nil {
		return report, err
	}
	env, e := environment(c.Profile, snapshot.Directory, home, c.NodeRoot)
	if e != nil {
		return report, e
	}
	if c.Profile.Runtime == "codex" {
		cleanup, e := copyLogin(c.CodexLoginFile, home)
		if e != nil {
			return report, e
		}
		defer cleanup()
	}
	command.Program = filepath.Join(c.NodeRoot, "bin", command.Program)
	report.Process, err = d.run(ctx, command, report.Directory, env, nil, nil)
	raw, e := os.ReadFile(report.Process.StdoutPath)
	if e != nil {
		return report, errors.Join(err, e)
	}
	report.Events, e = experimentruntime.ParseEvents(bytes.NewReader(raw), c.Profile.Runtime)
	if e != nil {
		return report, errors.Join(err, e)
	}
	report.Final, report.ToolPassed = semanticEvidence(raw, c.Profile.Runtime, markers)
	report.ContextPassed = matches(report.Final, markers)
	report.AccessPassed = report.ContextPassed && !report.Events.HasError && report.Process.ExitCode == 0 && err == nil && report.Events.MalformedLines == 0 && len(report.Events.UnknownTypes) == 0
	if report.AccessPassed && report.ToolPassed {
		report.Outcome = "capability_observed_unverified_model"
	}
	return report, err
}

func validate(c Config) error {
	if !filepath.IsAbs(c.Root) || !filepath.IsAbs(c.NodeRoot) {
		return fmt.Errorf("probe root and Node root must be absolute")
	}
	if c.Profile.Runtime == "opencode" {
		if c.CodexLoginFile != "" {
			return fmt.Errorf("free OpenCode probes do not accept a login file")
		}
		models := map[string]string{"opencode/deepseek-v4.1-flash-free": "high", "opencode/glm-5.3-flash-free": "high", "opencode/beam-free": "high", "opencode/mimo-v2.6-flash-free": ""}
		effort, ok := models[c.Profile.Model]
		if !ok || effort != c.Profile.Effort {
			return fmt.Errorf("unsupported free OpenCode probe profile")
		}
	} else if c.Profile.Runtime == "codex" {
		if c.CodexLoginFile == "" {
			return fmt.Errorf("Codex probe requires an explicit ChatGPT login file")
		}
		if _, err := experimentruntime.BuildCommand(c.Profile, "/workspace", ""); err != nil {
			return err
		}
	} else {
		return fmt.Errorf("unsupported probe runtime")
	}
	return nil
}

func environment(p experimentruntime.Profile, workspace, home, nodeRoot string) ([]string, error) {
	env := []string{"HOME=" + home, "PATH=" + filepath.Join(nodeRoot, "bin") + ":/usr/local/go/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "CODEX_HOME=" + filepath.Join(home, ".codex"), "XDG_CONFIG_HOME=" + filepath.Join(home, "config"), "XDG_DATA_HOME=" + filepath.Join(home, "data"), "XDG_CACHE_HOME=" + filepath.Join(home, "cache"), "OPENCODE_CONFIG_DIR=" + filepath.Join(home, "config", "opencode"), "OPENCODE_CONFIG_PROJECT_DISABLE=1", "OPENCODE_DISABLE_CLAUDE_CODE=1"}
	if p.Runtime == "opencode" {
		config, err := experimentruntime.OpenCodeConfig(p, workspace)
		if err != nil {
			return nil, err
		}
		env = append(env, "OPENCODE_CONFIG_CONTENT="+config)
	}
	return env, nil
}

func copyLogin(source, home string) (func(), error) {
	noop := func() {}
	b, err := os.ReadFile(source)
	if err != nil {
		return noop, fmt.Errorf("cannot read selected ChatGPT login file")
	}
	var auth struct {
		Mode   string          `json:"auth_mode"`
		APIKey json.RawMessage `json:"OPENAI_API_KEY"`
		Tokens json.RawMessage `json:"tokens"`
	}
	if json.Unmarshal(b, &auth) != nil || auth.Mode != "chatgpt" || string(auth.APIKey) != "null" || len(auth.Tokens) == 0 || string(auth.Tokens) == "null" {
		return noop, fmt.Errorf("selected file must contain ChatGPT login tokens and a null API key")
	}
	dir := filepath.Join(home, ".codex")
	if err = os.MkdirAll(dir, 0700); err != nil {
		return noop, err
	}
	dest := filepath.Join(dir, "auth.json")
	if err = os.WriteFile(dest, b, 0600); err != nil {
		return noop, fmt.Errorf("cannot create isolated login copy")
	}
	return func() { _ = os.Remove(dest) }, nil
}

func git(ctx context.Context, dir string, args ...string) error {
	cmd := exec.CommandContext(ctx, "/usr/bin/git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
	cmd.Dir = dir
	cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null"}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("fixture git command failed: %w: %s", err, out)
	}
	return nil
}
func writeJSON(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0600)
}
func matches(actual, expected map[string]string) bool {
	for key, value := range expected {
		if actual[key] != value {
			return false
		}
	}
	return true
}

func semanticEvidence(raw []byte, runtime string, markers map[string]string) (map[string]string, bool) {
	var final map[string]string
	tool := false
	var text strings.Builder
	var messageID string
	decoder := json.NewDecoder(bytes.NewReader(raw))
	for {
		var event struct {
			Type string `json:"type"`
			Item struct {
				Type, Text, Command, Status string
				ExitCode                    *int   `json:"exit_code"`
				Output                      string `json:"aggregated_output"`
			} `json:"item"`
			Part struct {
				Type, Text, Tool string
				MessageID        string `json:"messageID"`
				State            struct {
					Status, Output string
					Input          struct{ Command string }
				}
			} `json:"part"`
		}
		if decoder.Decode(&event) != nil {
			break
		}
		if runtime == "codex" {
			if event.Type == "item.completed" && event.Item.Type == "agent_message" {
				text.Reset()
				text.WriteString(event.Item.Text)
			}
			if event.Type == "item.completed" && event.Item.Type == "command_execution" && event.Item.Status == "completed" && event.Item.ExitCode != nil && *event.Item.ExitCode == 0 && strings.Contains(event.Item.Command, "cat") && strings.Contains(event.Item.Command, "payload.txt") && strings.Contains(event.Item.Output, markers["payload_marker"]) {
				tool = true
			}
		} else {
			if event.Type == "text" && event.Part.Type == "text" {
				if event.Part.MessageID != messageID {
					text.Reset()
					messageID = event.Part.MessageID
				}
				text.WriteString(event.Part.Text)
			}
			if event.Type == "tool_use" && event.Part.Type == "tool" && (event.Part.Tool == "bash" || event.Part.Tool == "shell") && event.Part.State.Status == "completed" && strings.Contains(event.Part.State.Input.Command, "cat") && strings.Contains(event.Part.State.Input.Command, "payload.txt") && strings.Contains(event.Part.State.Output, markers["payload_marker"]) {
				tool = true
			}
		}
	}
	content := strings.TrimSpace(text.String())
	if strings.HasPrefix(content, "```json\n") && strings.HasSuffix(content, "\n```") {
		content = strings.TrimSuffix(strings.TrimPrefix(content, "```json\n"), "\n```")
	}
	_ = json.Unmarshal([]byte(content), &final)
	return final, tool
}
