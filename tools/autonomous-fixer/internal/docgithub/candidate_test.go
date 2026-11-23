package docgithub

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func put(t *testing.T, path string, value any) {
	t.Helper()
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		t.Fatal(e)
	}
	b, e := json.MarshalIndent(value, "", "  ")
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(path, append(b, '\n'), 0600); e != nil {
		t.Fatal(e)
	}
}
func putText(t *testing.T, path, value string) {
	t.Helper()
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(path, []byte(value), 0600); e != nil {
		t.Fatal(e)
	}
}

type knownClassifier struct{ needed bool }

func (f knownClassifier) Classify(_ context.Context, r jev.Request) (jev.Response, error) {
	choices := map[string]string{"documentation_action": "no_change", "documentation_reason": "outside_help_scope", "change_attribution": "unchanged"}
	if f.needed {
		choices = map[string]string{"documentation_action": "needed", "documentation_reason": "stale_guidance", "change_attribution": "change_related"}
	}
	input, output, cost := int64(120), int64(30), 0.0042
	response := jev.Response{ID: "request-id", Model: r.Model, Answers: map[string]jev.Answer{}, Usage: &jev.Usage{InputTokens: &input, OutputTokens: &output, Cost: &cost}}
	for name, q := range r.Questions {
		a := jev.Answer{Type: "choice", Choice: choices[name], Probabilities: map[string]float64{}}
		for option := range q.Criteria {
			a.Probabilities[option] = 0
		}
		a.Probabilities[a.Choice] = 1
		response.Answers[name] = a
	}
	return response, nil
}
func publicationFixture(t *testing.T, needed bool) (Plan, string, string) {
	return publicationFixtureRoute(t, needed, false)
}
func publicationFixtureRoute(t *testing.T, needed, assessment bool) (Plan, string, string) {
	t.Helper()
	base := t.TempDir()
	source := filepath.Join(base, "source")
	task := filepath.Join(base, "task")
	run := filepath.Join(base, "run")
	putText(t, filepath.Join(source, "AGENTS.md"), "Follow project rules.\n")
	putText(t, filepath.Join(source, ".agents/skills/docs-authoring/SKILL.md"), "Document shipped behavior.\n")
	putText(t, filepath.Join(source, "help/docs/readme.md"), "# Help\n\nOld instruction.\n")
	for _, args := range [][]string{{"init", "-q", "--initial-branch=main"}, {"add", "."}, {"-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-qm", "baseline"}} {
		c := exec.Command("git", args...)
		c.Dir = source
		if b, e := c.CombinedOutput(); e != nil {
			t.Fatal(e, string(b))
		}
	}
	head, e := gitRead(context.Background(), source, "rev-parse", "HEAD")
	if e != nil {
		t.Fatal(e)
	}
	sha := strings.TrimSpace(string(head))
	plan := Plan{Version: "docs-plan-v2", IssueState: "closed", Repository: "org/repo", IssueNumber: 1, IssueNodeID: "ISSUE", IssueTitle: "Documentation behavior", IssueBodyHash: Digest("Issue body"), DefaultBranch: "main", BaseSHA: sha, Project: Project{ID: "PROJECT", Number: 12, ItemID: "ITEM", StatusFieldID: "FIELD", ReviewOptionID: "REVIEW", DoneOptionID: "DONE", ReviewStatus: "In doc review", DoneStatus: "Done"}, ImplementingPRs: []docpacket.PR{{Number: 2, MergeSHA: sha, Target: "main"}}}
	plan.ID = Digest(plan)
	issue, _ := json.Marshal(docpacket.Issue{Number: 1, Title: plan.IssueTitle, Body: "Issue body", PRs: plan.ImplementingPRs})
	packet := doctriage.Packet{ID: "issue-1", Scope: "Complete issue change", Repository: plan.Repository, Issue: issue, BaseSHA: sha, HeadSHA: sha, DocsSHA: sha, ChangedPaths: []string{"engine/file.go"}, Diff: "implementation diff", Documentation: []doctriage.Document{{Path: "help/docs/readme.md", Content: "Old instruction."}}, Complete: true}
	if assessment {
		packet.Complete = false
	}
	plan.PacketSHA256 = Digest(packet)
	plan.ID = ""
	plan.ID = Digest(plan)
	classifier, e := doctriage.Evaluate(context.Background(), packet, jev.DefaultModel, knownClassifier{needed})
	if e != nil {
		t.Fatal(e)
	}
	route, e := doctriage.SelectRoute(packet, classifier, doctriage.DefaultRoutingPolicy())
	if e != nil {
		t.Fatal(e)
	}
	put(t, filepath.Join(task, "packet.json"), packet)
	put(t, filepath.Join(task, "jev-report.json"), classifier)
	putText(t, filepath.Join(task, "task.md"), "Assess and update issue 1.\n")
	selection := workcontext.Selection{Attachments: []string{"packet.json", "jev-report.json"}}
	ctx, e := workcontext.BuildForKind(source, task, selection, "documentation")
	if e != nil {
		t.Fatal(e)
	}
	proof := filepath.Join(run, "evidence/documentation-routing.json")
	put(t, proof, map[string]any{"origin": "jev-controller-routing", "task_id": "org/repo#1:documentation", "source_sha": sha, "context_sha256": ctx.SHA256, "packet": packet, "report": classifier, "routing": route})
	cfg := localwork.Config{Workspace: source, TaskDirectory: task, OutputDirectory: run, TaskKind: "documentation", TaskID: "org/repo#1:documentation", Context: selection, DocumentationTriage: &localwork.DocumentationTriage{PacketPath: "packet.json", ReportPath: "jev-report.json"}}
	if needed {
		cfg.Profile = experimentruntime.Profile{Runtime: "codex", Model: "gpt-6.1-terra", Effort: "high"}
		for _, name := range []string{"step-0001-docs-write", "step-0002-docs-review", "step-0003-docs-verify"} {
			model := cfg.Profile
			if strings.HasSuffix(name, "-review") {
				model.Model = "gpt-6.1-sol"
			}
			step := filepath.Join(run, name)
			put(t, filepath.Join(step, "profile.json"), model)
			put(t, filepath.Join(step, "process.json"), experimentruntime.Result{FinishedAt: time.Now()})
			putText(t, filepath.Join(step, "home/.codex/sessions/run.jsonl"), `{"type":"session_meta","payload":{"id":"`+name+`"}}`+"\n"+
				`{"type":"turn_context","payload":{"model":"`+model.Model+`","effort":"`+model.Effort+`"}}`+"\n"+
				`{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":100,"cached_input_tokens":40,"cache_write_input_tokens":0,"output_tokens":10,"reasoning_output_tokens":3},"last_token_usage":{"input_tokens":100,"cached_input_tokens":40,"cache_write_input_tokens":0,"output_tokens":10,"reasoning_output_tokens":3}}}}`+"\n")
		}
	}
	put(t, filepath.Join(run, "config.json"), cfg)
	put(t, filepath.Join(run, "context.json"), ctx)
	doc := &workcycle.DocumentationDecision{Decision: "no_change", Reason: "outside_help_scope", Summary: "Help is sufficient.", IssueComment: "The change needs no product help update.", Claims: []workcycle.DocClaim{{ID: "behavior", Behavior: "User behavior", Attribution: "unchanged", SourceEvidence: []string{proof}, DocumentationEvidence: []string{proof}, Action: "Keep help unchanged."}}}
	report := workcycle.Report{Status: "docs-no-change", Request: workcycle.Request{Kind: "documentation", TaskID: cfg.TaskID, Context: ctx.Text, DocumentationEntry: &workcycle.DocumentationEntry{Route: route.Route, Evidence: []string{proof}, Decision: doc}}, Documentation: doc}
	if route.Route != "docs-no-change" {
		report.Request.DocumentationEntry.Decision = nil
	}
	if needed {
		doc.Decision = "needed"
		doc.Reason = "stale_guidance"
		doc.PRTitle = "docs: update instruction"
		doc.PRBody = "Describe the shipped control.\n\n<sub>🤖 draft-model in=999</sub>"
		doc.Claims[0].Attribution = "change_related"
		doc.Claims[0].NeedsChange = true
		report.Request.DocumentationEntry.Decision = nil
		putText(t, filepath.Join(source, "help/docs/readme.md"), "# Help\n\nNew instruction.\n")
		putText(t, filepath.Join(run, "candidate-files/help/docs/readme.md"), "# Help\n\nNew instruction.\n")
		report.Status = "docs-ready-for-pr"
		report.Request.RequiredChecks = []workcycle.Check{{ID: "scope", Scope: "help"}}
	}
	snapshot, e := localwork.SnapshotWorkspace(context.Background(), source, sha)
	if e != nil {
		t.Fatal(e)
	}
	report.Candidate = snapshot
	if needed {
		report.Observations = []workcycle.Observation{{Stage: "docs-write", After: snapshot, Declaration: workcycle.Response{Documentation: doc, Evidence: []string{proof}}}, {Stage: "docs-review", Before: snapshot, After: snapshot, Declaration: workcycle.Response{CandidateID: snapshot.ID, Evidence: []string{proof}}}, {Stage: "docs-verify", Before: snapshot, After: snapshot, Declaration: workcycle.Response{CandidateID: snapshot.ID, Evidence: []string{proof}, Checks: []workcycle.Result{{ID: "scope", Outcome: "pass", Evidence: []string{proof}}}}}}
	}
	put(t, filepath.Join(run, "report.json"), report)
	b, _ := os.ReadFile(filepath.Join(run, "report.json"))
	proofBytes, _ := os.ReadFile(proof)
	put(t, filepath.Join(run, "receipt.json"), map[string]any{"context_sha256": ctx.SHA256, "report_sha256": bytesHash(b), "candidate_id": snapshot.ID, "evidence_sha256": map[string]string{proof: bytesHash(proofBytes)}})
	return plan, run, source
}
func TestCandidateValidatesActualSourceAndEvidence(t *testing.T) {
	for _, needed := range []bool{false, true} {
		t.Run(map[bool]string{false: "no-change", true: "patch"}[needed], func(t *testing.T) {
			p, run, source := publicationFixture(t, needed)
			c, e := ValidateCandidate(context.Background(), p, run, "")
			if e != nil {
				t.Fatal(e)
			}
			if needed && len(c.Files) != 1 {
				t.Fatal(c)
			}
			putText(t, filepath.Join(source, "help/docs/readme.md"), "changed after verification")
			if _, e = ValidateCandidate(context.Background(), p, run, ""); e == nil {
				t.Fatal("modified source accepted")
			}
		})
	}
}
func TestCandidateRejectsTamperedCopyOrReceipt(t *testing.T) {
	for _, what := range []string{"copy", "receipt", "evidence", "scope"} {
		t.Run(what, func(t *testing.T) {
			p, run, source := publicationFixture(t, true)
			switch what {
			case "copy":
				putText(t, filepath.Join(run, "candidate-files/help/docs/readme.md"), "different")
			case "receipt":
				put(t, filepath.Join(run, "receipt.json"), map[string]any{})
			case "evidence":
				putText(t, filepath.Join(run, "evidence/documentation-routing.json"), "changed")
			case "scope":
				putText(t, filepath.Join(source, "engine/file.go"), "source edit")
			}
			if _, e := ValidateCandidate(context.Background(), p, run, ""); e == nil {
				t.Fatal("tampered candidate accepted")
			}
		})
	}
}

