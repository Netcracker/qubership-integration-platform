package localwork

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

const reviewPermissionProfile = "qip_code_review"

type readOnlyReviewWorkspace struct {
	root          string
	workspace     string
	taskDirectory string
	permissions   []string
}

func validateReviewParallelism(cfg Config) error {
	if cfg.ReviewParallelism == 0 {
		return nil
	}
	if cfg.ReviewParallelism < 1 || cfg.ReviewParallelism > 4 {
		return fmt.Errorf("review_parallelism must be between 1 and 4")
	}
	if cfg.Workflow != "agent" || cfg.TaskKind == "documentation" || cfg.Profile.Runtime != "codex" || runtime.GOOS != "linux" {
		return fmt.Errorf("review_parallelism requires the agent workflow and Codex on Linux")
	}
	return nil
}

func (v *readOnlyReviewWorkspace) cleanup() error {
	return os.RemoveAll(v.root)
}

func (d *Driver) prepareReadOnlyReview(ctx context.Context, candidate workcycle.Snapshot) (view *readOnlyReviewWorkspace, err error) {
	root, err := os.MkdirTemp(d.Config.OutputDirectory, "review-source-")
	if err != nil {
		return nil, err
	}
	defer func() {
		if err != nil {
			_ = os.RemoveAll(root)
		}
	}()
	view = &readOnlyReviewWorkspace{root: root, workspace: filepath.Join(root, "source"), taskDirectory: filepath.Join(root, "task")}
	for _, dir := range []string{view.workspace, view.taskDirectory} {
		if err = os.Mkdir(dir, 0700); err != nil {
			return nil, err
		}
	}
	if err = d.copyReviewSource(ctx, view.workspace, candidate); err != nil {
		return nil, err
	}
	if err = d.copyReviewAttachments(view.taskDirectory); err != nil {
		return nil, err
	}
	binary, err := filepath.EvalSymlinks(d.Config.RuntimeBinary)
	if err != nil {
		return nil, err
	}
	view.permissions = []string{
		"-c", "default_permissions=" + strconv.Quote(reviewPermissionProfile),
		"-c", "permissions." + reviewPermissionProfile + ".filesystem={\":minimal\"=\"read\", " +
			strconv.Quote(view.workspace) + "=\"read\", " + strconv.Quote(view.taskDirectory) + "=\"read\", " +
			strconv.Quote(binary) + "=\"read\"}",
		"-c", "permissions." + reviewPermissionProfile + ".network.enabled=false",
		"-c", "web_search=\"disabled\"",
		"-c", "allow_login_shell=false",
	}
	probeHome := filepath.Join(root, "probe-home")
	if err = os.MkdirAll(filepath.Join(probeHome, ".codex"), 0700); err != nil {
		return nil, err
	}
	args := []string{"sandbox", "-P", reviewPermissionProfile, "--cd", view.workspace}
	args = append(args, view.permissions...)
	args = append(args, "--", "/bin/sh", "-ec", `test -r .git/HEAD && test ! -w . && exec git rev-parse HEAD`)
	cmd := exec.CommandContext(ctx, d.Config.RuntimeBinary, args...)
	cmd.Dir = view.workspace
	cmd.Env = reviewEnvironment(probeHome)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	output, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("probe the read-only review sandbox: %w. Use a direct native Codex binary. %s", err, diagnosticExcerpt(stderr.String()))
	}
	if strings.TrimSpace(string(output)) != d.base {
		return nil, fmt.Errorf("the read-only review sandbox could not confirm the isolated Git checkout")
	}
	return view, nil
}

