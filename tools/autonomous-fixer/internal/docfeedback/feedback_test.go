package docfeedback

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type classifier struct{}

func (classifier) Classify(_ context.Context, req jev.Request) (jev.Response, error) {
	answers := map[string]jev.Answer{}
	choices := map[string]string{"documentation_action": "no_change", "documentation_reason": "outside_help_scope", "change_attribution": "unchanged"}
	for name, q := range req.Questions {
		p := map[string]float64{}
		for option := range q.Criteria {
			p[option] = 0
		}
		p[choices[name]] = 1
		answers[name] = jev.Answer{Type: "choice", Choice: choices[name], Probabilities: p}
	}
	return jev.Response{ID: "request-123", Model: req.Model, Answers: answers}, nil
}
func save(t *testing.T, path string, v any) string {
	t.Helper()
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		t.Fatal(e)
	}
	b = append(b, '\n')
	if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(path, b, 0600); e != nil {
		t.Fatal(e)
	}
	return digest(b)
}
func fixture(t *testing.T) string {
	t.Helper()
	return fixtureWithRoute(t, func(*doctriage.Routing) {})
}

func fixtureWithRoute(t *testing.T, changeRoute func(*doctriage.Routing)) string {
	t.Helper()
	root := t.TempDir()
	task := "o/r#12:documentation"
	packet := doctriage.Packet{ID: "issue12", Scope: "merged change", Issue: json.RawMessage(`{"number":12}`), Repository: "o/r", BaseSHA: "base", HeadSHA: "head", DocsSHA: "docs", ChangedPaths: []string{"infrastructure/Dockerfile"}, Diff: "build change", Complete: true, Documentation: []doctriage.Document{{Path: "help/docs/readme.md", Content: "help"}}}
	prediction, e := doctriage.Evaluate(context.Background(), packet, jev.DefaultModel, classifier{})
	if e != nil {
		t.Fatal(e)
	}
	route, e := doctriage.SelectRoute(packet, prediction, doctriage.DefaultRoutingPolicy())
	if e != nil {
		t.Fatal(e)
	}
	changeRoute(&route)
	if route.Version == "docs-routing-v1" {
		prediction.PolicyVersion = "docs-impact-v5"
		prediction.InputSHA256 = "eb41edd1ac5849cb9fd31effea7a68e710d97987f2669db2ed57da229a43e6a1" // Request digest before the public API scope change.
	}
	ctx := workcontext.Packet{Kind: "documentation", Text: "frozen context", Files: []workcontext.File{}}
	b, _ := json.Marshal(ctx)
	ctx.SHA256 = digest(b)
	proofPath := filepath.Join(root, "evidence/documentation-routing.json")
	evidence := []string{proofPath}
	decision := &workcycle.DocumentationDecision{Decision: "no_change", Reason: "outside_help_scope", Summary: "No help change.", IssueComment: "No help update is needed.", Claims: []workcycle.DocClaim{{ID: "scope", Behavior: "build", SourceEvidence: evidence, DocumentationEvidence: evidence, Action: "none", Attribution: "unchanged"}}}
	report := workcycle.Report{Request: workcycle.Request{Kind: "documentation", TaskID: task, Context: ctx.Text, DocumentationEntry: &workcycle.DocumentationEntry{Route: route.Route, Decision: decision, Evidence: evidence}}, Status: "docs-no-change", Documentation: decision, Candidate: workcycle.Snapshot{ID: "candidate"}}
	cfg := localwork.Config{OutputDirectory: root, TaskKind: "documentation", TaskID: task, DocumentationTriage: &localwork.DocumentationTriage{PacketPath: "packet.json", ReportPath: "report.json"}}
	save(t, filepath.Join(root, "config.json"), cfg)
	save(t, filepath.Join(root, "context.json"), ctx)
	reportHash := save(t, filepath.Join(root, "report.json"), report)
	save(t, filepath.Join(root, "routing.json"), route)
	proofHash := save(t, proofPath, proof{Origin: "jev-controller-routing", TaskID: task, SourceSHA: "docs", ContextSHA256: ctx.SHA256, Packet: packet, Report: prediction, Routing: route})
	save(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{proofPath: proofHash})
	save(t, filepath.Join(root, "receipt.json"), receipt{ReportSHA256: reportHash, ContextSHA256: ctx.SHA256, CandidateID: "candidate", Evidence: map[string]string{proofPath: proofHash}})
	return root
}

