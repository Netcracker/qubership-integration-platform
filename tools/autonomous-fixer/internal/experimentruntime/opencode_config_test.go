package experimentruntime

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestOpenCodeConfigPinsCompactionAndTitle(t *testing.T) {
	for _, p := range []Profile{{"opencode", "opencode/deepseek-v4.1-flash-free", "high"}, {"opencode", "opencode/glm-5.3-flash-free", "high"}, {"opencode", "opencode/mimo-v2.6-flash-free", ""}, {"opencode", "opencode/beam-free", "high"}} {
		t.Run(p.Model, func(t *testing.T) {
			b, err := OpenCodeConfig(p, "/actual/workspace")
			if err != nil {
				t.Fatal(err)
			}
			var got map[string]any
			if err = json.Unmarshal([]byte(b), &got); err != nil {
				t.Fatal(err)
			}
			model := p.Model
			if p.Effort != "" {
				model += "#" + p.Effort
			}
			want := map[string]any{"skills": []any{"/actual/workspace/.agents/skills"}, "instructions": []any{"/actual/workspace/AGENTS.md"}, "compaction": map[string]any{"auto": true, "keep": map[string]any{"tokens": float64(15000)}, "buffer": "15%"}, "agents": map[string]any{"compaction": map[string]any{"model": model}, "title": map[string]any{"model": model}}, "permissions": []any{map[string]any{"action": "subagent", "resource": "*", "effect": "deny"}}, "experimental": map[string]any{"subagent_depth": float64(0)}}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("config=%s", b)
			}
		})
	}
	if _, err := OpenCodeConfig(Profile{Runtime: "codex", Model: "gpt-6-luna", Effort: "high"}, "/actual/workspace"); err == nil {
		t.Fatal("accepted a Codex profile")
	}
}
