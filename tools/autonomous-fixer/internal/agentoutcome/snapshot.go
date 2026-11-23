package agentoutcome

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"slices"
	"strings"
)

var commitSHA = regexp.MustCompile(`^[a-f0-9]{40}$`)

type Snapshot struct {
	HeadSHA           string   `json:"head_sha,omitempty"`
	BaseRef           string   `json:"base_ref,omitempty"`
	BaseSHA           string   `json:"base_sha,omitempty"`
	ComparisonBaseSHA string   `json:"comparison_base_sha,omitempty"`
	TreeSHA           string   `json:"tree_sha,omitempty"`
	PatchSHA256       string   `json:"patch_sha256,omitempty"`
	Paths             []string `json:"paths"`
	Diagnostic        string   `json:"diagnostic,omitempty"`

	patch []byte
}

type gitRepository struct {
	workspace, token string
}

func (g gitRepository) run(ctx context.Context, args ...string) ([]byte, error) {
	command := exec.CommandContext(ctx, "git", args...)
	command.Dir = g.workspace
	command.Env = append(os.Environ(), "GIT_NO_REPLACE_OBJECTS=1", "GIT_TERMINAL_PROMPT=0")
	if g.token != "" && args[0] == "fetch" {
		command.Env = append(command.Env, "GIT_CONFIG_COUNT=1",
			"GIT_CONFIG_KEY_0=http.https://github.com/.extraheader",
			"GIT_CONFIG_VALUE_0=AUTHORIZATION: basic "+base64.StdEncoding.EncodeToString([]byte("x-access-token:"+g.token)))
	}
	return command.Output()
}

func (g gitRepository) hasCommit(ctx context.Context, sha string) bool {
	if !commitSHA.MatchString(sha) {
		return false
	}
	kind, _ := g.run(ctx, "cat-file", "-t", sha)
	if string(kind) == "commit\n" {
		return true
	}
	if _, err := g.run(ctx, "fetch", "--no-tags", "--no-recurse-submodules", "origin", sha); err != nil {
		return false
	}
	kind, _ = g.run(ctx, "cat-file", "-t", sha)
	return string(kind) == "commit\n"
}

func (g gitRepository) beforeMerge(ctx context.Context, head, base, merge string) (string, bool) {
	if merge == "" {
		return base, true
	}
	if !g.hasCommit(ctx, merge) {
		return "", false
	}
	data, err := g.run(ctx, "rev-list", "--parents", "-n", "1", merge)
	if err != nil {
		return "", false
	}
	parents := strings.Fields(string(data))
	if len(parents) == 3 && parents[2] == head {
		if _, err := g.run(ctx, "merge-base", "--is-ancestor", merge, base); err == nil {
			return parents[1], true
		}
	}
	return base, true
}

func (g gitRepository) isTestMerge(ctx context.Context, merge, base, head string) bool {
	if !g.hasCommit(ctx, merge) {
		return false
	}
	data, err := g.run(ctx, "rev-list", "--parents", "-n", "1", merge)
	parents := strings.Fields(string(data))
	return err == nil && len(parents) == 3 && parents[1] == base && parents[2] == head
}

var diffOptions = []string{
	"diff", "--no-renames", "--no-relative", "--no-color", "--no-ext-diff", "--no-textconv",
	"--ignore-submodules=none", "--submodule=short", "-O", "/dev/null",
}

func (g gitRepository) capture(ctx context.Context, snapshot *Snapshot, mergeSHA string) {
	fail := func(reason string) { snapshot.Diagnostic = reason }
	if snapshot.HeadSHA == "" || snapshot.BaseSHA == "" {
		fail("snapshot coordinates are missing")
		return
	}
	if !g.hasCommit(ctx, snapshot.HeadSHA) || !g.hasCommit(ctx, snapshot.BaseSHA) {
		fail("snapshot commit is unavailable")
		return
	}
	base, ok := g.beforeMerge(ctx, snapshot.HeadSHA, snapshot.BaseSHA, mergeSHA)
	if !ok {
		fail("merge commit is unavailable")
		return
	}
	common, err := g.run(ctx, "merge-base", "--all", base, snapshot.HeadSHA)
	comparisonBase := strings.TrimSpace(string(common))
	if err != nil || !commitSHA.MatchString(comparisonBase) {
		fail("snapshot has no unique comparison base")
		return
	}
	snapshot.ComparisonBaseSHA = comparisonBase
	tree, err := g.run(ctx, "rev-parse", snapshot.HeadSHA+"^{tree}")
	if err != nil || !commitSHA.MatchString(strings.TrimSpace(string(tree))) {
		fail("snapshot tree is unavailable")
		return
	}
	snapshot.TreeSHA = strings.TrimSpace(string(tree))

	diffArgs := append(slices.Clone(diffOptions), "--src-prefix=a/", "--dst-prefix=b/", "--full-index",
		"--unified=0", "--diff-algorithm=myers", "--no-indent-heuristic", comparisonBase, snapshot.HeadSHA, "--")
	diff, err := g.run(ctx, diffArgs...)
	if err != nil {
		fail("snapshot patch is unavailable")
		return
	}
	pathArgs := append(slices.Clone(diffOptions), "--name-only", "-z", comparisonBase, snapshot.HeadSHA, "--")
	paths, err := g.run(ctx, pathArgs...)
	if err != nil {
		fail("snapshot file list is unavailable")
		return
	}
	snapshot.Paths = []string{}
	if len(paths) != 0 {
		snapshot.Paths = strings.Split(strings.TrimSuffix(string(paths), "\x00"), "\x00")
		slices.Sort(snapshot.Paths)
	}
	snapshot.patch = normalizePatch(diff)
	snapshot.PatchSHA256 = fmt.Sprintf("%x", sha256.Sum256(snapshot.patch))
}

func normalizePatch(diff []byte) []byte {
	var normalized bytes.Buffer
	var index []byte
	for _, line := range bytes.SplitAfter(diff, []byte("\n")) {
		switch {
		case bytes.HasPrefix(line, []byte("diff --git ")):
			index = nil
			normalized.Write(line)
		case bytes.HasPrefix(line, []byte("index ")):
			index = line
		case bytes.HasPrefix(line, []byte("Binary files ")):
			normalized.Write(index)
			normalized.Write(line)
		case bytes.HasPrefix(line, []byte("@@ ")):
			normalized.WriteString("@@\n")
		default:
			normalized.Write(line)
		}
	}
	return normalized.Bytes()
}

func (g gitRepository) contentChange(ctx context.Context, published, final Snapshot) string {
	if published.PatchSHA256 == "" || final.PatchSHA256 == "" {
		return "unknown"
	}
	if published.ComparisonBaseSHA == final.ComparisonBaseSHA {
		if published.TreeSHA == final.TreeSHA {
			return "unchanged"
		}
		return "changed"
	}
	// Line offsets alone cannot identify repeated edits after the base changes.
	projected, err := g.run(ctx, "merge-tree", "--write-tree", "--no-messages",
		"--merge-base="+published.ComparisonBaseSHA, final.ComparisonBaseSHA, published.HeadSHA)
	if tree := strings.TrimSpace(string(projected)); err == nil && commitSHA.MatchString(tree) {
		if tree == final.TreeSHA {
			return "unchanged"
		}
		return "changed"
	}
	if published.PatchSHA256 != final.PatchSHA256 {
		return "changed"
	}
	return "unknown"
}
