package agentci

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestObserverFollowsOrdinaryPullRequestWorkflows(t *testing.T) {
	directory := filepath.Join("..", "..", "..", "..", ".github", "workflows")
	data, err := os.ReadFile(filepath.Join(directory, "agent-ci.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	_, triggers, ok := strings.Cut(string(data), "    workflows:\n")
	if !ok {
		t.Fatal("observer has no explicit workflow allowlist")
	}
	triggers, _, _ = strings.Cut(triggers, "    types: [completed]\n")
	allowed := make(map[string]bool)
	for line := range strings.SplitSeq(triggers, "\n") {
		if name, ok := strings.CutPrefix(line, "      - "); ok {
			allowed[name] = true
		}
	}
	// Revision completion wakes the observer after a worker releases its PR.
	if !allowed["QIP agent work"] {
		t.Fatal("observer does not follow completed revisions")
	}
	delete(allowed, "QIP agent work")
	files, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	pullRequestTrigger := regexp.MustCompile(`(?m)^  (pull_request|pull_request_target|pull_request_review):`)
	workflowName := regexp.MustCompile(`(?m)^name: (.+)$`)
	for _, file := range files {
		if ext := filepath.Ext(file.Name()); ext != ".yaml" && ext != ".yml" {
			continue
		}
		switch file.Name() {
		case "agent-ci.yaml", "agent-docs-reconcile-merge.yaml", "agent-outcome.yaml":
			continue
		}
		contents, err := os.ReadFile(filepath.Join(directory, file.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if !pullRequestTrigger.Match(contents) {
			continue
		}
		match := workflowName.FindSubmatch(contents)
		if len(match) != 2 {
			t.Errorf("missing name in %s", file.Name())
			continue
		}
		name := strings.Trim(string(match[1]), `"'`)
		if !allowed[name] {
			t.Errorf("observer does not follow PR workflow %q in %s", name, file.Name())
		}
		delete(allowed, name)
	}
	for name := range allowed {
		t.Errorf("observer follows workflow %q outside ordinary PR CI", name)
	}
}

func TestRevisionCompletionRoutesToPullInsteadOfCheckingWorkerSHA(t *testing.T) {
	repo := fixer.Repository{ID: 9, FullName: "org/repo", DefaultBranch: "main"}
	event := Event{Action: "completed", Repository: repo}
	event.WorkflowRun.WorkflowRun = fixer.WorkflowRun{ID: 77, Status: "completed", Path: ".github/workflows/agent-work.yaml", HeadBranch: repo.DefaultBranch, HeadRepository: repo, HeadSHA: strings.Repeat("a", 40)}
	for _, c := range []struct {
		title, trigger string
		pr             int64
	}{
		{"qip-agent-auto-revise|12|70", "workflow_dispatch", 12},
		{"qip-agent-revise|12|71", "issue_comment", 12},
		{"qip-agent-work|12|exec|1|1", "workflow_dispatch", 0},
		{"qip-agent-auto-revise|12|70", "issue_comment", 0},
		{"qip-agent-revise|12|invalid", "issue_comment", 0},
	} {
		event.WorkflowRun.DisplayTitle, event.WorkflowRun.Event = c.title, c.trigger
		if got := completedRevision(repo, event); got != c.pr {
			t.Fatalf("%s: got PR %d, want %d", c.title, got, c.pr)
		}
	}
	event.WorkflowRun.DisplayTitle, event.WorkflowRun.Event = "qip-agent-auto-revise|12|70", "workflow_dispatch"
	event.WorkflowRun.HeadBranch = "feature"
	if got := completedRevision(repo, event); got != 0 {
		t.Fatalf("routed worker on branch %q to PR %d", event.WorkflowRun.HeadBranch, got)
	}
}
