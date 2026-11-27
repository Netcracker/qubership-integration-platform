package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type triageClassifier struct {
	action, reason, attribution string
	probability                 float64
}

func (f triageClassifier) Classify(_ context.Context, req jev.Request) (jev.Response, error) {
	r := jev.Response{ID: "test-routing", Model: req.Model, Answers: map[string]jev.Answer{}}
	for name, q := range req.Questions {
		choice := map[string]string{"documentation_action": f.action, "documentation_reason": f.reason, "change_attribution": f.attribution}[name]
		probabilities := map[string]float64{}
		p := 1.0
		if name == "documentation_action" {
			p = f.probability
		}
		for option := range q.Criteria {
			if option == choice {
				probabilities[option] = p
			} else {
				probabilities[option] = (1 - p) / float64(len(q.Criteria)-1)
			}
		}
		r.Answers[name] = jev.Answer{Type: "choice", Choice: choice, Probabilities: probabilities}
	}
	return r, nil
}
func triageFixture(t *testing.T, f triageClassifier) Config {
	t.Helper()
	cfg := fixture(t)
	cfg.TaskKind = "documentation"
	cfg.TaskID = "owner/repo#1:documentation"
	for name, value := range map[string]string{".agents/skills/docs-authoring/SKILL.md": "Use current documentation.", "help/docs/readme.md": "# Help\n\nOriginal behavior.\n"} {
		p := filepath.Join(cfg.Workspace, name)
		if e := os.MkdirAll(filepath.Dir(p), 0700); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(p, []byte(value), 0600); e != nil {
			t.Fatal(e)
		}
	}
	for _, args := range [][]string{{"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "documentation baseline"}} {
		if _, e := git(context.Background(), cfg.Workspace, args...); e != nil {
			t.Fatal(e)
		}
	}
	head, e := git(context.Background(), cfg.Workspace, "rev-parse", "HEAD")
	if e != nil {
		t.Fatal(e)
	}
	packet := doctriage.Packet{ID: "fixture", Scope: "issue1", Repository: "owner/repo", Issue: json.RawMessage(`{"number":1,"title":"Behavior"}`), BaseSHA: "base", HeadSHA: "merge", DocsSHA: string(head[:len(head)-1]), ChangedPaths: []string{"engine/code.txt"}, Diff: "implementation patch", Documentation: []doctriage.Document{{Path: "help/docs/readme.md", Content: "# Help\n\nOriginal behavior.\n"}}, Complete: true}
	report, e := doctriage.Evaluate(context.Background(), packet, jev.DefaultModel, f)
	if e != nil {
		t.Fatal(e)
	}
	cfg.Context.Attachments = append(cfg.Context.Attachments, "packet.json", "jev-report.json")
	cfg.DocumentationTriage = &DocumentationTriage{PacketPath: filepath.Join(cfg.TaskDirectory, "packet.json"), ReportPath: filepath.Join(cfg.TaskDirectory, "jev-report.json")}
	if e = writeJSON(cfg.DocumentationTriage.PacketPath, packet); e != nil {
		t.Fatal(e)
	}
	if e = writeJSON(cfg.DocumentationTriage.ReportPath, report); e != nil {
		t.Fatal(e)
	}
	return cfg
}
func TestDocumentationTriageNativeRoutes(t *testing.T) {
	for _, tc := range []struct {
		name          string
		classifier    triageClassifier
		route, status string
		count         int
	}{
		{"automatic-negative", triageClassifier{"no_change", "outside_help_scope", "unchanged", 1}, "docs-no-change", "docs-no-change", 0},
		{"direct-writer", triageClassifier{"needed", "missing_guidance", "change_related", 1}, "docs-write", "docs-ready-for-pr", 3},
		{"ambiguous-assessment", triageClassifier{"needed", "missing_guidance", "change_related", .6}, "docs-assess", "docs-ready-for-pr", 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := triageFixture(t, tc.classifier)
			if tc.count == 0 {
				cfg.LoginFile = ""
				cfg.RuntimeBinary = filepath.Join(cfg.Workspace, "runtime-does-not-exist")
			}
			r, e := Execute(context.Background(), cfg)
			if e != nil || r.Status != tc.status || len(r.Observations) != tc.count {
				t.Fatalf("status=%s observations=%d err=%v", r.Status, len(r.Observations), e)
			}
			if r.Request.DocumentationEntry == nil || r.Request.DocumentationEntry.Route != tc.route {
				t.Fatal(r.Request.DocumentationEntry)
			}
			steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
			if len(steps) != tc.count {
				t.Fatal(steps)
			}
			if _, e := os.Stat(filepath.Join(cfg.OutputDirectory, "receipt.json")); e != nil {
				t.Fatal(e)
			}
			if tc.count == 0 && len(r.Candidate.ChangedPaths) != 0 {
				t.Fatal("automatic negative edited files")
			}
			old := cfg.OutputDirectory
			cfg.ResumeFrom = filepath.Join(old, "report.json")
			cfg.OutputDirectory = filepath.Join(filepath.Dir(old), "resume")
			resumed, e := Execute(context.Background(), cfg)
			if tc.count == 0 {
				if e == nil {
					t.Fatal("automatic negative resumed native work")
				}
				return
			}
			if e != nil || resumed.Status != "docs-ready-for-pr" || len(resumed.Observations) != tc.count+1 {
				t.Fatalf("resume: %s %v", resumed.Status, e)
			}
			cfg.OutputDirectory = filepath.Join(filepath.Dir(old), "changed-policy")
			policy := doctriage.DefaultRoutingPolicy()
			policy.PositiveProbability = .9
			cfg.DocumentationTriage.Policy = &policy
			if _, e = Execute(context.Background(), cfg); e == nil {
				t.Fatal("resume accepted changed routing policy")
			}
		})
	}
}

