package experiment

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func retentionFixture(t *testing.T, root, id string) string {
	t.Helper()
	job := filepath.Join(root, "runs", id)
	for _, role := range []string{"implement", "r1", "r2", "r3"} {
		w := filepath.Join(job, role, "workspace")
		retentionFile(t, filepath.Join(w, "source.txt"), "source")
		if _, err := git(context.Background(), w, "init", "--quiet"); err != nil {
			t.Fatal(err)
		}
		if _, err := git(context.Background(), w, "add", "source.txt"); err != nil {
			t.Fatal(err)
		}
		retentionFile(t, filepath.Join(w, "node_modules", "dependency.js"), "disposable")
		retentionFile(t, filepath.Join(job, role, "execution", "home", ".m2", "repository", "dependency.jar"), "disposable")
		retentionFile(t, filepath.Join(job, role, "execution", "stdout.jsonl"), "raw log")
		retentionFile(t, filepath.Join(job, role, "execution", "home", "evidence", "screenshot.png"), "evidence")
		retentionFile(t, filepath.Join(w, "module", "target", "surefire-reports", "test.xml"), "test evidence")
		var result Execution
		result.Process.FinishedAt = time.Now()
		if err := WriteJSON(filepath.Join(job, role, "execution", "result.json"), result); err != nil {
			t.Fatal(err)
		}
	}
	if err := WriteJSON(filepath.Join(job, "assessment.json"), map[string]any{"outcome": "verified-with-findings", "independent_reviews": []map[string]string{{"role": "r1", "summary": "review.json"}, {"role": "r2"}, {"role": "r3"}}}); err != nil {
		t.Fatal(err)
	}
	retentionFile(t, filepath.Join(job, "candidate", "evidence", "report.log"), "captured evidence")
	retentionFile(t, filepath.Join(job, "candidate", "candidate.patch"), "patch")
	return job
}

func retentionFile(t *testing.T, p, text string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
}

