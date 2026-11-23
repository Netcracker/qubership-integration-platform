package experiment

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"syscall"
	"time"
)

type RetentionReport struct {
	CreatedAt time.Time         `json:"created_at"`
	Applied   bool              `json:"applied"`
	Jobs      []RetentionJob    `json:"jobs"`
	Skipped   map[string]string `json:"skipped"`
	Directory string            `json:"directory,omitempty"`
}

type RetentionJob struct {
	Job            string   `json:"job"`
	Paths          []string `json:"paths"`
	AllocatedBytes int64    `json:"allocated_bytes"`
	ProtectedFiles int      `json:"protected_files"`
	Verified       bool     `json:"protected_files_unchanged"`
}

// CleanupCompleted removes disposable dependency copies from fully assessed cycles.
// Source, build outputs, logs, evidence, and interrupted attempts remain available.
func CleanupCompleted(ctx context.Context, root, exclude string, apply bool) (RetentionReport, error) {
	report := RetentionReport{CreatedAt: time.Now().UTC(), Applied: apply, Skipped: map[string]string{}}
	if _, err := Load(root); err != nil {
		return report, err
	}
	lock, err := os.OpenFile(filepath.Join(root, ".retention.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return report, err
	}
	defer lock.Close()
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return report, fmt.Errorf("retention already running: %w", err)
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	entries, err := os.ReadDir(filepath.Join(root, "runs"))
	if err != nil {
		return report, err
	}
	for _, entry := range entries {
		if err = ctx.Err(); err != nil {
			return report, err
		}
		id := entry.Name()
		job := filepath.Join(root, "runs", id)
		if !entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
			continue
		}
		if id == exclude {
			report.Skipped[id] = "current job"
			continue
		}
		if reason := retentionComplete(job); reason != "" {
			report.Skipped[id] = reason
			continue
		}
		if used := retentionInUse(job); used != "" {
			report.Skipped[id] = used
			continue
		}
		paths, bytes, err := retentionPaths(ctx, job)
		if err != nil {
			return report, err
		}
		if len(paths) == 0 {
			continue
		}
		row := RetentionJob{Job: id, Paths: paths, AllocatedBytes: bytes}
		if apply {
			if report.Directory == "" {
				parent := filepath.Join(root, "retention")
				if err = os.MkdirAll(parent, 0700); err != nil {
					return report, err
				}
				report.Directory, err = os.MkdirTemp(parent, "cleanup-")
				if err != nil {
					return report, err
				}
			}
			before, err := retentionHashes(ctx, job, paths)
			if err != nil {
				return report, err
			}
			row.ProtectedFiles = len(before)
			if err = WriteJSON(filepath.Join(report.Directory, id+"-before.json"), before); err != nil {
				return report, err
			}
			// Persist the exact removal list before the first filesystem change.
			if err = WriteJSON(filepath.Join(report.Directory, id+"-plan.json"), row); err != nil {
				return report, err
			}
			if used := retentionInUse(job); used != "" {
				return report, fmt.Errorf("job became active: %s: %s", id, used)
			}
			if reason := retentionComplete(job); reason != "" {
				return report, fmt.Errorf("job no longer complete: %s", id)
			}
			for _, rel := range paths {
				if err = ctx.Err(); err != nil {
					return report, err
				}
				path := filepath.Join(job, rel)
				if err = retentionRealPath(path); err != nil {
					return report, err
				}
				if err = os.RemoveAll(path); err != nil {
					return report, err
				}
			}
			after, err := retentionHashes(ctx, job, paths)
			if err != nil {
				return report, err
			}
			if err = WriteJSON(filepath.Join(report.Directory, id+"-after.json"), after); err != nil {
				return report, err
			}
			if !reflect.DeepEqual(before, after) {
				return report, fmt.Errorf("protected files changed during retention: %s; see %s", id, report.Directory)
			}
			row.Verified = true
		}
		report.Jobs = append(report.Jobs, row)
		if report.Directory != "" {
			if err = WriteJSON(filepath.Join(report.Directory, id+"-result.json"), row); err != nil {
				return report, err
			}
		}
	}
	if report.Directory != "" {
		if err = WriteJSON(filepath.Join(report.Directory, "report.json"), report); err != nil {
			return report, err
		}
	}
	return report, nil
}

func retentionComplete(job string) string {
	var a struct {
		Reviews []struct {
			Role string `json:"role"`
		} `json:"independent_reviews"`
	}
	data, err := os.ReadFile(filepath.Join(job, "assessment.json"))
	if err != nil || json.Unmarshal(data, &a) != nil {
		return "no final assessment"
	}
	roles := map[string]bool{}
	for _, r := range a.Reviews {
		roles[r.Role] = true
	}
	if len(a.Reviews) != 3 || !roles["r1"] || !roles["r2"] || !roles["r3"] {
		return "reviews not fully assessed"
	}
	for _, role := range []string{"implement", "r1", "r2", "r3"} {
		var result Execution
		if ReadJSON(filepath.Join(job, role, "execution", "result.json"), &result) != nil || result.Process.FinishedAt.IsZero() || result.Process.ExitCode != 0 {
			return "role incomplete or interrupted: " + role
		}
	}
	return ""
}

func retentionRealPath(path string) error {
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return err
	}
	if real != filepath.Clean(path) {
		return fmt.Errorf("retention refuses a symlink path: %s", path)
	}
	return nil
}

