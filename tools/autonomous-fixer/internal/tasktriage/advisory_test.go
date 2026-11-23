package tasktriage

import (
	"context"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

func TestPreReadyUsesOnlyIntakeSnapshot(t *testing.T) {
	p := Packet{Phase: "pre-ready", TaskID: "issue", Task: "Use the contract to settle the expected behavior.", Context: "External contract unavailable.", Diff: "future patch", SourceHints: []string{"later source"}}
	req, err := Build(p)
	if err != nil {
		t.Fatal(err)
	}
	state := req.State.(map[string]any)
	if len(state) != 2 || !strings.Contains(state["issue_at_intake"].(string), "contract") || state["known_access_and_scope"] != p.Context || len(req.Questions) != 2 {
		t.Fatal(req)
	}
	r := jev.Response{Answers: map[string]jev.Answer{
		"next_step":           answer(req.Questions["next_step"], "request_info", .95),
		"missing_information": answer(req.Questions["missing_information"], "integration_contract", .80),
	}}
	s := Select(p, r)
	if s.PreReadyAction != "request_info" || s.MissingInformation != "integration_contract" {
		t.Fatal(s)
	}
	r.Answers["missing_information"] = answer(req.Questions["missing_information"], "integration_contract", .60)
	if s = Select(p, r); s.PreReadyAction != "uncertain" {
		t.Fatal(s)
	}
}

func TestCoverageAdviceBindsQuestionOrderAndAbstainsOnWeakResults(t *testing.T) {
	p := Packet{Phase: "coverage-evidence", TaskID: "issue", CoverageEvidence: []CoverageEvidence{
		{ID: "session-list", Consumer: "browser", Requirement: "Open from Sessions", Observation: "Opened direct URL"},
		{ID: "root", Consumer: "browser", Requirement: "Return to root", Observation: "Test passed"},
	}}
	req, err := Build(p)
	if err != nil {
		t.Fatal(err)
	}
	r := jev.Response{Answers: map[string]jev.Answer{
		"case_1": answer(req.Questions["case_1"], "mismatch", .95),
		"case_2": answer(req.Questions["case_2"], "insufficient", .60),
	}}
	s := Select(p, r)
	if s.CoverageAssessment["session-list"] != "mismatch" || s.CoverageAssessment["root"] != "uncertain" {
		t.Fatal(s)
	}
	if result, err := Evaluate(context.Background(), p, &fake{}); err != nil || result.Error == "" || Validate(result) != nil {
		t.Fatal(result, err)
	}
}

func TestAdvisoryRequestsRedactAndBoundProviderInput(t *testing.T) {
	p := Packet{Phase: "pre-ready", TaskID: "issue",
		Task:    "Authorization: Basic ZXhhbXBsZTpydW50aW1l\nAccess from /home/example/private/source\n" + strings.Repeat("a", 13000),
		Context: "Call https://example.test/foo?x-amz-signature=signed-value&name=check"}
	req, err := Build(p)
	if err != nil {
		t.Fatal(err)
	}
	state := req.State.(map[string]any)
	task := state["issue_at_intake"].(string)
	if strings.Contains(task, "ZXhhbXBsZTpydW50aW1l") || strings.Contains(task, "/home/example") || !strings.HasSuffix(task, " [excerpt truncated]") ||
		strings.Contains(state["known_access_and_scope"].(string), "signed-value") {
		t.Fatal("request retained unbounded or sensitive inputs")
	}
	r := jev.Response{Answers: map[string]jev.Answer{"next_step": answer(req.Questions["next_step"], "ready", .95)}}
	if selection := Select(p, r); selection.PreReadyAction != "uncertain" {
		t.Fatal(selection)
	}
}
