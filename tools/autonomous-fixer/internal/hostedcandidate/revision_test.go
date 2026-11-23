package hostedcandidate

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func revisionRepo(t *testing.T) (RevisionOptions, Candidate, string, string, func(string, ...string) string) {
	t.Helper()
	original, _, _ := repo(t)
	workspace := original.Workspace
	ctx := context.Background()
	run := func(dir string, args ...string) string {
		t.Helper()
		data, err := git(ctx, dir, "", nil, args...)
		if err != nil {
			t.Fatal(err)
		}
		return strings.TrimSpace(string(data))
	}
	run(workspace, "reset", "--hard", original.Base)
	if err := os.WriteFile(filepath.Join(workspace, "ui", "human"), []byte("Human review edits\n"), 0600); err != nil {
		t.Fatal(err)
	}
	run(workspace, "add", "ui/human")
	run(workspace, "-c", "user.name=Maintainer", "-c", "user.email=maintainer@example.org", "commit", "-qm", "Add review coverage")
	human := run(workspace, "rev-parse", "HEAD")
	branch := "agent/fix/12/345-1"
	remote := t.TempDir()
	run(remote, "init", "--bare")
	run(workspace, "push", remote, human+":refs/heads/"+branch)
	if err := os.WriteFile(filepath.Join(workspace, "ui", "file"), []byte("Apply selected feedback\n"), 0600); err != nil {
		t.Fatal(err)
	}
	candidate, err := Pack(ctx, workspace, human)
	if err != nil {
		t.Fatal(err)
	}
	opts := RevisionOptions{Workspace: workspace, Branch: branch, Head: human, Scope: []string{"ui"}, Pull: 13, Comment: 56, Run: 123,
		CreatedAt: time.Date(2026, 11, 19, 12, 0, 0, 0, time.UTC)}
	return opts, candidate, remote, original.Base, run
}

func TestPublishRevisionFastForwardsTheExistingBranchAndPreservesHumanCommits(t *testing.T) {
	opts, candidate, remote, origin, run := revisionRepo(t)
	remoteHead := "refs/heads/" + opts.Branch
	opts.CheckHead = func(ctx context.Context, proposed string) (bool, error) {
		head := run(remote, "rev-parse", remoteHead)
		return head == proposed, nil
	}
	var published string
	for range 2 {
		tip, err := publishRevision(context.Background(), candidate, opts, remote)
		if err != nil || tip == "" || (published != "" && published != tip) {
			t.Fatalf("revision = %q, %v", tip, err)
		}
		published = tip
	}
	if got := run(remote, "rev-list", "--first-parent", published); got != published+"\n"+opts.Head+"\n"+origin {
		t.Fatalf("commit lineage = %s", got)
	}
	if human := run(remote, "show", published+":ui/human"); human != "Human review edits" {
		t.Fatalf("human change = %q", human)
	}
	if revised := run(remote, "show", published+":ui/file"); revised != "Apply selected feedback" {
		t.Fatalf("revision file = %q", revised)
	}
	if got := run(remote, "for-each-ref", "--format=%(refname)", "refs/heads/"); got != remoteHead {
		t.Fatalf("remote branches = %q", got)
	}
}

func TestPublishRevisionRejectsAnExactHeadRace(t *testing.T) {
	opts, candidate, remote, origin, run := revisionRepo(t)
	remoteHead := "refs/heads/" + opts.Branch
	stale := errors.New("head changed")
	calls := 0
	opts.CheckHead = func(ctx context.Context, proposed string) (bool, error) {
		calls++
		if calls > 1 {
			return false, stale
		}
		// A forced reset after the API check is still an ancestor of the proposed commit.
		run(remote, "update-ref", remoteHead, origin, opts.Head)
		return false, nil
	}
	tip, err := publishRevision(context.Background(), candidate, opts, remote)
	if tip != "" || !errors.Is(err, stale) || calls != 2 || run(remote, "rev-parse", remoteHead) != origin {
		t.Fatalf("publication after a head race = %q, %v, checks %d", tip, err, calls)
	}
}

func TestPublishRevisionSkipsBranchWriteWhenThePullRequestCloses(t *testing.T) {
	opts, candidate, remote, _, run := revisionRepo(t)
	closed := errors.New("pull request closed")
	opts.CheckHead = func(ctx context.Context, proposed string) (bool, error) {
		return false, closed
	}
	tip, err := publishRevision(context.Background(), candidate, opts, remote)
	if tip != "" || !errors.Is(err, closed) || run(remote, "rev-parse", "refs/heads/"+opts.Branch) != opts.Head {
		t.Fatalf("publication after closure = %q, %v", tip, err)
	}
}