func TestRetentionPreservesEvidenceAndOnlyCleansCompletedCycles(t *testing.T) {
	root, _ := campaign(t)
	job := retentionFixture(t, root, "1215-c1-01")
	second := retentionFixture(t, root, "1215-c2-01")
	incomplete := retentionFixture(t, root, "878-c1-01")
	if err := os.Remove(filepath.Join(incomplete, "r3", "execution", "result.json")); err != nil {
		t.Fatal(err)
	}
	current := retentionFixture(t, root, "1093-c1-01")
	ctx := context.Background()
	paths, _, err := retentionPaths(ctx, job)
	if err != nil {
		t.Fatal(err)
	}
	before, err := retentionHashes(ctx, job, paths)
	if err != nil {
		t.Fatal(err)
	}
	incompleteBefore, _ := retentionHashes(ctx, incomplete, nil)
	currentBefore, _ := retentionHashes(ctx, current, nil)
	plan, err := CleanupCompleted(ctx, root, "1093-c1-01", false)
	if err != nil || len(plan.Jobs) != 2 || len(plan.Jobs[0].Paths) != 8 {
		t.Fatalf("plan: %+v %v", plan, err)
	}
	if _, err = os.Stat(filepath.Join(job, paths[0])); err != nil {
		t.Fatal("dry run removed files", err)
	}
	report, err := CleanupCompleted(ctx, root, "1093-c1-01", true)
	if err != nil || len(report.Jobs) != 2 || !report.Jobs[0].Verified || !report.Jobs[1].Verified {
		t.Fatalf("apply: %+v %v", report, err)
	}
	if _, err = os.Stat(filepath.Join(second, "r3", "workspace", "node_modules")); !os.IsNotExist(err) {
		t.Fatal("second completed cycle not cleaned", err)
	}
	after, err := retentionHashes(ctx, job, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(before, after) {
		t.Fatal("protected files changed")
	}
	for _, pair := range []struct {
		path   string
		hashes map[string]string
	}{{incomplete, incompleteBefore}, {current, currentBefore}} {
		now, err := retentionHashes(ctx, pair.path, nil)
		if err != nil || !reflect.DeepEqual(pair.hashes, now) {
			t.Fatal("excluded job changed", err)
		}
	}
	again, err := CleanupCompleted(ctx, root, "1093-c1-01", true)
	if err != nil || len(again.Jobs) != 0 {
		t.Fatal("cleanup is not idempotent", err)
	}
}

func TestRetentionRejectsSymlinkEscapeAndPreservesTrackedFilesAndLogs(t *testing.T) {
	root, _ := campaign(t)
	job := retentionFixture(t, root, "1215-c1-01")
	w := filepath.Join(job, "implement", "workspace")
	retentionFile(t, filepath.Join(w, "node_modules", "important.log"), "keep")
	retentionFile(t, filepath.Join(w, "node_modules", "logged-package", "trace.jsonl"), "keep nested log")
	retentionFile(t, filepath.Join(w, "node_modules", "disposable-package", "index.js"), "disposable")
	r1 := filepath.Join(job, "r1", "workspace")
	if _, err := git(context.Background(), r1, "add", "node_modules/dependency.js"); err != nil {
		t.Fatal(err)
	}
	paths, _, err := retentionPaths(context.Background(), job)
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range paths {
		if p == "implement/workspace/node_modules" || p == "r1/workspace/node_modules" || p == "implement/workspace/node_modules/logged-package" {
			t.Fatal("protected cache selected", p)
		}
	}
	selected := false
	for _, p := range paths {
		if p == "implement/workspace/node_modules/disposable-package" {
			selected = true
		}
	}
	if !selected {
		t.Fatal("unrelated dependency package retained")
	}
	if _, err = CleanupCompleted(context.Background(), root, "", true); err != nil {
		t.Fatal(err)
	}
	for _, kept := range []string{filepath.Join(w, "node_modules", "important.log"), filepath.Join(w, "node_modules", "logged-package", "trace.jsonl"), filepath.Join(r1, "node_modules", "dependency.js")} {
		if _, err = os.Stat(kept); err != nil {
			t.Fatal("protected dependency file removed", kept, err)
		}
	}
	if _, err = os.Stat(filepath.Join(w, "node_modules", "disposable-package")); !os.IsNotExist(err) {
		t.Fatal("unrelated dependency package remains", err)
	}
	external := t.TempDir()
	retentionFile(t, filepath.Join(external, "keep.txt"), "outside")
	link := filepath.Join(job, "r2", "workspace", "e2e", "node_modules")
	if err = os.MkdirAll(filepath.Dir(link), 0700); err != nil {
		t.Fatal(err)
	}
	if err = os.Symlink(external, link); err != nil {
		t.Fatal(err)
	}
	if _, _, err = retentionPaths(context.Background(), job); err == nil {
		t.Fatal("symlink escape accepted")
	}
	if _, err = os.Stat(filepath.Join(external, "keep.txt")); err != nil {
		t.Fatal(err)
	}
}

func TestRetentionSkipsACompletedWorkspaceStillServingAProcess(t *testing.T) {
	root, _ := campaign(t)
	job := retentionFixture(t, root, "1215-c1-01")
	cmd := exec.Command("sleep", "30")
	cmd.Dir = filepath.Join(job, "implement", "workspace")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	report, err := CleanupCompleted(context.Background(), root, "", true)
	if err != nil || len(report.Jobs) != 0 || report.Skipped["1215-c1-01"] == "" {
		t.Fatalf("live workspace selected: %+v %v", report, err)
	}
}

func TestAutomaticRetentionRequiresOptInAndExcludesCurrentJob(t *testing.T) {
	root, _ := campaign(t)
	job := retentionFixture(t, root, "1215-c1-01")
	ctx := context.Background()
	if err := cleanupBeforeExecution(ctx, root, "878-c1-01"); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(job, "implement", "workspace", "node_modules")
	if _, err := os.Stat(p); err != nil {
		t.Fatal("cleanup ran without opt-in")
	}
	if err := WriteJSON(filepath.Join(root, "retention-policy.json"), map[string]bool{"enabled": true}); err != nil {
		t.Fatal(err)
	}
	if err := cleanupBeforeExecution(ctx, root, "1215-c1-01"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); err != nil {
		t.Fatal("current job removed")
	}
	if err := cleanupBeforeExecution(ctx, root, "878-c1-01"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatal("completed dependencies retained", err)
	}
}
