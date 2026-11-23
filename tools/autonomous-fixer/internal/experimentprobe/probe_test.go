package experimentprobe

import (
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

func fake(t *testing.T, correct, tool bool, login string) dependencies {
	t.Helper()
	return dependencies{
		run: func(ctx context.Context, c experimentruntime.Command, dir string, env []string, out, stderr io.Writer) (experimentruntime.Result, error) {
			valuesEnv := map[string]string{}
			for _, entry := range env {
				key, value, _ := strings.Cut(entry, "=")
				valuesEnv[key] = value
			}
			if valuesEnv["HOME"] != filepath.Join(dir, "home") || !strings.HasPrefix(valuesEnv["PATH"], "/") {
				t.Fatal("missing explicit host environment")
			}
			if valuesEnv["EXPERIMENT_INHERITED_SECRET"] != "" {
				t.Fatal("inherited secret")
			}
			if c.Workspace != filepath.Join(dir, "workspace") {
				t.Fatal("wrong actual working directory")
			}
			for _, name := range []string{"prompt.txt", "command.json", "workspace/AGENTS.md", "workspace/.agents/skills/probe/SKILL.md"} {
				if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
					t.Fatal(err)
				}
			}
			if login != "" {
				b, err := os.ReadFile(filepath.Join(valuesEnv["HOME"], ".codex", "auth.json"))
				if err != nil || string(b) != login {
					t.Fatal("isolated login copy is missing")
				}
			}
			values := map[string]string{}
			for key, path := range map[string]string{"root_marker": "AGENTS.md", "skill_marker": ".agents/skills/probe/SKILL.md", "payload_marker": "payload.txt"} {
				b, err := os.ReadFile(filepath.Join(c.Workspace, path))
				if err != nil {
					t.Fatal(err)
				}
				values[key] = regexp.MustCompile(key + `_[a-f0-9]{32}`).FindString(string(b))
				if values[key] == "" {
					t.Fatal("marker missing")
				}
			}
			var events []any
			isCodex := login != ""
			if tool {
				if isCodex {
					events = append(events, map[string]any{"type": "item.completed", "item": map[string]any{"type": "command_execution", "command": "cat payload.txt", "status": "completed", "exit_code": 0, "aggregated_output": values["payload_marker"]}})
				} else {
					events = append(events, map[string]any{"type": "tool_use", "part": map[string]any{"type": "tool", "tool": "shell", "state": map[string]any{"status": "completed", "input": map[string]string{"command": "cat payload.txt"}, "output": values["payload_marker"]}}})
				}
			}
			if !correct {
				values["root_marker"] = "wrong"
			}
			final, _ := json.Marshal(values)
			if isCodex {
				events = append(events, map[string]any{"type": "item.completed", "item": map[string]any{"type": "agent_message", "text": string(final)}}, map[string]any{"type": "turn.completed"})
			} else {
				events = append(events, map[string]any{"type": "text", "part": map[string]string{"type": "text", "text": string(final)}})
			}
			var raw strings.Builder
			for _, event := range events {
				b, _ := json.Marshal(event)
				raw.Write(b)
				raw.WriteByte('\n')
			}
			c.Program = "/bin/sh"
			c.Args = []string{"-c", `printf '%s' "$1"`, "fake-runtime", raw.String()}
			return experimentruntime.Run(ctx, c, dir, env, out, stderr)
		},
	}
}

func TestFreeProbeUsesFixtureAndActualFinalEvents(t *testing.T) {
	t.Setenv("EXPERIMENT_INHERITED_SECRET", "must-not-inherit")
	c := Config{Profile: experimentruntime.Profile{Runtime: "opencode", Model: "opencode/beam-free", Effort: "high"}, Root: t.TempDir(), NodeRoot: t.TempDir()}
	r, err := run(context.Background(), c, fake(t, true, true, ""))
	if err != nil || !r.AccessPassed || !r.ContextPassed || !r.ToolPassed || r.EffectiveModelVerified || r.Outcome != "capability_observed_unverified_model" {
		t.Fatalf("%+v %v", r, err)
	}
	for _, name := range []string{"stdout.jsonl", "stderr.log", "report.json", "workspace/payload.txt"} {
		if _, err := os.Stat(filepath.Join(r.Directory, name)); err != nil {
			t.Fatal(err)
		}
	}
	for _, tc := range []struct{ correct, tool bool }{{false, true}, {true, false}} {
		r, err = run(context.Background(), c, fake(t, tc.correct, tc.tool, ""))
		if err != nil || r.Outcome != "unverified" {
			t.Fatalf("%+v %v", r, err)
		}
	}
}

