package docpacket

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestBuildVerifiesCompleteLandedChanges(t *testing.T) {
	dir := t.TempDir()
	git := func(args ...string) string {
		t.Helper()
		b, err := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v %s", args, err, b)
		}
		return strings.TrimSpace(string(b))
	}
	write := func(path, body string) {
		t.Helper()
		file := filepath.Join(dir, path)
		if err := os.MkdirAll(filepath.Dir(file), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, []byte(body), 0644); err != nil {
			t.Fatal(err)
		}
	}
	commit := func(message string) string {
		t.Helper()
		git("add", ".")
		git("commit", "-m", message)
		return git("rev-parse", "HEAD")
	}
	const file = "ui/Buttons.tsx"
	buttons := func(first, second, third string) string {
		spacer := "\n" + strings.Repeat("\n// Separator", 12) + "\n"
		return first + spacer + second + spacer + third + "\n"
	}
	git("init", "-b", "main")
	git("config", "user.email", "test@example.com")
	git("config", "user.name", "Test")
	write("help/docs/current.md", "Current documentation\n")
	write(file, buttons("Copy", "Delete", "Open"))
	commit("Initial buttons")
	git("switch", "-c", "feature")
	write(file, buttons("Duplicate chain", "Delete", "Open"))
	first := commit("Rename the copy button")
	write(file, buttons("Duplicate chain", "Delete chain", "Open"))
	head := commit("Rename the delete button")
	git("switch", "main")
	write(file, buttons("Copy", "Delete", "Open chain"))
	base := commit("Rename the open button on main")

	for _, name := range []string{"rebase", "squash", "merge", "nested merge", "unrelated merge", "missing first change", "wrong commit count", "missing source history"} {
		t.Run(name, func(t *testing.T) {
			git("switch", "-C", "landed", base)
			switch name {
			case "rebase", "wrong commit count", "missing source history":
				git("cherry-pick", first, head)
			case "squash":
				git("merge", "--squash", head)
				commit("Squash the button renames")
			case "merge":
				git("merge", "--no-ff", head, "-m", "Merge the button renames")
			case "nested merge", "unrelated merge":
				git("switch", "-C", "outer", head)
				if name == "unrelated merge" {
					git("reset", "--hard", first)
				}
				write("unrelated.md", "Another task")
				outer := commit("Add unrelated changes")
				git("switch", "landed")
				git("merge", "--no-ff", outer, "-m", "Merge the outer branch")
			case "missing first change":
				git("cherry-pick", head)
			}
			tip := git("rev-parse", "HEAD")
			calls := 0
			cfg := Config{
				RepositoryPath: dir, Repository: "owner/repo", DocsRef: tip,
				Issue:     Issue{Number: 7, Title: "Rename buttons", PRs: []PR{{Number: 42, MergeSHA: tip, Target: "main"}}},
				DocsPaths: []string{"help/docs/current.md"},
				ResolvePRHead: func(context.Context, PR) (PRHead, error) {
					calls++
					count := 2
					if name == "wrong commit count" {
						count = 1
					}
					return PRHead{SHA: head, CommitCount: count}, nil
				},
			}
			if name == "missing source history" {
				cfg.ResolvePRHead = nil
			}
			p, r, err := Build(context.Background(), cfg)
			if name == "unrelated merge" || name == "missing first change" || name == "wrong commit count" || name == "missing source history" {
				if err == nil || !strings.Contains(err.Error(), "PR #42") || p.Complete || len(r.PRs) != 0 {
					t.Fatalf("accepted unverified landing: %+v %+v %v", p, r, err)
				}
				return
			}
			wantBase := base
			if name == "nested merge" {
				wantBase = git("rev-parse", first+"^")
				if p.HeadSHA != head || len(p.ChangedPaths) != 1 || p.ChangedPaths[0] != file || len(r.PRs) != 1 || r.PRs[0].MergeSHA != tip {
					t.Fatalf("included changes outside the nested PR: %v, head %s", p.ChangedPaths, p.HeadSHA)
				}
			}
			if err != nil || !p.Complete || p.BaseSHA != wantBase || len(r.PRs) != 1 || r.PRs[0].BaseSHA != wantBase {
				t.Fatalf("wrong landed range: %+v %+v %v", p, r, err)
			}
			if !strings.Contains(p.Diff, "+Duplicate chain") || !strings.Contains(p.Diff, "+Delete chain") || strings.Contains(p.Diff, "+Open chain") {
				t.Fatalf("wrong landed diff:\n%s", p.Diff)
			}
			if calls != 1 {
				t.Fatalf("PR head lookups: %d", calls)
			}
		})
	}
}
