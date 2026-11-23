package workcontext

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDocumentationUsesItsOwnRequiredSkill(t *testing.T) {
	root := t.TempDir()
	workspace := filepath.Join(root, "work")
	task := filepath.Join(root, "task")
	for path, content := range map[string]string{filepath.Join(workspace, "AGENTS.md"): "project rules", filepath.Join(workspace, ".agents/skills/docs-authoring/SKILL.md"): "documentation workflow", filepath.Join(task, "task.md"): "review documentation"} {
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	p, err := BuildForKind(workspace, task, Selection{}, "documentation")
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != "documentation" || !strings.Contains(p.Text, "documentation workflow") || strings.Contains(p.Text, "fix-issue") {
		t.Fatal(p)
	}
	if err := Verify(workspace, task, p); err != nil {
		t.Fatal(err)
	}
	p.Kind = "implementation"
	if Verify(workspace, task, p) == nil {
		t.Fatal("changed workflow kind accepted")
	}
	if _, err := Build(workspace, task, Selection{}); err == nil {
		t.Fatal("implementation accepted missing fix-issue skill")
	}
}