func (d *Driver) copyReviewSource(ctx context.Context, directory string, candidate workcycle.Snapshot) error {
	for _, args := range [][]string{
		{"init", "--quiet", "--template="},
		{"fetch", "--quiet", "--no-tags", "--depth=1", d.Config.Workspace, d.base},
		{"update-ref", "--no-deref", "HEAD", d.base},
		{"read-tree", d.base},
	} {
		if _, err := git(ctx, directory, args...); err != nil {
			return err
		}
	}
	paths, err := git(ctx, d.Config.Workspace, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
	if err != nil {
		return err
	}
	source, err := os.OpenRoot(d.Config.Workspace)
	if err != nil {
		return err
	}
	defer source.Close()
	target, err := os.OpenRoot(directory)
	if err != nil {
		return err
	}
	defer target.Close()
	seen := map[string]bool{}
	for _, path := range strings.Split(strings.TrimSuffix(string(paths), "\x00"), "\x00") {
		if path == "" || seen[path] {
			continue
		}
		seen[path] = true
		if err = copyReviewFile(source, target, path); err != nil {
			return err
		}
	}
	baseline, err := git(ctx, directory, "ls-files", "-z", "--cached")
	if err != nil {
		return err
	}
	args := []string{"update-index", "--force-remove", "--"}
	baselinePaths := map[string]bool{}
	for _, path := range strings.Split(strings.TrimSuffix(string(baseline), "\x00"), "\x00") {
		if path == "" {
			continue
		}
		baselinePaths[path] = true
		if !seen[path] {
			args = append(args, path)
		}
	}
	if len(args) > 3 {
		if _, err = git(ctx, directory, args...); err != nil {
			return err
		}
	}
	tracked, err := git(ctx, d.Config.Workspace, "ls-files", "-z", "--cached")
	if err != nil {
		return err
	}
	add := []string{"--literal-pathspecs", "add", "--force", "--intent-to-add", "--"}
	for _, path := range strings.Split(strings.TrimSuffix(string(tracked), "\x00"), "\x00") {
		if path == "" || baselinePaths[path] {
			continue
		}
		if _, err = target.Lstat(path); os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return err
		}
		add = append(add, path)
	}
	if len(add) > 5 {
		if _, err = git(ctx, directory, add...); err != nil {
			return err
		}
	}
	isolated := Driver{Config: Config{Workspace: directory}, base: d.base}
	snapshot, err := isolated.Snapshot(ctx)
	if err != nil {
		return err
	}
	if candidate.ID != snapshot.ID {
		return fmt.Errorf("the isolated source does not match the current candidate")
	}
	return nil
}

func copyReviewFile(source, target *os.Root, path string) error {
	info, err := source.Lstat(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if err = target.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		link, err := source.Readlink(path)
		if err != nil {
			return err
		}
		return target.Symlink(link, path)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("unsupported source entry %s", path)
	}
	from, err := source.Open(path)
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := target.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, info.Mode().Perm())
	if err != nil {
		return err
	}
	defer to.Close()
	if _, err = io.Copy(to, from); err != nil {
		return err
	}
	return to.Chmod(info.Mode())
}

func (d *Driver) copyReviewAttachments(directory string) error {
	for _, file := range d.Packet.Files {
		if file.Root != "task" {
			continue
		}
		data, err := workcontext.ReadBoundFile(d.Config.TaskDirectory, file)
		if err != nil {
			return err
		}
		path := filepath.Join(directory, file.Path)
		if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return err
		}
		if err = os.WriteFile(path, data, 0600); err != nil {
			return err
		}
	}
	return nil
}

func reviewEnvironment(home string) []string {
	return []string{
		"HOME=" + home, "CODEX_HOME=" + filepath.Join(home, ".codex"),
		"XDG_CONFIG_HOME=" + filepath.Join(home, "config"), "XDG_DATA_HOME=" + filepath.Join(home, "data"),
		"XDG_CACHE_HOME=" + filepath.Join(home, "cache"), "PATH=/usr/bin:/bin", "LANG=C.UTF-8",
		"GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_OPTIONAL_LOCKS=0",
	}
}

func (v *readOnlyReviewWorkspace) configure(command *experimentruntime.Command) {
	for i, arg := range command.Args {
		if arg == "--sandbox" {
			command.Args = append(command.Args[:i], command.Args[i+2:]...)
			break
		}
	}
	last := len(command.Args) - 1
	command.Args = append(command.Args[:last], v.permissions...)
	for _, feature := range []string{"apps", "browser_use", "in_app_browser", "browser_use_external", "computer_use"} {
		command.Args = append(command.Args, "--disable", feature)
	}
	command.Args = append(command.Args, "-")
}
