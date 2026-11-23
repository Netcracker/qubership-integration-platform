package localwork

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type routingFixture struct{ requests []jev.Request }

func (f *routingFixture) Classify(_ context.Context, q jev.Request) (jev.Response, error) {
	f.requests = append(f.requests, q)
	r := jev.Response{ID: "fixture", Model: jev.DefaultModel, Answers: map[string]jev.Answer{}}
	for id, v := range q.Questions {
		a := jev.Answer{Type: "choice", Choice: "uncertain", Probabilities: map[string]float64{}}
		for k := range v.Criteria {
			a.Probabilities[k] = 0
		}
		a.Probabilities["uncertain"] = 1
		r.Answers[id] = a
	}
	return r, nil
}
func TestEngineeringRoutingRecordsEvidenceAndNewFiles(t *testing.T) {
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	c := &routingFixture{}
	d.triageClient = c
	snap, e := d.Snapshot(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	a, e := d.Advise(context.Background(), workcycle.AdviceInput{Phase: "intake", Request: d.Request, Candidate: snap})
	if e != nil {
		t.Fatal(e)
	}
	if e = d.checkArtifacts(); e != nil {
		t.Fatal(e)
	}
	if _, e = d.Run(context.Background(), workcycle.Invocation{Request: d.Request, Stage: workcycle.ContractStage, Candidate: snap}); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(cfg.Workspace, "engine/new.go"), []byte("package engine\n// tenant access path\n"), 0600); e != nil {
		t.Fatal(e)
	}
	snap, _ = d.Snapshot(context.Background())
	if _, e = d.Advise(context.Background(), workcycle.AdviceInput{Phase: "review", Request: d.Request, Candidate: snap}); e != nil {
		t.Fatal(e)
	}
	state := c.requests[1].State.(map[string]any)
	if !strings.Contains(state["diff"].(string), "tenant access path") {
		t.Fatal("new file omitted from classifier input")
	}
	if e = os.WriteFile(a.Evidence, []byte("modified"), 0600); e != nil {
		t.Fatal(e)
	}
	if d.checkArtifacts() == nil {
		t.Fatal("classification evidence mutation went undetected")
	}
}
func TestClassifierCredentialNotInheritedByNativeProcess(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "fixture-secret")
	cfg := fixture(t)
	d, e := Prepare(context.Background(), cfg)
	if e != nil {
		t.Fatal(e)
	}
	env, e := d.environment(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	for _, v := range env {
		if strings.Contains(v, "fixture-secret") {
			t.Fatal("classifier key entered native environment")
		}
	}
}

func TestEngineeringAdviceRunsThroughNativeCycle(t *testing.T) {
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	c := &routingFixture{}
	d.triageClient = c
	report, e := workcycle.Run(context.Background(), d.Request, d)
	if e != nil || report.Status != "verified" || len(report.Routing) != 4 || len(c.requests) != 3 {
		t.Fatalf("status=%s routing=%d calls=%d error=%v", report.Status, len(report.Routing), len(c.requests), e)
	}
	if e = d.checkArtifacts(); e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(filepath.Join(cfg.OutputDirectory, "receipt.json")); e != nil {
		t.Fatal(e)
	}
}

type unavailableClassifier struct{}

func (unavailableClassifier) Classify(context.Context, jev.Request) (jev.Response, error) {
	return jev.Response{}, errors.New("provider unavailable")
}
func TestEngineeringProviderFailurePreservesNativeCycle(t *testing.T) {
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	d.triageClient = unavailableClassifier{}
	report, e := workcycle.Run(context.Background(), d.Request, d)
	if e != nil || report.Status != "verified" || len(report.Routing) != 4 {
		t.Fatalf("%+v %v", report, e)
	}
	if e = d.checkArtifacts(); e != nil {
		t.Fatal(e)
	}
}
func TestKnownProtocolFailureNeedsNoProviderKey(t *testing.T) {
	t.Setenv("OPENROUTER_API_KEY", "")
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	d.failureKind = "agent_protocol"
	snap, _ := d.Snapshot(context.Background())
	advice, e := d.Advise(context.Background(), workcycle.AdviceInput{Phase: "failure", Request: d.Request, Candidate: snap, Failure: "stage returned invalid JSON"})
	if e != nil || advice.FailureKind != "agent_protocol" {
		t.Fatalf("%+v %v", advice, e)
	}
}

