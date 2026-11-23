package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docpacket"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestPrepareFetchesMergedPRHead(t *testing.T) {
	ctx := context.Background()
	work := t.TempDir()
	repo := filepath.Join(work, "repo")
	remote := filepath.Join(work, "remote.git")
	runGit := func(args ...string) string {
		t.Helper()
		out, err := exec.Command("git", args...).CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v %s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	runGit("init", "--bare", remote)
	runGit("init", "-b", "main", repo)
	runGit("-C", repo, "config", "user.email", "test@example.com")
	runGit("-C", repo, "config", "user.name", "Test")
	runGit("-C", repo, "commit", "--allow-empty", "-m", "Initial")
	merge := runGit("-C", repo, "rev-parse", "HEAD")
	runGit("-C", repo, "push", remote, "main")
	runGit("-C", repo, "commit", "--allow-empty", "-m", "Original PR head")
	head := runGit("-C", repo, "rev-parse", "HEAD")
	runGit("-C", repo, "push", remote, "HEAD:refs/pull/42/head")
	checkout := filepath.Join(work, "checkout")
	runGit("clone", "--no-local", "--single-branch", "--branch", "main", remote, checkout)
	if git(ctx, checkout, "cat-file", "-e", head+"^{commit}") == nil {
		t.Fatal("checkout already contains the PR head")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/graphql" {
			http.NotFound(w, r)
			return
		}
		resp := map[string]any{"number": 42, "merged": true, "baseRefName": "main", "mergeCommit": map[string]string{"oid": merge}, "headRefOid": head, "commits": map[string]int{"totalCount": 1}}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{"repository": map[string]any{"pullRequest": resp}}})
	}))
	defer server.Close()
	api, err := fixer.NewGitHubClient("owner/repo", "test", server.Client(), server.URL)
	if err != nil {
		t.Fatal(err)
	}
	client := &docgithub.Client{API: api, Repository: "owner/repo"}
	pr := docpacket.PR{Number: 42, MergeSHA: merge, Target: "main"}
	got, err := preparePRHead(ctx, client, checkout, pr)
	if err != nil || got.SHA != head || got.CommitCount != 1 || git(ctx, checkout, "cat-file", "-e", head+"^{commit}") != nil {
		t.Fatalf("PR head: %+v %v", got, err)
	}
	pr.MergeSHA = strings.Repeat("a", 40)
	if _, err := preparePRHead(ctx, client, checkout, pr); err == nil {
		t.Fatal("accepted metadata for a different merge")
	}
}
