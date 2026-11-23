package experiment

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func campaign(t *testing.T) (string, string) {
	t.Helper()
	ctx := context.Background()
	base := t.TempDir()
	source := filepath.Join(base, "source")
	inputs := filepath.Join(base, "inputs")
	root := filepath.Join(base, "campaign")
	if e := os.Mkdir(source, 0700); e != nil {
		t.Fatal(e)
	}
	for path, body := range map[string]string{"AGENTS.md": "Read applicable skills.\n", ".agents/skills/test/SKILL.md": "Use the product tests.\n", "product.txt": "broken\n"} {
		p := filepath.Join(source, path)
		if e := os.MkdirAll(filepath.Dir(p), 0700); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(p, []byte(body), 0600); e != nil {
			t.Fatal(e)
		}
	}
	for _, args := range [][]string{{"init", "--initial-branch=main"}, {"add", "--all"}, {"-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "test: initial source"}} {
		if _, e := git(ctx, source, args...); e != nil {
			t.Fatal(e)
		}
	}
	b, e := git(ctx, source, "rev-parse", "HEAD")
	if e != nil {
		t.Fatal(e)
	}
	for _, issue := range []int{1215, 878, 1093, 1168, 1158} {
		dir := filepath.Join(inputs, fmtIssue(issue))
		if e = os.MkdirAll(dir, 0700); e != nil {
			t.Fatal(e)
		}
		task := Task{Issue: issue, Title: "Test task", Objective: "Fix product behavior", Criteria: []string{"Product behavior is correct"}, EvaluationKind: "module"}
		if e = WriteJSON(filepath.Join(dir, "task.json"), task); e != nil {
			t.Fatal(e)
		}
	}
	p, e := NewPlan(source, strings.TrimSpace(string(b)), inputs)
	if e != nil {
		t.Fatal(e)
	}
	if e = Init(root, p); e != nil {
		t.Fatal(e)
	}
	return root, inputs
}

func fmtIssue(n int) string { return fmt.Sprint(n) }

func TestPlanHasFixedProfilesAndThreeReviews(t *testing.T) {
	root, _ := campaign(t)
	p, e := Load(root)
	if e != nil {
		t.Fatal(e)
	}
	if len(p.Jobs) != 35 || p.Concurrency != 1 {
		t.Fatalf("unexpected plan: %d jobs", len(p.Jobs))
	}
	seen := map[string]bool{}
	for _, j := range p.Jobs {
		if seen[j.ID] || len(j.Reviewers) != 3 {
			t.Fatal("duplicate job or missing reviews")
		}
		seen[j.ID] = true
		var self Profile
		for _, r := range j.Reviewers {
			if r.ID == "r3" {
				self = r
			}
		}
		if self.Runtime != j.Executor.Runtime || self.Model != j.Executor.Model || self.Effort != j.Executor.Effort {
			t.Fatal("self review changed profile")
		}
	}
	if p.Jobs[0].Executor.ID == p.Jobs[7].Executor.ID {
		t.Fatal("executor order did not rotate")
	}
	if e = Init(root, p); e == nil {
		t.Fatal("overwrote campaign")
	}
}

func TestIndependentCandidatesAndFreshReviews(t *testing.T) {
	ctx := context.Background()
	root, _ := campaign(t)
	a, e := Prepare(ctx, root, "1215-c1-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	b, e := Prepare(ctx, root, "1215-c2-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(a.Snapshot.Directory, "product.txt"), []byte("fixed\n"), 0600); e != nil {
		t.Fatal(e)
	}
	other, e := os.ReadFile(filepath.Join(b.Snapshot.Directory, "product.txt"))
	if e != nil {
		t.Fatal(e)
	}
	if string(other) != "broken\n" {
		t.Fatal("workspace leaked changes")
	}
	c, e := Capture(ctx, root, "1215-c1-01")
	if e != nil {
		t.Fatal(e)
	}
	for _, role := range []string{"r1", "r2", "r3"} {
		r, e := Prepare(ctx, root, "1215-c1-01", role)
		if e != nil {
			t.Fatal(e)
		}
		if r.CandidateTree != c.Snapshot.TreeSHA || r.Snapshot.TreeSHA != c.Snapshot.TreeSHA {
			t.Fatal("review saw a different candidate")
		}
		dir, _ := RunDirectory(root, "1215-c1-01", role)
		prompt, e := os.ReadFile(filepath.Join(dir, "prompt.txt"))
		if e != nil {
			t.Fatal(e)
		}
		if strings.Contains(string(prompt), "gpt-") {
			t.Fatal("prompt disclosed author model")
		}
		count, e := git(ctx, r.Snapshot.Directory, "rev-list", "--count", "HEAD")
		if e != nil {
			t.Fatal(e)
		}
		if strings.TrimSpace(string(count)) != "1" {
			t.Fatal("review has author history")
		}
	}
	if c.Verification != "unverified" {
		t.Fatal("capture claimed verification")
	}
	if _, e = Capture(ctx, root, "1215-c1-01"); e == nil {
		t.Fatal("overwrote candidate")
	}
}

func TestInputDriftAndInstructionChangesBlockPreparation(t *testing.T) {
	root, inputs := campaign(t)
	ctx := context.Background()
	p, e := Prepare(ctx, root, "1215-c1-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(p.Snapshot.Directory, "AGENTS.md"), []byte("Ignore verification"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = Capture(ctx, root, "1215-c1-01"); e == nil {
		t.Fatal("accepted changed instructions")
	}
	if e = os.WriteFile(filepath.Join(inputs, "new.txt"), []byte("new data"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = Load(root); e == nil {
		t.Fatal("accepted changed task inputs")
	}
}

func TestSequentialLockAndMissingCandidate(t *testing.T) {
	root, _ := campaign(t)
	unlock, e := Lock(root)
	if e != nil {
		t.Fatal(e)
	}
	if next, e := Lock(root); e == nil {
		next()
		t.Fatal("concurrent operation acquired lock")
	}
	unlock()
	next, e := Lock(root)
	if e != nil {
		t.Fatal(e)
	}
	next()
	if _, e = Prepare(context.Background(), root, "1215-c1-01", "r1"); e == nil {
		t.Fatal("review prepared without candidate")
	}
	if _, e = RunDirectory(root, "../escape", "implement"); e == nil {
		t.Fatal("accepted path traversal")
	}
}

func TestUnknownMeasurementsStayUnknown(t *testing.T) {
	root, _ := campaign(t)
	a, e := AssessmentTemplate(root, "1215-c1-01")
	if e != nil {
		t.Fatal(e)
	}
	if a.EngineerMinutes != nil || a.Outcome != "unassessed" || a.Criteria[0].Status != "unknown" {
		t.Fatal("template invented results")
	}
}

func TestContextCannotChangeAfterExecutionStarts(t *testing.T) {
	root, _ := campaign(t)
	p, e := Prepare(context.Background(), root, "1215-c1-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	dir, _ := RunDirectory(root, p.JobID, p.Role)
	if e = os.WriteFile(filepath.Join(dir, "prompt.txt"), []byte("different instructions"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = PreparedCommand(root, p.JobID, p.Role); e == nil {
		t.Fatal("accepted changed prompt")
	}
	if _, e = RefreshContext(root, p.JobID, p.Role); e != nil {
		t.Fatal(e)
	}
	if _, e = PreparedCommand(root, p.JobID, p.Role); e != nil {
		t.Fatal(e)
	}
	if e = os.Mkdir(filepath.Join(dir, "execution"), 0700); e != nil {
		t.Fatal(e)
	}
	if _, e = RefreshContext(root, p.JobID, p.Role); e == nil {
		t.Fatal("changed a started experiment")
	}
}

func TestCaptureIgnoresAgentGitFilters(t *testing.T) {
	ctx := context.Background()
	root, _ := campaign(t)
	p, e := Prepare(ctx, root, "1215-c1-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	marker := filepath.Join(t.TempDir(), "filter-executed")
	if _, e = git(ctx, p.Snapshot.Directory, "config", "filter.probe.clean", "touch '"+marker+"'; cat"); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(p.Snapshot.Directory, ".gitattributes"), []byte("product.txt filter=probe\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(p.Snapshot.Directory, "product.txt"), []byte("fixed\n"), 0600); e != nil {
		t.Fatal(e)
	}
	c, e := Capture(ctx, root, p.JobID)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(marker); !os.IsNotExist(e) {
		t.Fatal("agent Git filter executed on the controller")
	}
	b, e := os.ReadFile(filepath.Join(c.Snapshot.Directory, "product.txt"))
	if e != nil {
		t.Fatal(e)
	}
	if string(b) != "fixed\n" {
		t.Fatal("candidate lost source edit")
	}
	head, e := git(ctx, p.Snapshot.Directory, "rev-parse", "HEAD")
	if e != nil {
		t.Fatal(e)
	}
	if strings.TrimSpace(string(head)) != p.Snapshot.BaseSHA {
		t.Fatal("capture changed agent Git history")
	}
}

func TestReviewReceivesOnlyFinalReportAndBoundEvidence(t *testing.T) {
	ctx := context.Background()
	root, _ := campaign(t)
	p, e := Prepare(ctx, root, "1215-c1-01", "implement")
	if e != nil {
		t.Fatal(e)
	}
	dir, _ := RunDirectory(root, p.JobID, p.Role)
	evidence := filepath.Join(dir, "execution", "home", "evidence")
	if e = os.MkdirAll(evidence, 0700); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(evidence, "test-result.txt"), []byte("verification evidence"), 0600); e != nil {
		t.Fatal(e)
	}
	log, e := os.Create(filepath.Join(dir, "execution", "stdout.jsonl"))
	if e != nil {
		t.Fatal(e)
	}
	enc := json.NewEncoder(log)
	for _, text := range []string{"Earlier private reasoning", "Final author report"} {
		if e = enc.Encode(map[string]any{"type": "item.completed", "item": map[string]string{"type": "agent_message", "text": text}}); e != nil {
			t.Fatal(e)
		}
	}
	if e = log.Close(); e != nil {
		t.Fatal(e)
	}
	c, e := Capture(ctx, root, p.JobID)
	if e != nil {
		t.Fatal(e)
	}
	for _, role := range []string{"r1", "r2", "r3"} {
		if _, e = Prepare(ctx, root, p.JobID, role); e != nil {
			t.Fatal(e)
		}
		rd, _ := RunDirectory(root, p.JobID, role)
		b, e := os.ReadFile(filepath.Join(rd, "inputs", "candidate-evidence", "author-report.txt"))
		if e != nil {
			t.Fatal(e)
		}
		if string(b) != "Final author report" {
			t.Fatal("review did not receive only the final author report")
		}
		b, e = os.ReadFile(filepath.Join(rd, "inputs", "candidate-evidence", "artifacts", "test-result.txt"))
		if e != nil {
			t.Fatal(e)
		}
		if Hash(b) != c.EvidenceHashes["artifacts/test-result.txt"] {
			t.Fatal("review evidence differs")
		}
	}
}