func TestEngineeringQuestionIncludesActualStageAndProcessDiagnostic(t *testing.T) {
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	d.step = 1
	dir := filepath.Join(cfg.OutputDirectory, "step-0001-reproduce")
	if e = os.MkdirAll(dir, 0700); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "process.json"), []byte(`{"ExitCode":0,"Classification":"completed_unverified"}`), 0600); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "stderr.log"), []byte("ERROR registry request failed: Authorization: Bearer sk-privatecredential123456\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "progress.json"), []byte(`{"stage":"reproduce","last_command":{"command":"curl https://example.test","status":"completed","exit_code":22,"output_excerpt":"HTTP 503 service unavailable"}}`), 0600); e != nil {
		t.Fatal(e)
	}
	c := &routingFixture{}
	d.triageClient = c
	snap, _ := d.Snapshot(context.Background())
	_, e = d.Advise(context.Background(), workcycle.AdviceInput{Phase: "failure", Stage: workcycle.ReproduceStage, Request: d.Request, Candidate: snap, Question: &workcycle.Question{Text: "Is the registry available?", Needed: "Access to the registry.", Evidence: []string{"diagnostic"}}})
	if e != nil {
		t.Fatal(e)
	}
	b, _ := json.Marshal(c.requests[0].State)
	if !strings.Contains(string(b), `"stage":"reproduce"`) || !strings.Contains(string(b), `"process_exit":0`) || !strings.Contains(string(b), "registry request failed") || !strings.Contains(string(b), "exit: 22") || !strings.Contains(string(b), "HTTP 503") || strings.Contains(string(b), "privatecredential") {
		t.Fatal(string(b))
	}
	if _, ok := c.requests[0].Questions["question_action"]; !ok {
		t.Fatal("next action omitted")
	}
}

func TestEngineeringReviewOmitsAlreadyMandatoryLenses(t *testing.T) {
	cfg := fixture(t)
	cfg.EngineeringTriage = &EngineeringTriage{}
	d, e := prepareHistoricalTriageFixture(cfg)
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(cfg.Workspace, "engine/new.go"), []byte("package engine\n"), 0600); e != nil {
		t.Fatal(e)
	}
	c := &routingFixture{}
	d.triageClient = c
	snap, _ := d.Snapshot(context.Background())
	_, e = d.Advise(context.Background(), workcycle.AdviceInput{Phase: "review", Request: d.Request, Candidate: snap, MandatoryReviews: []string{"correctness", "compatibility"}})
	if e != nil {
		t.Fatal(e)
	}
	if _, ok := c.requests[0].Questions["review_compatibility"]; ok {
		t.Fatal("mandatory lens classified again")
	}
}

func TestFailureDiagnosticRedactsHeadersAndURLCredentials(t *testing.T) {
	input := "ERROR request Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.credential.signature api_key=private-key password: local-password https://alice:privatepass@example.test/path"
	actual := diagnosticExcerpt(input)
	for _, secret := range []string{"eyJhbGci", "private-key", "local-password", "privatepass"} {
		if strings.Contains(actual, secret) {
			t.Fatal("credential retained")
		}
	}
	if !strings.Contains(actual, "ERROR request") || !strings.Contains(actual, "example.test/path") {
		t.Fatal("diagnostic context lost")
	}
}

// Archived experiments used this configuration before engineering runs stopped calling Jev.
func prepareHistoricalTriageFixture(cfg Config) (*Driver, error) {
	d, err := Prepare(context.Background(), cfg)
	if err == nil {
		d.Config.EngineeringTriage = cfg.EngineeringTriage
	}
	return d, err
}

func TestEngineeringRunsIgnoreLegacyJevSettings(t *testing.T) {
	for _, workflow := range []string{"", "fix-issue", "agent", "staged"} {
		t.Run("workflow-"+workflow, func(t *testing.T) {
			cfg := agentFixture(t)
			cfg.Workflow = workflow
			cfg.EngineeringTriage = &EngineeringTriage{CoverageAudit: "recheck"}
			d, err := Prepare(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			if d.Config.EngineeringTriage != nil {
				t.Fatal("engineering classifier enabled")
			}
			client := &routingFixture{}
			d.triageClient = client
			for _, phase := range []string{"intake", "review", "failure"} {
				a, e := d.Advise(context.Background(), workcycle.AdviceInput{Phase: phase})
				if a != nil || e != nil {
					t.Fatal(a, e)
				}
			}
			if a := d.auditCoverage(context.Background(), workcycle.AgentAssessmentRequest{}, nil); a != nil || len(client.requests) != 0 {
				t.Fatalf("audit %v; requests %d", a, len(client.requests))
			}
		})
	}
}
