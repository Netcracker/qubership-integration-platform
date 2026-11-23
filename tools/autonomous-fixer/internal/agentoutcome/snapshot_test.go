package agentoutcome

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

type gitFixture struct {
	t         *testing.T
	workspace string
}

func newGitFixture(t *testing.T) gitFixture {
	g := gitFixture{t: t, workspace: t.TempDir()}
	g.run("init", "-q", "-b", "main")
	g.run("config", "user.name", "Test")
	g.run("config", "user.email", "test@example.org")
	g.run("config", "commit.gpgsign", "false")
	return g
}

func (g gitFixture) run(args ...string) string {
	g.t.Helper()
	command := exec.Command("git", args...)
	command.Dir = g.workspace
	data, err := command.CombinedOutput()
	if err != nil {
		g.t.Fatalf("git %v failed: %v: %s", args, err, data)
	}
	return strings.TrimSpace(string(data))
}

func (g gitFixture) file(name, value string) {
	g.t.Helper()
	if err := os.WriteFile(filepath.Join(g.workspace, name), []byte(value), 0600); err != nil {
		g.t.Fatal(err)
	}
}

func (g gitFixture) commit(message string) string {
	g.t.Helper()
	g.run("add", ".")
	g.run("commit", "-qm", message)
	return g.run("rev-parse", "HEAD")
}

func capture(g gitFixture, head, base, merge string) Snapshot {
	snapshot := Snapshot{HeadSHA: head, BaseSHA: base}
	gitRepository{workspace: g.workspace}.capture(context.Background(), &snapshot, merge)
	return snapshot
}

func compare(g gitFixture, published, final Snapshot) string {
	return gitRepository{workspace: g.workspace}.contentChange(context.Background(), published, final)
}

func TestCompareRebasedPatchAndNewFiles(t *testing.T) {
	g := newGitFixture(t)
	g.file("product.txt", "first\nold\nlast\n")
	base := g.commit("baseline")
	g.run("switch", "-qc", "draft")
	g.file("product.txt", "first\nnew\nlast\n")
	head := g.commit("published fix")
	published := capture(g, head, base, "")

	g.run("switch", "-q", "main")
	g.file("product.txt", "base-only change\nfirst\nold\nlast\n")
	advanced := g.commit("advance base")
	g.run("switch", "-qc", "rebased")
	g.file("product.txt", "base-only change\nfirst\nnew\nlast\n")
	rebased := g.commit("same fix on new base")
	final := capture(g, rebased, advanced, "")
	if published.ComparisonBaseSHA != base || final.ComparisonBaseSHA != advanced ||
		published.PatchSHA256 != final.PatchSHA256 || compare(g, published, final) != "unchanged" || rebased == head {
		t.Fatal("same patch after rebase was misclassified", published, final)
	}

	g.file("new-regression.txt", "regression test\n")
	withTest := g.commit("add a file")
	final = capture(g, withTest, advanced, "")
	if compare(g, published, final) != "changed" || !slices.Equal(final.Paths, []string{"new-regression.txt", "product.txt"}) {
		t.Fatal("new touched file was ignored", final)
	}
}

func TestCompareKeepsBinaryModeWhitespaceAndLocationChanges(t *testing.T) {
	g := newGitFixture(t)
	g.file("binary", "\x00base")
	g.file("script", "one\nneedle\ntwo\nneedle\nthree\n")
	base := g.commit("baseline")
	g.file("binary", "\x00candidate")
	g.file("script", "one\nfixed\ntwo\nneedle\nthree\n")
	head := g.commit("change the first occurrence and binary")
	published := capture(g, head, base, "")

	g.file("script", "one\nneedle\ntwo\nfixed\nthree\n")
	moved := capture(g, g.commit("move the same replacement"), base, "")
	if published.PatchSHA256 != moved.PatchSHA256 || compare(g, published, moved) != "changed" {
		t.Fatal("moving a replacement on the same base must count as a content change")
	}
	g.run("reset", "--hard", head)
	g.file("script", "one\nfixed \ntwo\nneedle\nthree\n")
	whitespace := capture(g, g.commit("add a space in the replacement"), base, "")
	if whitespace.PatchSHA256 == published.PatchSHA256 {
		t.Fatal("patch normalization discarded source whitespace")
	}
	g.run("reset", "--hard", head)
	g.file("binary", "\x00other")
	binary := capture(g, g.commit("change a binary payload"), base, "")
	if binary.PatchSHA256 == published.PatchSHA256 {
		t.Fatal("patch normalization discarded the binary identity")
	}
	g.run("reset", "--hard", head)
	if err := os.Chmod(filepath.Join(g.workspace, "script"), 0700); err != nil {
		t.Fatal(err)
	}
	mode := capture(g, g.commit("make the script executable"), base, "")
	if mode.PatchSHA256 == published.PatchSHA256 {
		t.Fatal("patch normalization discarded file modes")
	}
}

func TestMergeCommitNeverReplacesPullRequestHead(t *testing.T) {
	for _, method := range []string{"merge", "squash"} {
		t.Run(method, func(t *testing.T) {
			g := newGitFixture(t)
			g.file("product.txt", "baseline\n")
			base := g.commit("baseline")
			g.run("switch", "-qc", "draft")
			g.file("product.txt", "candidate\n")
			head := g.commit("candidate")
			published := capture(g, head, base, "")
			g.run("switch", "-q", "main")
			g.file("unrelated.txt", "base only")
			g.commit("advance the base")
			if method == "merge" {
				g.run("merge", "-q", "--no-ff", "draft", "-m", "integrate")
			} else {
				g.run("merge", "-q", "--squash", "draft")
				g.commit("integrate")
			}
			merge := g.run("rev-parse", "HEAD")
			final := capture(g, head, merge, merge)
			if merge == head || final.ComparisonBaseSHA != base || compare(g, published, final) != "unchanged" ||
				!slices.Equal(final.Paths, []string{"product.txt"}) {
				t.Fatal("integration changed the recorded pull request patch", published, final)
			}
		})
	}
}

func TestMovingAnIdenticalEditAcrossBasesChangesContent(t *testing.T) {
	g := newGitFixture(t)
	g.file("script", "one\nneedle\ntwo\nneedle\nthree\n")
	base := g.commit("baseline")
	g.run("switch", "-qc", "draft")
	g.file("script", "one\nfixed\ntwo\nneedle\nthree\n")
	published := capture(g, g.commit("replace the first occurrence"), base, "")
	g.run("switch", "-q", "main")
	g.file("other", "base change\n")
	base = g.commit("advance the base")
	g.file("script", "one\nneedle\ntwo\nfixed\nthree\n")
	final := capture(g, g.commit("replace the second occurrence"), base, "")
	if published.PatchSHA256 != final.PatchSHA256 || compare(g, published, final) != "changed" {
		t.Fatal("transplanted patch applied to a different location")
	}
}
