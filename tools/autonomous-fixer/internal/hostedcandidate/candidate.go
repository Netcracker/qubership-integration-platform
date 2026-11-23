// Package hostedcandidate carries regular-file changes from an isolated job to a trusted publisher.
package hostedcandidate

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type File struct {
	Path    string `json:"path"`
	Mode    string `json:"mode"`
	Content []byte `json:"content,omitempty"`
}
type Candidate struct {
	Version int    `json:"version"`
	BaseSHA string `json:"baseSha"`
	Files   []File `json:"files"`
}

var sha = regexp.MustCompile("^[0-9a-f]{40}$")

func git(ctx context.Context, dir, index string, input []byte, args ...string) ([]byte, error) {
	return gitWithEnv(ctx, dir, index, input, nil, args...)
}

func gitWithEnv(ctx context.Context, dir, index string, input []byte, environment []string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
	cmd.Dir = dir
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0", "GIT_NO_REPLACE_OBJECTS=1", "GIT_LITERAL_PATHSPECS=1"}
	cmd.Env = append(cmd.Env, environment...)
	if index != "" {
		cmd.Env = append(cmd.Env, "GIT_INDEX_FILE="+index)
	}
	cmd.Stdin = bytes.NewReader(input)
	data, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("git %s: %w", args[0], err)
	}
	return data, nil
}

func Pack(ctx context.Context, workspace, base string) (Candidate, error) {
	c := Candidate{Version: 1, BaseSHA: base}
	if !sha.MatchString(base) {
		return c, fmt.Errorf("invalid base commit")
	}
	paths := map[string]bool{}
	for _, args := range [][]string{{"diff", "--name-only", "--no-renames", "--no-ext-diff", "--no-textconv", "-z", base, "--"}, {"ls-files", "--others", "--exclude-standard", "-z"}} {
		data, err := git(ctx, workspace, "", nil, args...)
		if err != nil {
			return c, err
		}
		for _, p := range strings.Split(string(data), "\x00") {
			if p != "" {
				paths[p] = true
			}
		}
	}
	root, err := os.OpenRoot(workspace)
	if err != nil {
		return c, err
	}
	defer root.Close()
	var bytesToEncode int64
	for path := range paths {
		f := File{Path: path}
		info, err := root.Lstat(path)
		switch {
		case os.IsNotExist(err):
			f.Mode = "delete"
		case err != nil:
			return c, err
		case !info.Mode().IsRegular():
			return c, fmt.Errorf("candidate entry %q is not a regular file", path)
		default:
			bytesToEncode += 4 * ((info.Size() + 2) / 3)
			if bytesToEncode > fixer.MaxHostedCandidateBytes {
				return c, fmt.Errorf("candidate exceeds the hosted artifact limit")
			}
			f.Mode = "100644"
			if info.Mode()&0111 != 0 {
				f.Mode = "100755"
			}
			f.Content, err = root.ReadFile(path)
			if err != nil {
				return c, err
			}
		}
		c.Files = append(c.Files, f)
	}
	sort.Slice(c.Files, func(i, j int) bool { return c.Files[i].Path < c.Files[j].Path })
	if len(c.Files) == 0 {
		return c, fmt.Errorf("the candidate has no changes")
	}
	return c, nil
}

func Read(path, digest string) (Candidate, error) {
	var c Candidate
	data, err := fixer.ReadHostedFile(path, fixer.MaxHostedCandidateBytes)
	if err != nil {
		return c, err
	}
	h := sha256.Sum256(data)
	if hex.EncodeToString(h[:]) != digest {
		return c, fmt.Errorf("candidate digest mismatch")
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err = dec.Decode(&c); err != nil {
		return c, err
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return c, fmt.Errorf("unexpected data after candidate")
	}
	return c, nil
}

func Validate(c Candidate, base string, scope []string) error {
	if c.Version != 1 || !sha.MatchString(base) || base != c.BaseSHA || len(c.Files) == 0 {
		return fmt.Errorf("invalid candidate or base")
	}
	seen := map[string]bool{}
	for _, f := range c.Files {
		if len(fixer.CheckPaths([]string{f.Path}, scope, false)) != 0 || seen[f.Path] {
			return fmt.Errorf("disallowed or duplicate path %q", f.Path)
		}
		for _, part := range strings.Split(f.Path, "/") {
			if strings.EqualFold(part, ".git") {
				return fmt.Errorf("invalid Git path %q", f.Path)
			}
		}
		if f.Mode != "100644" && f.Mode != "100755" && !(f.Mode == "delete" && len(f.Content) == 0) {
			return fmt.Errorf("invalid file mode for %q", f.Path)
		}
		seen[f.Path] = true
	}
	return nil
}

// candidateTree derives the tree in a temporary index without checking out the candidate.
func candidateTree(ctx context.Context, workspace string, c Candidate) (string, error) {
	dir, err := os.MkdirTemp("", "qip-publish-index-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(dir)
	idx := filepath.Join(dir, "index")
	if _, err = git(ctx, workspace, idx, nil, "read-tree", c.BaseSHA); err != nil {
		return "", err
	}
	var entries bytes.Buffer
	for _, f := range c.Files {
		mode, blob := f.Mode, strings.Repeat("0", 40)
		if f.Mode == "delete" {
			mode = "0"
		} else {
			data, err := git(ctx, workspace, idx, f.Content, "hash-object", "-w", "--stdin")
			if err != nil {
				return "", err
			}
			blob = strings.TrimSpace(string(data))
		}
		fmt.Fprintf(&entries, "%s %s\t%s%c", mode, blob, f.Path, 0)
	}
	if _, err = git(ctx, workspace, idx, entries.Bytes(), "update-index", "-z", "--index-info"); err != nil {
		return "", err
	}
	output, err := git(ctx, workspace, idx, nil, "write-tree")
	if err != nil {
		return "", err
	}
	tree := strings.TrimSpace(string(output))
	diff, err := git(ctx, workspace, idx, nil, "diff-tree", "--raw", "--no-abbrev", "-r", "--no-renames", "-z", c.BaseSHA, tree)
	if err != nil {
		return "", err
	}
	parts := bytes.Split(bytes.TrimSuffix(diff, []byte{0}), []byte{0})
	if len(parts) != len(c.Files)*2 {
		return "", fmt.Errorf("actual changed paths differ from the candidate")
	}
	modes := map[string]string{}
	for _, f := range c.Files {
		modes[f.Path] = f.Mode
	}
	for i := 0; i < len(parts); i += 2 {
		fields := strings.Fields(string(parts[i]))
		path := string(parts[i+1])
		want, ok := modes[path]
		if want == "delete" {
			want = "000000"
		}
		if !ok || len(fields) != 5 || fields[1] != want || (fields[0] != ":000000" && fields[0] != ":100644" && fields[0] != ":100755") {
			return "", fmt.Errorf("unexpected tree change at %q", path)
		}
	}
	return tree, nil
}
