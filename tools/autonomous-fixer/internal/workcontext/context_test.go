package workcontext

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func put(t *testing.T, root, path, body string) {
	t.Helper()
	target := filepath.Join(root, path)
	if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
}
func fixture(t *testing.T) (string, string) {
	t.Helper()
	workspace, task := t.TempDir(), t.TempDir()
	put(t, workspace, "AGENTS.md", "workspace instructions")
	put(t, workspace, ".agents/skills/fix-issue/SKILL.md", "full process")
	put(t, task, "task.md", "task source")
	return workspace, task
}
func TestBuildPreservesFullSources(t *testing.T) {
	w, d := fixture(t)
	large := strings.Repeat("full source\n", 100000) + "required tail instruction"
	put(t, w, "module.instructions.md", large)
	put(t, d, "annex.md", "required policy")
	selection := Selection{Instructions: []string{"module.instructions.md"}, Attachments: []string{"annex.md"}}
	packet, err := Build(w, d, selection)
	if err != nil {
		t.Fatal(err)
	}
	if len(packet.Files) != 5 || !strings.Contains(packet.Text, large) || !strings.Contains(packet.Text, "required policy") {
		t.Fatal("packet lost context")
	}
	if err := Verify(w, d, packet); err != nil {
		t.Fatal(err)
	}
	again, err := Build(w, d, selection)
	if err != nil || packet.SHA256 != again.SHA256 {
		t.Fatal("packet is not deterministic")
	}
	renamed := selection
	renamed.Attachments = []string{"renamed.md"}
	put(t, d, "renamed.md", "required policy")
	other, err := Build(w, d, renamed)
	if err != nil || packet.SHA256 == other.SHA256 {
		t.Fatal("digest must include source path")
	}
}
func TestBuildRejectsMissingOrEmptyContext(t *testing.T) {
	for _, path := range []string{"AGENTS.md", ".agents/skills/fix-issue/SKILL.md", "task.md", "annex.md", "chosen-skill.md"} {
		t.Run(path, func(t *testing.T) {
			for _, empty := range []bool{false, true} {
				w, d := fixture(t)
				put(t, d, "annex.md", "annex")
				put(t, w, "chosen-skill.md", "instructions")
				root := w
				if path == "task.md" || path == "annex.md" {
					root = d
				}
				if empty {
					put(t, root, path, " \n")
				} else if err := os.Remove(filepath.Join(root, path)); err != nil {
					t.Fatal(err)
				}
				_, err := Build(w, d, Selection{Instructions: []string{"chosen-skill.md"}, Attachments: []string{"annex.md"}})
				if err == nil {
					t.Fatalf("accepted missing or empty %s", path)
				}
			}
		})
	}
}
func TestBuildRejectsDuplicateAndUnsafePaths(t *testing.T) {
	for _, path := range []string{"AGENTS.md", "../outside.md", "/absolute.md", "a/../AGENTS.md", ".", ""} {
		t.Run(path, func(t *testing.T) {
			w, d := fixture(t)
			if _, err := Build(w, d, Selection{Instructions: []string{path}}); err == nil {
				t.Fatalf("accepted %q", path)
			}
		})
	}
	w, d := fixture(t)
	outside := t.TempDir()
	put(t, outside, "secret.md", "outside")
	if err := os.Symlink(outside, filepath.Join(w, "escape")); err != nil {
		t.Fatal(err)
	}
	if _, err := Build(w, d, Selection{Instructions: []string{"escape/secret.md"}}); err == nil {
		t.Fatal("accepted symlink escape")
	}
	if _, err := Build(w, d, Selection{Instructions: []string{".agents"}}); err == nil {
		t.Fatal("accepted directory")
	}
	if _, err := Build(w, d, Selection{Attachments: []string{"task.md"}}); err == nil {
		t.Fatal("accepted duplicate attachment")
	}
}
func TestVerifyDetectsTampering(t *testing.T) {
	for _, kind := range []string{"text", "hash", "file-hash", "file-path", "file-root", "remove-file", "changed-source", "missing-source"} {
		t.Run(kind, func(t *testing.T) {
			w, d := fixture(t)
			packet, err := Build(w, d, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			switch kind {
			case "text":
				packet.Text += "tampered"
			case "hash":
				packet.SHA256 = "tampered"
			case "file-hash":
				packet.Files[0].SHA256 = "tampered"
			case "file-path":
				packet.Files[0].Path = "task.md"
			case "file-root":
				packet.Files[0].Root = "other"
			case "remove-file":
				packet.Files = packet.Files[1:]
			case "changed-source":
				put(t, w, "AGENTS.md", "changed instructions")
			case "missing-source":
				if err := os.Remove(filepath.Join(d, "task.md")); err != nil {
					t.Fatal(err)
				}
			}
			if err := Verify(w, d, packet); err == nil {
				t.Fatal("accepted tampered packet")
			}
		})
	}
}

func TestLargeAttachmentsRemainBoundWithoutInlining(t *testing.T) {
	w, d := fixture(t)
	large := strings.Repeat("evidence\n", 150000) + "last required fact"
	put(t, d, "packet.json", large)
	p, e := Build(w, d, Selection{Attachments: []string{"packet.json"}, AttachmentsAsFiles: true})
	if e != nil {
		t.Fatal(e)
	}
	if len(p.Text) > 10000 || strings.Contains(p.Text, "last required fact") || !strings.Contains(p.Text, "workspace instructions") || !strings.Contains(p.Text, "full process") {
		t.Fatal("attachment was inlined or instructions lost")
	}
	if e = Verify(w, d, p); e != nil {
		t.Fatal(e)
	}
	moved := t.TempDir()
	put(t, moved, "task.md", "task source")
	put(t, moved, "packet.json", large)
	if e = Verify(w, moved, p); e != nil {
		t.Fatal("relative attachment identity should survive controlled archive relocation", e)
	}
	put(t, d, "packet.json", large+" changed")
	if e = Verify(w, d, p); e == nil {
		t.Fatal("referenced file mutation not detected")
	}
	p.Files[0].ReferenceOnly = true
	if e = Verify(w, moved, p); e == nil {
		t.Fatal("instructions could be hidden behind a file reference")
	}
}