func retentionInUse(job string) string {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return "cannot inspect active processes"
	}
	for _, p := range entries {
		if p.Name() == fmt.Sprint(os.Getpid()) {
			continue
		}
		cwd, _ := os.Readlink(filepath.Join("/proc", p.Name(), "cwd"))
		if cwd == job || strings.HasPrefix(cwd, job+"/") {
			return "active process cwd: " + p.Name()
		}
		fds, _ := os.ReadDir(filepath.Join("/proc", p.Name(), "fd"))
		for _, fd := range fds {
			path, _ := os.Readlink(filepath.Join("/proc", p.Name(), "fd", fd.Name()))
			if strings.HasPrefix(path, job+"/") {
				return "open file in job: " + p.Name()
			}
		}
	}
	return ""
}

func retentionPaths(ctx context.Context, job string) ([]string, int64, error) {
	var paths []string
	var total int64
	for _, role := range []string{"implement", "r1", "r2", "r3"} {
		workspace := filepath.Join(job, role, "workspace")
		tracked, err := exec.CommandContext(ctx, "git", "-C", workspace, "ls-files", "-z").Output()
		if err != nil {
			return nil, 0, fmt.Errorf("inspect retained source: %s: %w", workspace, err)
		}
		candidates := []string{"execution/home/.m2/repository", "workspace/node_modules", "workspace/e2e/node_modules", "workspace/ui/node_modules", "workspace/schemas/node_modules", "workspace/vscode-extension/node_modules"}
		for _, suffix := range candidates {
			rel := filepath.Join(role, suffix)
			path := filepath.Join(job, rel)
			if _, err = os.Lstat(path); os.IsNotExist(err) {
				continue
			} else if err != nil {
				return nil, 0, err
			}
			if err = retentionRealPath(path); err != nil {
				return nil, 0, err
			}
			protected := false
			trackedSource := false
			prefix := strings.TrimPrefix(suffix, "workspace/")
			if strings.HasPrefix(suffix, "workspace/") {
				for _, name := range strings.Split(string(tracked), "\x00") {
					if name == prefix || strings.HasPrefix(name, prefix+"/") {
						protected = true
						trackedSource = true
					}
				}
			}
			var bytes int64
			keepChild := map[string]bool{}
			childBytes := map[string]int64{}
			err = filepath.WalkDir(path, func(p string, d fs.DirEntry, e error) error {
				if e != nil {
					return e
				}
				if e = ctx.Err(); e != nil {
					return e
				}
				name := strings.ToLower(d.Name())
				relative, e := filepath.Rel(path, p)
				if e != nil {
					return e
				}
				child := strings.SplitN(relative, string(filepath.Separator), 2)[0]
				// Retain the package containing a log, even inside a dependency copy.
				if name == "evidence" || name == "test-results" || name == "playwright-report" || name == "surefire-reports" || name == "failsafe-reports" || strings.HasSuffix(name, ".log") || strings.HasSuffix(name, ".jsonl") || strings.HasSuffix(name, ".har") || strings.HasSuffix(name, ".webm") {
					protected = true
					keepChild[child] = true
				}
				info, e := d.Info()
				if e != nil {
					return e
				}
				if stat, ok := info.Sys().(*syscall.Stat_t); ok {
					bytes += stat.Blocks * 512
					childBytes[child] += stat.Blocks * 512
				}
				return nil
			})
			if err != nil {
				return nil, 0, err
			}
			if !protected {
				paths = append(paths, rel)
				total += bytes
			} else if !trackedSource && filepath.Base(path) == "node_modules" {
				children, err := os.ReadDir(path)
				if err != nil {
					return nil, 0, err
				}
				for _, child := range children {
					if child.IsDir() && child.Type()&os.ModeSymlink == 0 && !keepChild[child.Name()] {
						paths = append(paths, filepath.Join(rel, child.Name()))
						total += childBytes[child.Name()]
					}
				}
			}
		}
	}
	return paths, total, nil
}

func retentionHashes(ctx context.Context, root string, exclude []string) (map[string]string, error) {
	hashes := map[string]string{}
	excluded := map[string]bool{}
	for _, path := range exclude {
		excluded[path] = true
	}
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if err = ctx.Err(); err != nil {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		if excluded[rel] {
			return filepath.SkipDir
		}
		if d.IsDir() {
			return nil
		}
		if d.Type()&os.ModeSymlink != 0 {
			target, err := os.Readlink(path)
			if err != nil {
				return err
			}
			hashes[rel] = "symlink:" + target
			return nil
		}
		if !d.Type().IsRegular() {
			return fmt.Errorf("unexpected retained file type: %s", path)
		}
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		h := sha256.New()
		_, err = io.Copy(h, file)
		closeErr := file.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
		hashes[rel] = hex.EncodeToString(h.Sum(nil))
		return nil
	})
	return hashes, err
}

func cleanupBeforeExecution(ctx context.Context, root, current string) error {
	path := filepath.Join(root, "retention-policy.json")
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var policy struct {
		Enabled bool `json:"enabled"`
	}
	if err = json.Unmarshal(data, &policy); err != nil {
		return err
	}
	if !policy.Enabled {
		return nil
	}
	_, err = CleanupCompleted(ctx, root, current, true)
	return err
}
