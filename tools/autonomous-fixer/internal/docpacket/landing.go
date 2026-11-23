package docpacket

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
)

func landedBase(ctx context.Context, git func(...string) ([]byte, error), source func(context.Context, PR) (PRHead, error), pr PR, tip string) (string, string, error) {
	parentList, err := git("show", "--no-patch", "--format=%P", tip)
	if err != nil {
		return "", "", err
	}
	parents := strings.Fields(string(parentList))
	if len(parents) == 0 {
		return "", "", fmt.Errorf("landed commit %s has no parent", tip)
	}
	parent := parents[0]
	if len(parents) > 1 {
		return parent, "First parent of the merge commit.", nil
	}
	if source == nil {
		return "", "", fmt.Errorf("provide the original PR head SHA and commit count to verify a linear landing")
	}
	head, err := source(ctx, pr)
	if err != nil {
		return "", "", err
	}
	if head.SHA == "" || head.CommitCount < 1 {
		return "", "", fmt.Errorf("provide the original PR head SHA and commit count to verify a linear landing")
	}
	resolved, err := git("rev-parse", "--verify", "--end-of-options", head.SHA+"^{commit}")
	if err != nil {
		return "", "", fmt.Errorf("read original PR head %s: %w", head.SHA, err)
	}
	if strings.TrimSpace(string(resolved)) != head.SHA {
		return "", "", fmt.Errorf("original PR head %q must be a full commit SHA", head.SHA)
	}
	common, err := git("merge-base", "--all", parent, head.SHA)
	if err != nil {
		return "", "", err
	}
	bases := strings.Fields(string(common))
	if len(bases) != 1 {
		return "", "", fmt.Errorf("original PR head %s has no unique merge base with %s", head.SHA, parent)
	}
	base := bases[0]
	count, err := git("rev-list", "--count", base+".."+head.SHA)
	if err != nil {
		return "", "", err
	}
	if strings.TrimSpace(string(count)) != strconv.Itoa(head.CommitCount) {
		return "", "", fmt.Errorf("local history for PR head %s differs from GitHub's commit count %d", head.SHA, head.CommitCount)
	}
	match, err := sameChange(git, base, head.SHA, parent, tip)
	if err != nil {
		return "", "", err
	}
	if match {
		return parent, fmt.Sprintf("Single landed commit verified against PR head %s.", head.SHA), nil
	}
	if head.CommitCount > 1 {
		candidate, err := linearBase(git, tip, head.CommitCount)
		if err != nil {
			return "", "", err
		}
		if candidate != "" {
			containsBase, err := gitMatches(git, "merge-base", "--is-ancestor", base, candidate)
			if err != nil {
				return "", "", err
			}
			if containsBase {
				match, err := sameChange(git, base, head.SHA, candidate, tip)
				if err != nil {
					return "", "", err
				}
				if match {
					return candidate, fmt.Sprintf("%d landed commits verified against PR head %s.", head.CommitCount, head.SHA), nil
				}
			}
		}
	}
	return "", "", fmt.Errorf("landed changes at %s could not be verified against PR head %s", tip, head.SHA)
}

func linearBase(git func(...string) ([]byte, error), tip string, count int) (string, error) {
	out, err := git("rev-list", "--first-parent", "--parents", "--max-count="+strconv.Itoa(count), tip)
	if err != nil {
		return "", err
	}
	commits := strings.Split(strings.TrimSpace(string(out)), "\n")
	if len(commits) != count {
		return "", nil
	}
	var base string
	for _, line := range commits {
		commit := strings.Fields(line)
		if len(commit) != 2 {
			return "", nil
		}
		base = commit[1]
	}
	return base, nil
}

func sameChange(git func(...string) ([]byte, error), sourceBase, sourceHead, landedBase, landedTip string) (bool, error) {
	project := func(from, head, onto, expected string) (bool, error) {
		actual, err := git("merge-tree", "--write-tree", "--no-messages", "--merge-base="+from, onto, head)
		if mergeConflict(err) {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		tree, err := git("rev-parse", "--verify", expected+"^{tree}")
		return err == nil && strings.TrimSpace(string(actual)) == strings.TrimSpace(string(tree)), err
	}
	// The reverse projection detects PR changes already present in a candidate parent.
	match, err := project(landedBase, landedTip, sourceBase, sourceHead)
	if err != nil || !match {
		return match, err
	}
	return project(sourceBase, sourceHead, landedBase, landedTip)
}

func gitMatches(git func(...string) ([]byte, error), args ...string) (bool, error) {
	_, err := git(args...)
	if mergeConflict(err) {
		return false, nil
	}
	return err == nil, err
}

func mergeConflict(err error) bool {
	var status *exec.ExitError
	return errors.As(err, &status) && status.ExitCode() == 1
}