func rewritePublicationReport(t *testing.T, run string, report workcycle.Report, extra map[string]string) {
	t.Helper()
	put(t, filepath.Join(run, "report.json"), report)
	bytes, err := os.ReadFile(filepath.Join(run, "report.json"))
	if err != nil {
		t.Fatal(err)
	}
	var receipt map[string]any
	if err = readJSON(filepath.Join(run, "receipt.json"), &receipt); err != nil {
		t.Fatal(err)
	}
	receipt["report_sha256"] = bytesHash(bytes)
	receipt["candidate_id"] = report.Candidate.ID
	if extra != nil {
		hashes := receipt["evidence_sha256"].(map[string]any)
		for name, hash := range extra {
			hashes[name] = hash
		}
	}
	put(t, filepath.Join(run, "receipt.json"), receipt)
}
func nativeNoChangeFixture(t *testing.T, route string) (Plan, string, string) {
	t.Helper()
	plan, run, source := publicationFixtureRoute(t, route == "docs-write", route == "docs-assess")
	original, err := gitRead(context.Background(), source, "show", "HEAD:help/docs/readme.md")
	if err != nil {
		t.Fatal(err)
	}
	putText(t, filepath.Join(source, "help/docs/readme.md"), string(original))
	var report workcycle.Report
	if err = readJSON(filepath.Join(run, "report.json"), &report); err != nil {
		t.Fatal(err)
	}
	report.Status = "docs-no-change"
	doc := report.Documentation
	doc.Decision = "no_change"
	doc.Reason = "already_covered"
	doc.Claims[0].Attribution = "unchanged"
	doc.Claims[0].NeedsChange = false
	evidencePath := filepath.Join(run, "evidence", "step-0001-"+route, "inspection.json")
	putText(t, evidencePath, "Fresh source and help inspection.\n")
	b, _ := os.ReadFile(evidencePath)
	doc.Claims[0].SourceEvidence = []string{evidencePath}
	doc.Claims[0].DocumentationEvidence = []string{evidencePath}
	report.Candidate, err = localwork.SnapshotWorkspace(context.Background(), source, plan.BaseSHA)
	if err != nil {
		t.Fatal(err)
	}
	report.Observations = []workcycle.Observation{{Stage: workcycle.Stage(route), Before: report.Candidate, After: report.Candidate, Declaration: workcycle.Response{CandidateID: report.Candidate.ID, Documentation: doc, Evidence: []string{evidencePath}}}}
	rewritePublicationReport(t, run, report, map[string]string{evidencePath: bytesHash(b)})
	return plan, run, source
}
func TestNativeNoChangeRequiresMatchingObservationAndFreshEvidence(t *testing.T) {
	for _, route := range []string{"docs-write", "docs-assess"} {
		t.Run(route, func(t *testing.T) {
			for _, damage := range []string{"none", "missing_observation", "routing_only", "changed_snapshot", "different_decision", "wrong_stage", "question"} {
				t.Run(damage, func(t *testing.T) {
					plan, run, source := nativeNoChangeFixture(t, route)
					var report workcycle.Report
					if err := readJSON(filepath.Join(run, "report.json"), &report); err != nil {
						t.Fatal(err)
					}
					switch damage {
					case "missing_observation":
						report.Observations = nil
					case "routing_only":
						proof := filepath.Join(run, "evidence/documentation-routing.json")
						report.Observations[0].Declaration.Evidence = []string{proof}
						report.Documentation.Claims[0].SourceEvidence = []string{proof}
						report.Documentation.Claims[0].DocumentationEvidence = []string{proof}
						report.Observations[0].Declaration.Documentation = report.Documentation
					case "changed_snapshot":
						report.Observations[0].Before.ID = "other"
					case "different_decision":
						copy := *report.Documentation
						copy.Summary = "A different assessment."
						report.Observations[0].Declaration.Documentation = &copy
					case "wrong_stage":
						report.Observations[0].Stage = "docs-review"
					case "question":
						report.Observations[0].Declaration.Question = &workcycle.Question{Kind: "needs-human"}
					}
					rewritePublicationReport(t, run, report, nil)
					_, err := ValidateCandidate(context.Background(), plan, run, source)
					if damage == "none" && err != nil {
						t.Fatal(err)
					}
					if damage != "none" && err == nil {
						t.Fatal("invalid no-change completion accepted")
					}
				})
			}
		})
	}
}
func TestAutomaticNoChangeMustMatchControllerDecision(t *testing.T) {
	plan, run, source := publicationFixture(t, false)
	var report workcycle.Report
	if err := readJSON(filepath.Join(run, "report.json"), &report); err != nil {
		t.Fatal(err)
	}
	report.Documentation.Summary = "Changed after routing."
	rewritePublicationReport(t, run, report, nil)
	if _, err := ValidateCandidate(context.Background(), plan, run, source); err == nil {
		t.Fatal("automatic conclusion differs from controller decision")
	}
}

