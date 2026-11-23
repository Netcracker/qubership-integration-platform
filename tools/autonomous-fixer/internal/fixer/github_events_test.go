package fixer

import (
	"strings"
	"testing"
)

func TestDecodeGitHubCommands(t *testing.T) {
	user := GitHubUser{ID: 12, Login: "human-user", Type: "User"}
	payload := GitHubPayload{Repository: Repository{FullName: "o/r"}, Issue: Issue{ID: 1, Number: 4, Labels: []Label{{Name: "ready-for-agent"}}}, Comment: Comment{ID: 5, User: user, Body: "/agent answer question/one 2 3 accepted"}, Sender: user, Action: "created"}
	event := DecodeGitHubEvent("issue_comment", payload, "o/r", nil, 0)
	if event == nil || event.QuestionVersion != 2 || event.ContractVersion != 3 || event.QuestionID != "question/one" {
		t.Fatalf("wrong event: %+v", event)
	}
	for _, body := range []string{"/agent fix\n", " /agent fix", "/agent answer q 1 yes", "/agent answer q 1 2 "} {
		payload.Comment.Body = body
		if DecodeGitHubEvent("issue_comment", payload, "o/r", nil, 0) != nil {
			t.Errorf("accepted %q", body)
		}
	}
	payload.Comment.Body = "/agent fix"
	payload.Sender.ID++
	if DecodeGitHubEvent("issue_comment", payload, "o/r", nil, 0) != nil {
		t.Fatal("accepted mismatched sender")
	}
}
func TestWorkerRunBinding(t *testing.T) {
	name := WorkRunName(3, "exec/one", 2, 4)
	identity, err := ParseWorkRunName(name)
	if err != nil || identity.IssueNumber != 3 || identity.Generation != 2 {
		t.Fatal(identity, err)
	}
	expected := WorkerRunExpected{Repository: "o/r", WorkflowID: 9, TrustedSHA: strings.Repeat("a", 40), DefaultBranch: "main", RunName: name}
	run := WorkflowRun{ID: 7, RunAttempt: 1, WorkflowID: 9, HeadRepository: Repository{FullName: "o/r"}, Event: "workflow_dispatch", Path: ".github/workflows/agent-work.yaml", HeadSHA: expected.TrustedSHA, HeadBranch: "main", DisplayTitle: name}
	if !MatchWorkerRun(run, expected) {
		t.Fatal("rejected matching run")
	}
	run.RunAttempt = 2
	if MatchWorkerRun(run, expected) {
		t.Fatal("accepted rerun")
	}
	if _, err := ParseWorkRunName(name + "\n"); err == nil {
		t.Fatal("accepted newline")
	}
}
