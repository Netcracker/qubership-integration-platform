package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestReviewProfileIsUsedOnlyForIndependentReviews(t *testing.T) {
	cfg := fixture(t)
	reviewer := experimentruntime.Profile{Runtime: "codex", Model: "review-fixture", Effort: "high"}
	cfg.ReviewProfile = &reviewer
	b, e := os.ReadFile(cfg.ModelCatalog)
	if e != nil {
		t.Fatal(e)
	}
	var catalog map[string][]map[string]any
	if e = json.Unmarshal(b, &catalog); e != nil {
		t.Fatal(e)
	}
	catalog["models"] = append(catalog["models"], map[string]any{"slug": "review-fixture", "context_window": 200000, "supported_reasoning_levels": []map[string]string{{"effort": "high"}}})
	b, _ = json.Marshal(catalog)
	if e = os.WriteFile(cfg.ModelCatalog, b, 0600); e != nil {
		t.Fatal(e)
	}
	report, e := Execute(context.Background(), cfg)
	if e != nil || report.Status != "verified" {
		t.Fatalf("%+v %v", report, e)
	}
	dirs, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	reviews := 0
	for _, dir := range dirs {
		b, _ := os.ReadFile(filepath.Join(dir, "invocation.json"))
		var inv workcycle.Invocation
		if e = json.Unmarshal(b, &inv); e != nil {
			t.Fatal(e)
		}
		b, _ = os.ReadFile(filepath.Join(dir, "profile.json"))
		var got experimentruntime.Profile
		if e = json.Unmarshal(b, &got); e != nil {
			t.Fatal(e)
		}
		want := cfg.Profile
		if inv.Stage == workcycle.ReviewStage {
			want = reviewer
			reviews++
		}
		if got != want {
			t.Fatalf("stage %s got %+v want %+v", inv.Stage, got, want)
		}
		b, _ = os.ReadFile(filepath.Join(dir, "model-catalog.json"))
		var pinned experimentruntime.CatalogSelection
		_ = json.Unmarshal(b, &pinned)
		if pinned.Model != want.Model {
			t.Fatal(pinned, want)
		}
	}
	if reviews != 2 {
		t.Fatal(reviews)
	}
}
