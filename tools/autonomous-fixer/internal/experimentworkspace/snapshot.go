package experimentworkspace

import (
	"archive/tar"
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"
)

type Snapshot struct{ Directory, Branch, SourceSHA, BaseSHA, TreeSHA string }

func git(ctx context.Context, dir string, args ...string) (string, error) {
	c := exec.CommandContext(ctx, "/usr/bin/git", append([]string{"-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"}, args...)...)
	c.Dir = dir
	c.Env = gitEnvironment()
	out, err := c.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("git %s: %w: %s", args[0], err, out)
	}
	return strings.TrimSpace(string(out)), nil
}

func Create(ctx context.Context, source, revision, destination, branch string) (s Snapshot, err error) {
	destination, err = filepath.Abs(destination)
	if err != nil {
		return s, err
	}
	if _, err = os.Lstat(destination); !os.IsNotExist(err) {
		return s, fmt.Errorf("destination must not exist: %s", destination)
	}
	if _, err = git(ctx, source, "check-ref-format", "--branch", branch); err != nil {
		return s, err
	}
	sha, err := git(ctx, source, "rev-parse", "--verify", revision+"^{commit}")
	if err != nil {
		return s, err
	}
	tracked, err := git(ctx, source, "ls-tree", "-r", "--name-only", "-z", sha)
	if err != nil {
		return s, err
	}
	if err = os.Mkdir(destination, 0700); err != nil {
		return s, err
	}
	defer func() {
		if err != nil {
			os.RemoveAll(destination)
		}
	}()
	c := exec.CommandContext(ctx, "/usr/bin/git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "archive", "--format=tar", sha)
	c.Dir = source
	c.Env = gitEnvironment()
	var stderr bytes.Buffer
	c.Stderr = &stderr
	pipe, e := c.StdoutPipe()
	if e != nil {
		return s, e
	}
	if err = c.Start(); err != nil {
		return s, err
	}
	extractErr := extract(pipe, destination)
	if extractErr != nil {
		c.Process.Kill()
	}
	waitErr := c.Wait()
	if extractErr != nil {
		return s, extractErr
	}
	if waitErr != nil {
		return s, fmt.Errorf("git archive: %w: %s", waitErr, stderr.String())
	}
	for _, name := range strings.Split(tracked, "\x00") {
		if name == "" {
			continue
		}
		if _, e = os.Lstat(filepath.Join(destination, name)); e != nil {
			return s, fmt.Errorf("archive omitted tracked file %q (check export-ignore and submodules): %w", name, e)
		}
	}
	if _, err = git(ctx, destination, "init", "--initial-branch="+branch); err != nil {
		return s, err
	}
	if _, err = git(ctx, destination, "add", "--force", "--all"); err != nil {
		return s, err
	}
	if _, err = git(ctx, destination, "-c", "user.name=Experiment snapshot", "-c", "user.email=experiment@localhost", "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "--allow-empty", "-m", "chore: create experiment snapshot"); err != nil {
		return s, err
	}
	base, err := git(ctx, destination, "rev-parse", "HEAD")
	if err != nil {
		return s, err
	}
	tree, err := git(ctx, destination, "rev-parse", "HEAD^{tree}")
	if err != nil {
		return s, err
	}
	sourceTree, err := git(ctx, source, "rev-parse", sha+"^{tree}")
	if err != nil {
		return s, err
	}
	if tree != sourceTree {
		return s, fmt.Errorf("snapshot tree %s differs from source tree %s; check archive attributes and submodules", tree, sourceTree)
	}
	return Snapshot{destination, branch, sha, base, tree}, nil
}

func extract(r io.Reader, destination string) error {
	tr := tar.NewReader(r)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		name := path.Clean(h.Name)
		if name == "." && h.Typeflag == tar.TypeDir {
			continue
		}
		if name == "." || path.IsAbs(name) || name == ".." || strings.HasPrefix(name, "../") || name == ".git" || strings.HasPrefix(name, ".git/") {
			return fmt.Errorf("unsafe archive path %q", h.Name)
		}
		target := filepath.Join(destination, filepath.FromSlash(name))
		parent := filepath.Dir(target)
		rel, _ := filepath.Rel(destination, parent)
		cur := destination
		for _, part := range strings.Split(rel, string(filepath.Separator)) {
			if part == "." {
				continue
			}
			cur = filepath.Join(cur, part)
			info, e := os.Lstat(cur)
			if os.IsNotExist(e) {
				if e = os.Mkdir(cur, 0755); e != nil {
					return e
				}
				continue
			}
			if e != nil {
				return e
			}
			if !info.IsDir() {
				return fmt.Errorf("archive parent is not a directory: %s", cur)
			}
		}
		switch h.Typeflag {
		case tar.TypeDir:
			if err = os.MkdirAll(target, os.FileMode(h.Mode)&0777); err != nil {
				return err
			}
		case tar.TypeReg:
			f, e := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, os.FileMode(h.Mode)&0777)
			if e != nil {
				return e
			}
			_, e = io.Copy(f, tr)
			closeErr := f.Close()
			if e != nil {
				return e
			}
			if closeErr != nil {
				return closeErr
			}
		case tar.TypeSymlink:
			link := path.Clean(path.Join(path.Dir(name), h.Linkname))
			if path.IsAbs(h.Linkname) || link == ".." || strings.HasPrefix(link, "../") {
				return fmt.Errorf("unsafe symlink %q", h.Name)
			}
			if err = os.Symlink(h.Linkname, target); err != nil {
				return err
			}
		case tar.TypeXGlobalHeader:
		default:
			return fmt.Errorf("unsupported archive entry %q: %d", h.Name, h.Typeflag)
		}
	}
}

func gitEnvironment() []string {
	return []string{"PATH=/usr/bin:/bin", "LANG=C.UTF-8", "HOME=/nonexistent", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"}
}
