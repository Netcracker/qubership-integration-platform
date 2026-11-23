package experimentruntime

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPinCatalogPreservesNativeMetadata(t *testing.T) {
	p := Profile{Runtime: "codex", Model: "gpt-6.1-sol", Effort: "medium"}
	command, e := BuildCommand(p, t.TempDir(), "task")
	if e != nil {
		t.Fatal(e)
	}
	source := filepath.Join(t.TempDir(), "models.json")
	data := []byte(`{"models":[{"slug":"gpt-6.1-sol","context_window":500000,"effective_context_window_percent":95,"supported_reasoning_levels":[{"effort":"medium"}],"model_messages":{"instructions_template":"original"}}]}`)
	if e = os.WriteFile(source, data, 0600); e != nil {
		t.Fatal(e)
	}
	s, e := PinCatalog(&command, p, source, t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	if s.ContextWindow != 500000 || s.EffectivePercent == nil || *s.EffectivePercent != 95 || s.SHA256 == "" {
		t.Fatalf("lost native metadata: %+v", s)
	}
	b, e := os.ReadFile(s.Path)
	if e != nil {
		t.Fatal(e)
	}
	if string(b) != string(data) {
		t.Fatal("rewrote model metadata")
	}
	if command.Args[len(command.Args)-1] != "-" || !strings.Contains(strings.Join(command.Args, " "), "model_catalog_json=") {
		t.Fatal("catalog is not passed to the stdin invocation")
	}
}

func TestPinCatalogRejectsMissingOrUnsupportedProfile(t *testing.T) {
	p := Profile{Runtime: "codex", Model: "gpt-6.1-sol", Effort: "medium"}
	for _, data := range []string{`{"models":[]}`, `{"models":[{"slug":"gpt-6.1-sol","context_window":500000,"effective_context_window_percent":95,"supported_reasoning_levels":[{"effort":"low"}]}]}`, `{"models":[{"slug":"gpt-6.1-sol"}]}`} {
		command, e := BuildCommand(p, t.TempDir(), "task")
		if e != nil {
			t.Fatal(e)
		}
		source := filepath.Join(t.TempDir(), "models.json")
		if e = os.WriteFile(source, []byte(data), 0600); e != nil {
			t.Fatal(e)
		}
		if _, e = PinCatalog(&command, p, source, t.TempDir()); e == nil {
			t.Fatal("accepted a catalog without the requested capabilities")
		}
	}
}
