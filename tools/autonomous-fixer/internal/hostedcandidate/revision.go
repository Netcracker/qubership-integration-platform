package hostedcandidate

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

var revisionBranch = regexp.MustCompile(`^agent/fix/[1-9][0-9]*/[1-9][0-9]*-[1-9][0-9]*$`)
var repositoryName = regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`)

type RevisionOptions struct {
	Workspace, Repository, Branch, Head, Token string
	Pull, Comment, Run                         int64
	CreatedAt                                  time.Time
	Scope                                      []string
	CheckHead                                  func(context.Context, string) (alreadyPublished bool, err error)
}

func PublishRevision(ctx context.Context, candidate Candidate, opts RevisionOptions) (string, error) {
	if !repositoryName.MatchString(opts.Repository) || opts.Token == "" {
		return "", fmt.Errorf("invalid revision repository or missing publication token")
	}
	return publishRevision(ctx, candidate, opts, "https://github.com/"+opts.Repository+".git")
}

func publishRevision(ctx context.Context, candidate Candidate, opts RevisionOptions, remote string) (string, error) {
	if !revisionBranch.MatchString(opts.Branch) || opts.Pull <= 0 || opts.Comment <= 0 || opts.Run <= 0 ||
		opts.CreatedAt.IsZero() || opts.CheckHead == nil {
		return "", fmt.Errorf("invalid revision publication identity")
	}
	if err := Validate(candidate, opts.Head, opts.Scope); err != nil {
		return "", err
	}
	tree, err := candidateTree(ctx, opts.Workspace, candidate)
	if err != nil {
		return "", err
	}
	identity := []string{
		"GIT_AUTHOR_NAME=github-actions[bot]", "GIT_AUTHOR_EMAIL=41898282+github-actions[bot]@users.noreply.github.com",
		"GIT_COMMITTER_NAME=github-actions[bot]", "GIT_COMMITTER_EMAIL=41898282+github-actions[bot]@users.noreply.github.com",
		"GIT_AUTHOR_DATE=" + opts.CreatedAt.Format(time.RFC3339), "GIT_COMMITTER_DATE=" + opts.CreatedAt.Format(time.RFC3339),
	}
	message := fmt.Sprintf("fix: address review feedback on #%d\n\nqip-agent-revision:%d:%d:%d\n", opts.Pull, opts.Pull, opts.Comment, opts.Run)
	data, err := gitWithEnv(ctx, opts.Workspace, "", []byte(message), identity, "commit-tree", tree, "-p", opts.Head)
	if err != nil {
		return "", err
	}
	tip := strings.TrimSpace(string(data))
	if !sha.MatchString(tip) {
		return "", fmt.Errorf("invalid revision commit")
	}
	already, err := opts.CheckHead(ctx, tip)
	if err != nil {
		return "", err
	}
	if already {
		return tip, nil
	}
	// The commit has exactly one parent. The lease requires that parent to remain the remote head.
	branch := "refs/heads/" + opts.Branch
	var credentials []string
	if opts.Token != "" {
		credentials = []string{
			"GIT_CONFIG_COUNT=1", "GIT_CONFIG_KEY_0=http.https://github.com/.extraheader",
			"GIT_CONFIG_VALUE_0=AUTHORIZATION: basic " + base64.StdEncoding.EncodeToString([]byte("x-access-token:"+opts.Token)),
		}
	}
	_, err = gitWithEnv(ctx, opts.Workspace, "", nil, credentials,
		"push", "--porcelain", "--force-with-lease="+branch+":"+opts.Head, "--", remote, tip+":"+branch)
	if err != nil {
		already, checkErr := opts.CheckHead(ctx, tip)
		if !already {
			return "", errors.Join(err, checkErr)
		}
	}
	return tip, nil
}