func TestDocumentationResumeRetainsLegacyRoutingProof(t *testing.T) {
	cfg := triageFixture(t, triageClassifier{"needed", "missing_guidance", "change_related", 1})
	raw, err := os.ReadFile(cfg.DocumentationTriage.ReportPath)
	if err != nil {
		t.Fatal(err)
	}
	var archived doctriage.Report
	if err := json.Unmarshal(raw, &archived); err != nil {
		t.Fatal(err)
	}
	archived.PolicyVersion = "docs-impact-v5"
	if err := writeJSON(cfg.DocumentationTriage.ReportPath, archived); err != nil {
		t.Fatal(err)
	}
	seed, err := Execute(context.Background(), cfg)
	if err != nil || seed.Status != "docs-ready-for-pr" {
		t.Fatalf("seed status = %s, error = %v", seed.Status, err)
	}
	prior := cfg.OutputDirectory
	proofPath := filepath.Join(prior, "evidence", "documentation-routing.json")
	data, err := os.ReadFile(proofPath)
	if err != nil {
		t.Fatal(err)
	}
	var proof map[string]json.RawMessage
	if err = json.Unmarshal(data, &proof); err != nil {
		t.Fatal(err)
	}
	var routing doctriage.Routing
	if err = json.Unmarshal(proof["routing"], &routing); err != nil {
		t.Fatal(err)
	}
	routing.Version = "docs-routing-v1"
	routing.ReasonCode = ""
	if err = writeJSON(filepath.Join(prior, "routing.json"), routing); err != nil {
		t.Fatal(err)
	}
	proof["routing"], err = json.Marshal(routing)
	if err != nil {
		t.Fatal(err)
	}
	if err = writeJSON(proofPath, proof); err != nil {
		t.Fatal(err)
	}
	data, err = os.ReadFile(proofPath)
	if err != nil {
		t.Fatal(err)
	}
	proofHash := bytesDigest(data)
	manifests, err := filepath.Glob(filepath.Join(prior, "step-*", "evidence-sha256.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range append(manifests, filepath.Join(prior, "controller-evidence-sha256.json")) {
		var manifest map[string]string
		body, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(body, &manifest); err != nil {
			t.Fatal(err)
		}
		if _, ok := manifest[proofPath]; ok {
			manifest[proofPath] = proofHash
			if err = writeJSON(path, manifest); err != nil {
				t.Fatal(err)
			}
		}
	}

	cfg.ResumeFrom = filepath.Join(prior, "report.json")
	cfg.OutputDirectory = filepath.Join(filepath.Dir(prior), "resume-legacy")
	resumed, err := Execute(context.Background(), cfg)
	if err != nil || resumed.Status != "docs-ready-for-pr" || len(resumed.Observations) != len(seed.Observations)+1 {
		t.Fatalf("resume status = %s, observations = %d, error = %v", resumed.Status, len(resumed.Observations), err)
	}
}

func TestDirectWriterCanDismissFalsePositive(t *testing.T) {
	for _, mutate := range []bool{false, true} {
		cfg := triageFixture(t, triageClassifier{"needed", "missing_guidance", "change_related", 1})
		cfg.Environment["QIP_WORK_TEST_DOCS_NO_CHANGE"] = "1"
		if mutate {
			cfg.Environment["QIP_WORK_TEST_DOCS_NO_CHANGE_MUTATES"] = "1"
		}
		r, e := Execute(context.Background(), cfg)
		if mutate {
			if e == nil || r.Status == "docs-no-change" {
				t.Fatal("dismissal hid a patch")
			}
			continue
		}
		if e != nil || r.Status != "docs-no-change" || len(r.Observations) != 1 || r.Observations[0].Stage != "docs-write" {
			t.Fatal(r.Status, e)
		}
	}
}
func TestTriageRejectsUnboundInputsBeforeRuntime(t *testing.T) {
	for _, scenario := range []string{"task", "head", "report", "attachment", "modified-after-prepare"} {
		t.Run(scenario, func(t *testing.T) {
			cfg := triageFixture(t, triageClassifier{"no_change", "outside_help_scope", "unchanged", 1})
			switch scenario {
			case "task":
				cfg.TaskID = "owner/repo#2:documentation"
			case "head":
				var p doctriage.Packet
				b, _ := os.ReadFile(cfg.DocumentationTriage.PacketPath)
				_ = json.Unmarshal(b, &p)
				p.DocsSHA = "different"
				_ = writeJSON(cfg.DocumentationTriage.PacketPath, p)
			case "report":
				var r doctriage.Report
				b, _ := os.ReadFile(cfg.DocumentationTriage.ReportPath)
				_ = json.Unmarshal(b, &r)
				r.PacketSHA256 = "different"
				_ = writeJSON(cfg.DocumentationTriage.ReportPath, r)
			case "attachment":
				cfg.Context.Attachments = []string{"policy.md", "packet.json"}
			case "modified-after-prepare":
				d, e := Prepare(context.Background(), cfg)
				if e != nil {
					t.Fatal(e)
				}
				_ = os.WriteFile(cfg.DocumentationTriage.ReportPath, []byte("changed"), 0600)
				snapshot, e := d.Snapshot(context.Background())
				if e != nil {
					t.Fatal(e)
				}
				e = d.Save(context.Background(), workcycle.Report{Request: d.Request, Status: "docs-no-change", Candidate: snapshot, Documentation: d.Request.DocumentationEntry.Decision})
				if e == nil {
					t.Fatal("changed input accepted at final save")
				}
				return
			}
			if _, e := Execute(context.Background(), cfg); e == nil {
				t.Fatal("unbound triage accepted")
			}
			steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
			if len(steps) != 0 {
				t.Fatal("invalid triage launched runtime")
			}
		})
	}
}

func TestTriageUsesRawAnswersInsteadOfStoredRecommendation(t *testing.T) {
	for _, positive := range []bool{false, true} {
		classifier := triageClassifier{"no_change", "outside_help_scope", "unchanged", 1}
		if positive {
			classifier = triageClassifier{"needed", "missing_guidance", "change_related", 1}
		}
		cfg := triageFixture(t, classifier)
		var report doctriage.Report
		data, _ := os.ReadFile(cfg.DocumentationTriage.ReportPath)
		if e := json.Unmarshal(data, &report); e != nil {
			t.Fatal(e)
		}
		report.Recommendation = doctriage.Recommendation{Action: "no_change", Reason: "outside_help_scope", Attribution: "unchanged"}
		report.SuggestedRoute = "docs-no-change"
		if !positive {
			report.Recommendation.Action = "needed"
			report.SuggestedRoute = "docs-write"
		}
		if e := writeJSON(cfg.DocumentationTriage.ReportPath, report); e != nil {
			t.Fatal(e)
		}
		r, e := Execute(context.Background(), cfg)
		want, stages := "docs-no-change", 0
		if positive {
			want, stages = "docs-ready-for-pr", 3
		}
		if e != nil || r.Status != want || len(r.Observations) != stages {
			t.Fatalf("%s %d %v", r.Status, len(r.Observations), e)
		}
	}
}