func TestAutomaticNoChangeRequiresContextBoundRoutingProof(t *testing.T) {
	for _, damage := range []string{"replaced_response", "unbound_report", "unbound_proof"} {
		t.Run(damage, func(t *testing.T) {
			plan, run, source := publicationFixture(t, false)
			proofPath := filepath.Join(run, "evidence/documentation-routing.json")
			switch damage {
			case "replaced_response":
				var proof map[string]any
				if err := readJSON(proofPath, &proof); err != nil {
					t.Fatal(err)
				}
				proof["report"].(map[string]any)["response"].(map[string]any)["id"] = "another-request"
				put(t, proofPath, proof)
				var receipt map[string]any
				if err := readJSON(filepath.Join(run, "receipt.json"), &receipt); err != nil {
					t.Fatal(err)
				}
				data, _ := os.ReadFile(proofPath)
				receipt["evidence_sha256"].(map[string]any)[proofPath] = bytesHash(data)
				put(t, filepath.Join(run, "receipt.json"), receipt)
			case "unbound_report":
				var cfg localwork.Config
				if err := readJSON(filepath.Join(run, "config.json"), &cfg); err != nil {
					t.Fatal(err)
				}
				data, _ := os.ReadFile(filepath.Join(cfg.TaskDirectory, "jev-report.json"))
				putText(t, filepath.Join(cfg.TaskDirectory, "other-report.json"), string(data))
				cfg.DocumentationTriage.ReportPath = "other-report.json"
				cfg.Context.Attachments = append(cfg.Context.Attachments, "other-report.json")
				put(t, filepath.Join(run, "config.json"), cfg)
			case "unbound_proof":
				data, _ := os.ReadFile(proofPath)
				other := filepath.Join(run, "evidence/other.json")
				putText(t, other, string(data))
				var report workcycle.Report
				if err := readJSON(filepath.Join(run, "report.json"), &report); err != nil {
					t.Fatal(err)
				}
				report.Request.DocumentationEntry.Evidence = []string{other}
				report.Documentation.Claims[0].SourceEvidence = []string{other}
				report.Documentation.Claims[0].DocumentationEvidence = []string{other}
				report.Request.DocumentationEntry.Decision = report.Documentation
				rewritePublicationReport(t, run, report, map[string]string{other: bytesHash(data)})
				var receipt map[string]any
				if err := readJSON(filepath.Join(run, "receipt.json"), &receipt); err != nil {
					t.Fatal(err)
				}
				delete(receipt["evidence_sha256"].(map[string]any), proofPath)
				put(t, filepath.Join(run, "receipt.json"), receipt)
			}
			if _, err := ValidateCandidate(context.Background(), plan, run, source); err == nil {
				t.Fatal("unverified automatic routing accepted")
			}
		})
	}
}