func TestCodexLoginCopyIsRemovedAndSourceUnchanged(t *testing.T) {
	login := `{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"access_token":"fake-secret"}}`
	source := filepath.Join(t.TempDir(), "auth.json")
	if err := os.WriteFile(source, []byte(login), 0600); err != nil {
		t.Fatal(err)
	}
	c := Config{Profile: experimentruntime.Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "high"}, Root: t.TempDir(), NodeRoot: t.TempDir(), CodexLoginFile: source}
	r, err := run(context.Background(), c, fake(t, true, true, login))
	if err != nil || !r.AccessPassed || !r.ToolPassed {
		t.Fatalf("%+v %v", r, err)
	}
	if _, err := os.Stat(filepath.Join(r.Directory, "home/.codex/auth.json")); !os.IsNotExist(err) {
		t.Fatal("login copy remains")
	}
	b, err := os.ReadFile(source)
	if err != nil || string(b) != login {
		t.Fatal("source login changed")
	}
	b, err = os.ReadFile(filepath.Join(r.Directory, "report.json"))
	if err != nil || strings.Contains(string(b), "fake-secret") {
		t.Fatal("login leaked into report")
	}
}

func TestRejectPaidModelsAndAPIKeys(t *testing.T) {
	for _, model := range []string{"openai/gpt-6", "opencode/beam-free#high", "opencode/unknown-free"} {
		if err := validate(Config{Profile: experimentruntime.Profile{Runtime: "opencode", Model: model, Effort: "high"}, Root: t.TempDir(), NodeRoot: t.TempDir()}); err == nil {
			t.Fatal("accepted model", model)
		}
	}
	for _, login := range []string{`{"auth_mode":"apikey","OPENAI_API_KEY":"secret","tokens":{}}`, `{"auth_mode":"chatgpt","tokens":{}}`, `{"auth_mode":"chatgpt","OPENAI_API_KEY":"","tokens":{}}`} {
		source := filepath.Join(t.TempDir(), "auth.json")
		if err := os.WriteFile(source, []byte(login), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := copyLogin(source, t.TempDir()); err == nil {
			t.Fatal("accepted unapproved credentials")
		}
	}
}

func TestToolEchoIsNotFinalEvidence(t *testing.T) {
	values := map[string]string{"root_marker": "a", "skill_marker": "b", "payload_marker": "c"}
	final, _ := json.Marshal(values)
	raw, _ := json.Marshal(map[string]any{"type": "tool_use", "part": map[string]any{"type": "tool", "tool": "shell", "state": map[string]any{"status": "completed", "input": map[string]string{"command": "cat payload.txt"}, "output": string(final)}}})
	got, tool := semanticEvidence(raw, "opencode", values)
	if matches(got, values) || !tool {
		t.Fatalf("tool echoes became final evidence: %v %v", got, tool)
	}
}

// Live DeepSeek and GLM capability probes exposed separate narration messages
// and a final JSON code fence, respectively.
func TestOpenCodeFinalMessageBoundariesAndFences(t *testing.T) {
	values := map[string]string{"root_marker": "root-example", "skill_marker": "skill-example", "payload_marker": "payload-example"}
	final, err := json.Marshal(values)
	if err != nil {
		t.Fatal(err)
	}
	for _, fenced := range []bool{false, true} {
		content := string(final)
		if fenced {
			content = "```json\n" + content + "\n```"
		}
		var raw strings.Builder
		for _, part := range []map[string]string{
			{"type": "text", "messageID": "narration", "text": "I'll load the probe skill first."},
			{"type": "text", "messageID": "final", "text": content[:len(content)/2]},
			{"type": "text", "messageID": "final", "text": content[len(content)/2:]},
		} {
			b, _ := json.Marshal(map[string]any{"type": "text", "part": part})
			raw.Write(b)
			raw.WriteByte('\n')
		}
		got, _ := semanticEvidence([]byte(raw.String()), "opencode", values)
		if !matches(got, values) {
			t.Fatalf("final message not parsed (fenced=%v): %v", fenced, got)
		}
	}
}

func TestRecheckPreservesOriginalAndUsesControllerFixture(t *testing.T) {
	c := Config{Profile: experimentruntime.Profile{Runtime: "opencode", Model: "opencode/beam-free", Effort: "high"}, Root: t.TempDir(), NodeRoot: t.TempDir()}
	r, err := run(context.Background(), c, fake(t, true, true, ""))
	if err != nil {
		t.Fatal(err)
	}
	original, err := os.ReadFile(filepath.Join(r.Directory, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(r.Directory, "workspace", "payload.txt"), []byte("agent changed the payload"), 0600); err != nil {
		t.Fatal(err)
	}
	got, err := Recheck(r.Directory)
	if err != nil || !got.AccessPassed || !got.ContextPassed || !got.ToolPassed || got.EffectiveModelVerified || got.Process != r.Process {
		t.Fatalf("%+v %v", got, err)
	}
	after, err := os.ReadFile(filepath.Join(r.Directory, "report.json"))
	if err != nil || string(original) != string(after) {
		t.Fatal("original report changed")
	}
	if _, err = os.Stat(filepath.Join(r.Directory, "report-rechecked.json")); err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(filepath.Join(r.Directory, "stdout.jsonl"), os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteString(`{"type":"error","error":{"message":"runtime failure"}}` + "\n")
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	got, err = Recheck(r.Directory)
	if err != nil || got.AccessPassed || got.Outcome != "runtime_error" {
		t.Fatalf("%+v %v", got, err)
	}
}