func TestImportReplaysLegacyRouteAndRejectsIncorrectCurrentReason(t *testing.T) {
	legacy := fixtureWithRoute(t, func(route *doctriage.Routing) {
		route.Version = "docs-routing-v1"
		route.ReasonCode = ""
	})
	record, err := Import(legacy, nil)
	if err != nil || record.Routing.Version != "docs-routing-v1" || record.Routing.ReasonCode != "" {
		t.Fatalf("legacy routing = %+v, import error = %v", record.Routing, err)
	}
	incorrect := fixtureWithRoute(t, func(route *doctriage.Routing) {
		route.ReasonCode = doctriage.RouteBelowThreshold
	})
	if _, err = Import(incorrect, nil); err == nil || !strings.Contains(err.Error(), "recorded route differs") {
		t.Fatalf("import with incorrect reason error = %v", err)
	}
}
func judgment(kind, actorType string) Adjudication {
	return Adjudication{Kind: kind, Actor: "reviewer", ActorType: actorType, Basis: "Compared the scoped behavior with its user help.", Evidence: []Reference{{Path: "https://github.com/o/r/issues/12#issuecomment-1"}}, Timestamp: time.Date(2026, 11, 13, 10, 0, 0, 0, time.UTC)}
}
func TestImportIsDeterministicAndPending(t *testing.T) {
	root := fixture(t)
	a, e := Import(root, nil)
	if e != nil {
		t.Fatal(e)
	}
	b, e := Import(root, nil)
	if e != nil || a.ID != b.ID || a.CaseID != b.CaseID {
		t.Fatal(a.ID, b.ID, e)
	}
	if a.AssessmentStatus != "pending" || a.Adjudication != nil || a.Observed.Origin != "classifier" || a.Observed.Status != "docs-no-change" {
		t.Fatal(a)
	}
	s, e := Summarize([]Record{a})
	if e != nil || s.Groups[0].Pending != 1 || len(s.Groups[0].HumanDeclaredJudgments) != 0 {
		t.Fatal(s, e)
	}
	output := filepath.Join(t.TempDir(), "record.json")
	if e = WriteNew(output, a); e != nil {
		t.Fatal(e)
	}
	if e = WriteNew(output, a); e == nil {
		t.Fatal("record overwritten")
	}
}
func TestExplicitJudgmentSupersedesPendingWithoutInventingAccuracy(t *testing.T) {
	r, e := Import(fixture(t), nil)
	if e != nil {
		t.Fatal(e)
	}
	labeled, e := Adjudicate(r, judgment("missed_needed_change", "human"))
	if e != nil {
		t.Fatal(e)
	}
	if r.Adjudication != nil || labeled.ID == r.ID || labeled.CaseID != r.CaseID || labeled.Supersedes != r.ID {
		t.Fatal("source record mutated or identity lost")
	}
	s, e := Summarize([]Record{r, labeled})
	if e != nil || len(s.Groups) != 1 || s.Groups[0].Cases != 1 || s.Groups[0].Pending != 0 || s.Groups[0].HumanDeclaredJudgments["missed_needed_change"] != 1 {
		t.Fatal(s, e)
	}
	if _, e = Adjudicate(r, judgment("unnecessary_change", "model")); e == nil {
		t.Fatal("incompatible prediction error accepted")
	}
	model, e := Adjudicate(r, judgment("confirmed_correct", "model"))
	if e != nil {
		t.Fatal(e)
	}
	s, e = Summarize([]Record{model})
	if e != nil || s.Groups[0].ModelDeclaredJudgments["confirmed_correct"] != 1 || len(s.Groups[0].HumanDeclaredJudgments) != 0 {
		t.Fatal(s, e)
	}
	if _, e = Summarize([]Record{labeled, model}); e == nil {
		t.Fatal("conflicting current labels collapsed silently")
	}
}
func TestImportRejectsMismatchedArtifactsAndUnfinishedOutcomes(t *testing.T) {
	for _, damage := range []string{"receipt", "context", "proof", "route", "task", "running", "missing_receipt"} {
		t.Run(damage, func(t *testing.T) {
			root := fixture(t)
			switch damage {
			case "receipt":
				save(t, filepath.Join(root, "receipt.json"), receipt{ReportSHA256: "wrong"})
			case "context":
				save(t, filepath.Join(root, "context.json"), workcontext.Packet{Text: "changed", SHA256: "wrong"})
			case "proof":
				save(t, filepath.Join(root, "evidence/documentation-routing.json"), map[string]string{"origin": "changed"})
			case "route":
				save(t, filepath.Join(root, "routing.json"), doctriage.Routing{Route: "docs-write"})
			case "task":
				save(t, filepath.Join(root, "config.json"), localwork.Config{TaskKind: "documentation", TaskID: "other"})
			case "running":
				var result workcycle.Report
				_, _ = read(filepath.Join(root, "report.json"), &result)
				result.Status = "rejected"
				save(t, filepath.Join(root, "report.json"), result)
			case "missing_receipt":
				if e := os.Remove(filepath.Join(root, "receipt.json")); e != nil {
					t.Fatal(e)
				}
			}
			if _, e := Import(root, nil); e == nil {
				t.Fatal("invalid provenance accepted")
			}
		})
	}
}
func TestJudgmentEvidenceAndRecordTampering(t *testing.T) {
	r, e := Import(fixture(t), nil)
	if e != nil {
		t.Fatal(e)
	}
	a := judgment("context_gap", "human")
	a.Actor = ""
	if _, e = Adjudicate(r, a); e == nil {
		t.Fatal("missing actor")
	}
	path := filepath.Join(t.TempDir(), "proof.txt")
	if e = os.WriteFile(path, []byte("original"), 0600); e != nil {
		t.Fatal(e)
	}
	ref, e := Evidence(path)
	if e != nil {
		t.Fatal(e)
	}
	a = judgment("context_gap", "human")
	a.Evidence = []Reference{ref}
	if e = os.WriteFile(path, []byte("changed"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = Adjudicate(r, a); e == nil {
		t.Fatal("changed judgment evidence accepted")
	}
	if _, e = Evidence("http://example.com/proof"); e == nil {
		t.Fatal("unsupported reference accepted")
	}
	r.Observed.Decision = "needed"
	if e = Validate(r); e == nil || !strings.Contains(e.Error(), "hash") {
		t.Fatal(e)
	}
}

func TestRelocatedArchiveImportsAfterOriginalIsRemoved(t *testing.T) {
	original := fixture(t)
	before, e := Import(original, nil)
	if e != nil {
		t.Fatal(e)
	}
	relocated := filepath.Join(t.TempDir(), "download")
	if e = os.CopyFS(relocated, os.DirFS(original)); e != nil {
		t.Fatal(e)
	}
	if e = os.RemoveAll(original); e != nil {
		t.Fatal(e)
	}
	after, e := Import(relocated, nil)
	if e != nil {
		t.Fatal(e)
	}
	if after.CaseID != before.CaseID || after.RequestSHA256 != before.RequestSHA256 || after.Observed.Status != before.Observed.Status {
		t.Fatal("archive identity changed")
	}
}
func TestArchiveRejectsExternalEvidenceAndSymlinks(t *testing.T) {
	for _, damage := range []string{"external", "escape", "symlink", "relocated_resume"} {
		t.Run(damage, func(t *testing.T) {
			original := fixture(t)
			root := filepath.Join(t.TempDir(), "download")
			if e := os.CopyFS(root, os.DirFS(original)); e != nil {
				t.Fatal(e)
			}
			switch damage {
			case "external", "escape":
				p := filepath.Join(t.TempDir(), "outside.txt")
				if e := os.WriteFile(p, []byte("proof"), 0600); e != nil {
					t.Fatal(e)
				}
				var done receipt
				_, _ = read(filepath.Join(root, "receipt.json"), &done)
				if damage == "escape" {
					p = original + "/../outside.txt"
				}
				done.Evidence[p] = digest([]byte("proof"))
				save(t, filepath.Join(root, "receipt.json"), done)
			case "symlink":
				proof := filepath.Join(root, "evidence/documentation-routing.json")
				if e := os.Remove(proof); e != nil {
					t.Fatal(e)
				}
				if e := os.Symlink(filepath.Join(original, "evidence/documentation-routing.json"), proof); e != nil {
					t.Fatal(e)
				}
			case "relocated_resume":
				var cfg localwork.Config
				_, _ = read(filepath.Join(root, "config.json"), &cfg)
				cfg.ResumeFrom = filepath.Join(t.TempDir(), "report.json")
				save(t, filepath.Join(root, "config.json"), cfg)
			}
			if _, e := Import(root, nil); e == nil {
				t.Fatal("unsupported archive mapping accepted")
			}
		})
	}
}

func anotherAttempt(t *testing.T, requestID string) Record {
	t.Helper()
	root := fixture(t)
	proofPath := filepath.Join(root, "evidence/documentation-routing.json")
	var p proof
	if _, e := read(proofPath, &p); e != nil {
		t.Fatal(e)
	}
	p.Report.Response.ID = requestID
	h := save(t, proofPath, p)
	save(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{proofPath: h})
	var done receipt
	if _, e := read(filepath.Join(root, "receipt.json"), &done); e != nil {
		t.Fatal(e)
	}
	done.Evidence[proofPath] = h
	save(t, filepath.Join(root, "receipt.json"), done)
	r, e := Import(root, nil)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestRepeatedClassifierAttemptsRemainDistinctObservations(t *testing.T) {
	first := anotherAttempt(t, "request-first")
	second := anotherAttempt(t, "request-second")
	if first.CaseID != second.CaseID || first.ObservationID == second.ObservationID {
		t.Fatal("case and observation identities are not separated")
	}
	summary, e := Summarize([]Record{first, second})
	if e != nil {
		t.Fatal(e)
	}
	if summary.DatasetCases != 1 || summary.Observations != 2 || len(summary.Groups) != 1 || summary.Groups[0].Cases != 1 || summary.Groups[0].Observations != 2 || summary.Groups[0].Pending != 2 {
		t.Fatal(summary)
	}
	label, e := Adjudicate(first, judgment("context_gap", "human"))
	if e != nil {
		t.Fatal(e)
	}
	summary, e = Summarize([]Record{first, label, second})
	if e != nil || summary.DatasetCases != 1 || summary.Observations != 2 || summary.Groups[0].Pending != 1 || summary.Groups[0].HumanDeclaredJudgments["context_gap"] != 1 {
		t.Fatal(summary, e)
	}
	second.Supersedes = first.ID
	second = identify(second)
	if _, e = Summarize([]Record{first, second}); e == nil {
		t.Fatal("another actual attempt superseded the first")
	}
}
func TestForkedJudgmentsStillRequireResolution(t *testing.T) {
	original := anotherAttempt(t, "request-first")
	first, e := Adjudicate(original, judgment("confirmed_correct", "human"))
	if e != nil {
		t.Fatal(e)
	}
	second, e := Adjudicate(original, judgment("editorial_disagreement", "human"))
	if e != nil {
		t.Fatal(e)
	}
	if first.ObservationID != second.ObservationID {
		t.Fatal("adjudication changed observation identity")
	}
	if _, e = Summarize([]Record{original, first, second}); e == nil {
		t.Fatal("forked judgments were silently counted")
	}
	corrected, e := Adjudicate(first, judgment("editorial_disagreement", "human"))
	if e != nil {
		t.Fatal(e)
	}
	summary, e := Summarize([]Record{original, first, corrected})
	if e != nil || summary.Observations != 1 || summary.Groups[0].HumanDeclaredJudgments["editorial_disagreement"] != 1 {
		t.Fatal(summary, e)
	}
}
func TestObservationIdentityBindsRequestAndWorkReport(t *testing.T) {
	original := anotherAttempt(t, "request-first")
	for _, change := range []string{"provider", "report"} {
		r := clone(original)
		if change == "provider" {
			r.Prediction.Response.ID = "request-replaced"
		} else {
			ref := r.Artifacts["report.json"]
			ref.SHA256 = digest([]byte("other work report"))
			r.Artifacts["report.json"] = ref
		}
		r = identify(r)
		if e := Validate(r); e == nil {
			t.Fatal("observation identity accepted changed input", change)
		}
	}
}

func TestSkippedClassifierIsNotAClassificationError(t *testing.T) {
	root := fixture(t)
	proofPath := filepath.Join(root, "evidence/documentation-routing.json")
	var p proof
	if _, e := read(proofPath, &p); e != nil {
		t.Fatal(e)
	}
	p.Packet.Complete = false
	p.Report = doctriage.SkipIncomplete(p.Packet, jev.DefaultModel)
	var e error
	p.Routing, e = doctriage.SelectRoute(p.Packet, p.Report, doctriage.DefaultRoutingPolicy())
	if e != nil {
		t.Fatal(e)
	}
	proofHash := save(t, proofPath, p)
	save(t, filepath.Join(root, "routing.json"), p.Routing)
	save(t, filepath.Join(root, "controller-evidence-sha256.json"), map[string]string{proofPath: proofHash})
	var result workcycle.Report
	if _, e = read(filepath.Join(root, "report.json"), &result); e != nil {
		t.Fatal(e)
	}
	result.Request.DocumentationEntry.Route = "docs-assess"
	result.Request.DocumentationEntry.Decision = nil
	result.Observations = []workcycle.Observation{{Stage: "docs-assess"}}
	reportHash := save(t, filepath.Join(root, "report.json"), result)
	save(t, filepath.Join(root, "receipt.json"), receipt{ReportSHA256: reportHash, ContextSHA256: p.ContextSHA256, CandidateID: result.Candidate.ID, Evidence: map[string]string{proofPath: proofHash}})
	record, e := Import(root, nil)
	if e != nil {
		t.Fatal(e)
	}
	if record.Prediction.Mode != "preflight" || record.Prediction.RequestAttempted || record.Prediction.Response != nil || record.Observed.Origin != "agent" {
		t.Fatal(record)
	}
	summary, e := Summarize([]Record{record})
	if e != nil || summary.Groups[0].SkippedClassifications != 1 || summary.Groups[0].Pending != 1 {
		t.Fatal(summary, e)
	}
	if _, e = Adjudicate(record, judgment("missed_needed_change", "human")); e == nil {
		t.Fatal("a skipped request was labeled a false negative")
	}
	if _, e = Adjudicate(record, judgment("context_gap", "human")); e != nil {
		t.Fatal(e)
	}
}
