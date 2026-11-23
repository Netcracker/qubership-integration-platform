package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestDocumentationNativeFlow(t *testing.T) {
	for _, noChange := range []bool{false, true} {
		t.Run(map[bool]string{false: "write-review-verify", true: "no-change"}[noChange], func(t *testing.T) {
			cfg := fixture(t)
			cfg.TaskKind = "documentation"
			cfg.Environment["QIP_WORK_TEST_DOCS_BAD_ECHO"] = "1"
			cfg.Environment["QIP_WORK_TEST_DOCS_NESTED_ONLY"] = "1"
			cfg.Environment["QIP_WORK_TEST_DOCS_SEPARATE"] = "1"
			for name, value := range map[string]string{".agents/skills/docs-authoring/SKILL.md": "Use the documentation workflow.", "help/docs/readme.md": "# Help\n\nOriginal behavior.\n"} {
				p := filepath.Join(cfg.Workspace, name)
				if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(p, []byte(value), 0600); err != nil {
					t.Fatal(err)
				}
			}
			for _, args := range [][]string{{"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "docs baseline"}} {
				if _, err := git(context.Background(), cfg.Workspace, args...); err != nil {
					t.Fatal(err)
				}
			}
			if noChange {
				cfg.Environment["QIP_WORK_TEST_DOCS_NO_CHANGE"] = "1"
				cfg.RequiredChecks = nil
			}
			r, err := Execute(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			want := "docs-ready-for-pr"
			stages := 4
			if noChange {
				want = "docs-no-change"
				stages = 1
			}
			if r.Status != want || len(r.Observations) != stages {
				t.Fatalf("%s %d", r.Status, len(r.Observations))
			}
			var draft map[string]any
			b, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "delivery-draft.json"))
			if err != nil {
				t.Fatal(err)
			}
			if err = json.Unmarshal(b, &draft); err != nil {
				t.Fatal(err)
			}
			if draft["github_effects_executed"] != false || draft["reason"] == nil || len(draft["separate_findings"].([]any)) != 1 {
				t.Fatal(draft)
			}
			proof := r.Documentation.SeparateFindings[0].SourceEvidence[0]
			var manifest map[string]string
			stepsWithEvidence, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "evidence-sha256.json"))
			if err != nil || len(stepsWithEvidence) == 0 {
				t.Fatal("missing evidence manifests", err)
			}
			content, err := os.ReadFile(stepsWithEvidence[len(stepsWithEvidence)-1])
			if err != nil || json.Unmarshal(content, &manifest) != nil || manifest[proof] == "" {
				t.Fatal("separate finding evidence was not checked", err)
			}
			if noChange && len(r.Candidate.ChangedPaths) != 0 {
				t.Fatal("no-change modified source")
			}
			if noChange {
				oldOutput := cfg.OutputDirectory
				cfg.ResumeFrom = filepath.Join(oldOutput, "report.json")
				cfg.ResumeMode = "assessment"
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "revalidated-assessment")
				replayed, err := Execute(context.Background(), cfg)
				if err != nil {
					t.Fatal(err)
				}
				steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
				if replayed.Status != "docs-no-change" || len(steps) != 0 {
					t.Fatal("assessment replay launched a model", replayed.Status, steps)
				}
				proof := r.Documentation.SeparateFindings[0].SourceEvidence[0]
				if err := os.WriteFile(proof, []byte("changed proof"), 0600); err != nil {
					t.Fatal(err)
				}
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "tampered-assessment")
				if _, err := Execute(context.Background(), cfg); err == nil {
					t.Fatal("altered proof accepted for replay")
				}
			}
			if !noChange {
				if _, err := os.Stat(filepath.Join(cfg.OutputDirectory, "candidate.patch")); err != nil {
					t.Fatal(err)
				}
				oldOutput := cfg.OutputDirectory
				cfg.ResumeFrom = filepath.Join(oldOutput, "report.json")
				r.Candidate.FileHashes = nil
				for i := range r.Observations {
					r.Observations[i].Before.FileHashes = nil
					r.Observations[i].After.FileHashes = nil
				}
				originalPaths := append([]string(nil), r.Candidate.ChangedPaths...)
				r.Candidate.ChangedPaths = append(r.Candidate.ChangedPaths, "help/docs/unrelated.md")
				if e := writeJSON(cfg.ResumeFrom, r); e != nil {
					t.Fatal(e)
				}
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "changed-paths-resume")
				if _, e := Prepare(context.Background(), cfg); e == nil {
					t.Fatal("resume accepted changed paths")
				}
				r.Candidate.ChangedPaths = originalPaths
				if e := writeJSON(cfg.ResumeFrom, r); e != nil {
					t.Fatal(e)
				}
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "resumed")
				resumed, err := Execute(context.Background(), cfg)
				if err != nil {
					t.Fatal(err)
				}
				steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
				if resumed.Status != "docs-ready-for-pr" || len(steps) != 1 || len(resumed.Observations) != 5 {
					t.Fatalf("resume repeated work: %+v %v", resumed, steps)
				}
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "mutating-verification")
				cfg.Environment["QIP_WORK_TEST_DOCS_MUTATE_REVIEW"] = "1"
				mutated, err := Execute(context.Background(), cfg)
				if err == nil || mutated.Status == "docs-ready-for-pr" {
					t.Fatal("controller binding hid a source mutation", mutated.Status, err)
				}
				delete(cfg.Environment, "QIP_WORK_TEST_DOCS_MUTATE_REVIEW")
				cfg.OutputDirectory = filepath.Join(filepath.Dir(oldOutput), "tampered-resume")
				_ = os.WriteFile(filepath.Join(cfg.Workspace, "help/docs/readme.md"), []byte("# Changed after checkpoint\n"), 0600)
				if _, err := Execute(context.Background(), cfg); err == nil {
					t.Fatal("changed checkpoint candidate accepted")
				}
			}
		})
	}
}