func TestPublicationRequiresTheExactPreparedPacket(t *testing.T) {
	for _, damage := range []string{"missing", "different_packet"} {
		t.Run(damage, func(t *testing.T) {
			plan, run, source := publicationFixture(t, false)
			if damage == "missing" {
				plan.PacketSHA256 = ""
			} else {
				plan.PacketSHA256 = strings.Repeat("f", 64)
			}
			plan.ID = ""
			plan.ID = Digest(plan)
			if _, err := ValidateCandidate(context.Background(), plan, run, source); err == nil {
				t.Fatal("publication accepted an unbound or substituted preparation packet")
			}
		})
	}
}

func TestPublicationSnapshotIncludesBothSidesOfRename(t *testing.T) {
	plan, _, source := publicationFixture(t, false)
	c := exec.Command("git", "mv", "help/docs/readme.md", "help/docs/guide.md")
	c.Dir = source
	if b, e := c.CombinedOutput(); e != nil {
		t.Fatalf("%s: %v", b, e)
	}
	snapshot, e := localwork.SnapshotWorkspace(context.Background(), source, plan.BaseSHA)
	if e != nil || !samePaths(snapshot.ChangedPaths, []string{"help/docs/guide.md", "help/docs/readme.md"}) {
		t.Fatalf("renamed paths: %v (%v)", snapshot.ChangedPaths, e)
	}
}
