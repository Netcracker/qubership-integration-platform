package experimentruntime

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestProfileMatrix(t *testing.T) {
	profiles := []Profile{{"codex", "gpt-6.1-sol", "medium"}, {"codex", "gpt-6.1-terra", "medium"}, {"codex", "gpt-6-luna", "high"}, {"codex", "gpt-6.1-sol", "high"}, {"codex", "gpt-6.1-terra", "high"}, {"opencode", "opencode/deepseek-v4.1-flash-free", "high"}, {"opencode", "opencode/glm-5.3-flash-free", "high"}, {"opencode", "opencode/mimo-v2.6-flash-free", ""}, {"opencode", "opencode/beam-free", "high"}}
	for _, p := range profiles {
		t.Run(p.Runtime+"/"+p.Model+"/"+p.Effort, func(t *testing.T) {
			c, err := BuildCommand(p, "/workspace", "test prompt")
			if err != nil {
				t.Fatal(err)
			}
			var expected []string
			if p.Runtime == "codex" {
				expected = []string{"--no-daemon", "exec", "--ignore-user-config", "--ignore-rules", "--json", "--color", "never", "--sandbox", "danger-full-access", "--cd", "/workspace", "--model", p.Model, "-c", `approval_policy="never"`, "-c", `model_reasoning_effort="` + p.Effort + `"`, "-c", `projects."/workspace".trust_level="untrusted"`, "-c", "project_doc_max_bytes=65536", "--disable", "hooks", "--disable", "multi_agent", "--disable", "multi_agent_v2", "--disable", "plugins", "--disable", "remote_plugin", "--disable", "skill_mcp_dependency_install", "-"}
				if c.Stdin != "test prompt" {
					t.Fatal(c.Stdin)
				}
			} else {
				model := p.Model
				if p.Effort != "" {
					model += "#" + p.Effort
				}
				expected = []string{"run", "--standalone", "--auto", "--format", "json", "--model", model, "--", "test prompt"}
			}
			if !reflect.DeepEqual(c.Args, expected) {
				t.Fatalf("%q != %q", c.Args, expected)
			}
		})
	}
}

func TestCapabilityProbeUsesOnlyVersionAndHelp(t *testing.T) {
	program := filepath.Join(t.TempDir(), "fake-runtime")
	script := `#!/bin/sh
test -z "$PROBE_SECRET" || exit 3
case "$*" in
--version) printf 'opencode v2.2.6\n';;
'run --help') printf '%s' '--standalone --auto --format --model provider/model#variant --file';;
*) exit 7;;
esac
`
	if err := os.WriteFile(program, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PROBE_SECRET", "not inherited")
	c, err := ProbeCapabilities(context.Background(), "opencode", program, nil)
	if err != nil || !c.VersionMatches || !c.JSONEvents || !c.ImageAttachmentFlag {
		t.Fatalf("%+v %v", c, err)
	}
	if err := os.WriteFile(program, []byte("#!/bin/sh\nprintf old-version"), 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := ProbeCapabilities(context.Background(), "opencode", program, nil); err == nil {
		t.Fatal("accepted incompatible runtime")
	}
}
