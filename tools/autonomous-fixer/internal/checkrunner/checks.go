package checkrunner

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

type Recipe struct {
	SourceModules     []string   `json:"source_modules,omitempty"`
	ID                string     `json:"id"`
	Scope             string     `json:"scope"`
	Command           []string   `json:"command"`
	CWD               string     `json:"cwd"`
	DependencyRoots   []string   `json:"dependency_roots,omitempty"`
	InputFiles        []string   `json:"input_files,omitempty"`
	ToolchainCommands [][]string `json:"toolchain_commands"`
}

type Record struct {
	CommandSeconds          float64           `json:"command_seconds"`
	FingerprintSeconds      float64           `json:"fingerprint_seconds"`
	ProcessUserCPUSeconds   float64           `json:"process_user_cpu_seconds"`
	ProcessSystemCPUSeconds float64           `json:"process_system_cpu_seconds"`
	ID                      string            `json:"id"`
	CandidateID             string            `json:"candidate_id"`
	InputSHA256             string            `json:"input_sha256"`
	Recipe                  Recipe            `json:"recipe"`
	StartedAt               time.Time         `json:"started_at"`
	ElapsedSeconds          float64           `json:"elapsed_seconds"`
	ExitCode                int               `json:"exit_code"`
	Stdout                  string            `json:"stdout"`
	Stderr                  string            `json:"stderr"`
	OutputSHA256            map[string]string `json:"output_sha256"`
	ReusedFrom              string            `json:"reused_from,omitempty"`
	Path                    string            `json:"-"`
}

func Validate(r Recipe) error {
	if !regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]*$`).MatchString(r.ID) || r.Scope == "" || len(r.Command) == 0 || len(r.ToolchainCommands) == 0 {
		return fmt.Errorf("check requires an ID, scope, command, and toolchain commands")
	}
	if filepath.IsAbs(r.CWD) || strings.HasPrefix(filepath.Clean(r.CWD), "..") {
		return fmt.Errorf("check cwd must be inside the workspace")
	}
	for _, module := range r.SourceModules {
		if module == "." || module == ".." || filepath.Base(module) != module || strings.ContainsAny(module, "/\\") {
			return fmt.Errorf("source_modules must name workspace modules")
		}
	}
	for _, cmd := range r.ToolchainCommands {
		if len(cmd) == 0 {
			return fmt.Errorf("empty toolchain command")
		}
	}
	return nil
}

func digest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func fileDigest(path string) (string, error) {
	f, e := os.Open(path)
	if e != nil {
		return "", e
	}
	defer f.Close()
	h := sha256.New()
	if _, e = io.Copy(h, f); e != nil {
		return "", e
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
func write(path string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return e
	}
	return os.WriteFile(path, append(b, '\n'), 0600)
}

func inputKey(ctx context.Context, workspace, candidate string, r Recipe, env []string, cache *InputCache) (string, error) {
	if err := Validate(r); err != nil {
		return "", err
	}
	h := sha256.New()
	encoded, _ := json.Marshal(r)
	h.Write(encoded)
	io.WriteString(h, candidate)
	sortedEnv := append([]string(nil), env...)
	sort.Strings(sortedEnv)
	encoded, _ = json.Marshal(sortedEnv)
	h.Write(encoded)
	for _, args := range r.ToolchainCommands {
		program, e := resolveProgram(args[0], filepath.Join(workspace, r.CWD), env)
		if e != nil {
			return "", e
		}
		c := exec.CommandContext(ctx, program, args[1:]...)
		c.Dir = filepath.Join(workspace, r.CWD)
		c.Env = env
		data, e := c.CombinedOutput()
		if e != nil {
			return "", fmt.Errorf("toolchain command %s failed: %w", args[0], e)
		}
		h.Write(data)
		bin, e := resolveProgram(args[0], filepath.Join(workspace, r.CWD), env)
		if e != nil {
			return "", e
		}
		sum, e := cache.digest(bin)
		if e != nil {
			return "", e
		}
		io.WriteString(h, sum)
	}
	program, e := resolveProgram(r.Command[0], filepath.Join(workspace, r.CWD), env)
	if e != nil {
		return "", e
	}
	programHash, e := cache.digest(program)
	if e != nil {
		return "", e
	}
	io.WriteString(h, program+"\x00"+programHash)
	paths := append(append([]string(nil), r.DependencyRoots...), r.InputFiles...)
	memo := map[string]string{}
	for _, path := range paths {
		if !filepath.IsAbs(path) {
			path = filepath.Join(workspace, path)
		}
		sum, e := treeDigest(ctx, path, workspace, r.SourceModules, memo, map[string]bool{}, cache, false)
		if e != nil {
			return "", e
		}
		io.WriteString(h, path+"\x00"+sum)
	}

	return hex.EncodeToString(h.Sum(nil)), nil
}

func Run(ctx context.Context, workspace, candidate, evidenceDir string, r Recipe, env []string) (Record, error) {
	return RunCached(ctx, workspace, candidate, evidenceDir, r, env, &InputCache{})
}

func RunCached(ctx context.Context, workspace, candidate, evidenceDir string, r Recipe, env []string, cache *InputCache) (Record, error) {
	rec := Record{ID: r.ID, CandidateID: candidate, Recipe: r, StartedAt: time.Now().UTC(), ExitCode: -1}
	fingerprintStart := time.Now()
	key, e := inputKey(ctx, workspace, candidate, r, env, cache)
	rec.FingerprintSeconds = time.Since(fingerprintStart).Seconds()
	if e != nil {
		return rec, e
	}
	rec.InputSHA256 = key
	dir, e := os.MkdirTemp(evidenceDir, "check-"+r.ID+"-")
	if e != nil {
		return rec, e
	}
	rec.Path = filepath.Join(dir, "result.json")
	matches, e := filepath.Glob(filepath.Join(evidenceDir, "check-"+r.ID+"-*", "result.json"))
	if e != nil {
		return rec, e
	}
	sort.Strings(matches)
	for _, file := range matches {
		data, readErr := os.ReadFile(file)
		if readErr != nil {
			return rec, readErr
		}
		var old Record
		if e = json.Unmarshal(data, &old); e != nil {
			return rec, e
		}
		if old.ReusedFrom != "" || old.InputSHA256 != key || old.ExitCode != 0 {
			continue
		}
		if len(old.OutputSHA256) != 2 {
			return rec, fmt.Errorf("cached check has incomplete output identities")
		}
		for path, want := range old.OutputSHA256 {
			got, e := fileDigest(path)
			if e != nil || got != want {
				return rec, fmt.Errorf("cached check output changed: %s", path)
			}
		}
		rec.ExitCode = 0
		rec.Stdout = old.Stdout
		rec.Stderr = old.Stderr
		rec.OutputSHA256 = old.OutputSHA256
		rec.ReusedFrom = file
		rec.ElapsedSeconds = time.Since(rec.StartedAt).Seconds()
		return rec, write(rec.Path, rec)
	}
	program, e := resolveProgram(r.Command[0], filepath.Join(workspace, r.CWD), env)
	if e != nil {
		return rec, e
	}
	process, runErr := experimentruntime.RunWithProgress(ctx, experimentruntime.Command{Program: program, Args: r.Command[1:], Workspace: filepath.Join(workspace, r.CWD)}, dir, env, nil, nil, experimentruntime.ProgressOptions{Runtime: "command", Stage: "check:" + r.ID, LatestPath: filepath.Join(filepath.Dir(evidenceDir), "runtime-progress.json")})
	rec.Stdout, rec.Stderr = process.StdoutPath, process.StderrPath
	rec.ExitCode = process.ExitCode
	rec.CommandSeconds = process.Duration.Seconds()
	rec.ProcessUserCPUSeconds = process.ProcessUserCPU.Seconds()
	rec.ProcessSystemCPUSeconds = process.ProcessSystemCPU.Seconds()
	rec.ElapsedSeconds = time.Since(rec.StartedAt).Seconds()
	rec.OutputSHA256 = map[string]string{}
	for _, path := range []string{rec.Stdout, rec.Stderr} {
		sum, e := fileDigest(path)
		if e != nil {
			return rec, e
		}
		rec.OutputSHA256[path] = sum
	}
	if e = write(rec.Path, rec); e != nil {
		return rec, e
	}
	if runErr != nil && (rec.ExitCode <= 0 || ctx.Err() != nil) {
		return rec, runErr
	}
	// Commands that modify their own inputs cannot produce reusable results.
	fingerprintStart = time.Now()
	after, e := inputKey(ctx, workspace, candidate, r, env, cache)
	rec.FingerprintSeconds += time.Since(fingerprintStart).Seconds()
	if e != nil {
		return rec, e
	}
	if after != key {
		rec.InputSHA256 = ""
	}
	rec.ElapsedSeconds = time.Since(rec.StartedAt).Seconds()
	if e = write(rec.Path, rec); e != nil {
		return rec, e
	}
	return rec, nil
}

func treeDigest(ctx context.Context, path, workspace string, sourceModules []string, memo map[string]string, active map[string]bool, cache *InputCache, fromLink bool) (string, error) {
	if e := ctx.Err(); e != nil {
		return "", e
	}
	resolved, e := filepath.EvalSymlinks(path)
	if e != nil {
		return "", e
	}
	if fromLink {
		for _, module := range sourceModules {
			if resolved == filepath.Join(workspace, module) {
				nested := filepath.Join(resolved, "node_modules")
				if _, e := os.Stat(nested); os.IsNotExist(e) {
					return "source-module:" + module, nil
				}
				sum, e := treeDigest(ctx, nested, workspace, sourceModules, memo, active, cache, false)
				return "source-module:" + module + ":" + sum, e
			}
		}
	}
	if value, ok := memo[resolved]; ok {
		return value, nil
	}
	if active[resolved] {
		return "cycle:" + resolved, nil
	}
	active[resolved] = true
	defer delete(active, resolved)
	info, e := os.Stat(resolved)
	if e != nil {
		return "", e
	}
	h := sha256.New()
	fmt.Fprintf(h, "%s\x00%d\x00", resolved, info.Mode())
	if info.IsDir() {
		entries, e := os.ReadDir(resolved)
		if e != nil {
			return "", e
		}
		for _, entry := range entries {
			if entry.Name() == ".git" {
				continue
			}
			sum, e := treeDigest(ctx, filepath.Join(resolved, entry.Name()), workspace, sourceModules, memo, active, cache, entry.Type()&os.ModeSymlink != 0)
			if e != nil {
				return "", e
			}
			io.WriteString(h, entry.Name()+"\x00"+sum)
		}
	} else if info.Mode().IsRegular() {
		sum, e := cache.digest(resolved)
		if e != nil {
			return "", e
		}
		io.WriteString(h, sum)
	} else {
		return "", fmt.Errorf("unsupported check input %s", path)
	}
	result := hex.EncodeToString(h.Sum(nil))
	memo[resolved] = result
	return result, nil
}

type InputCache struct{ files map[string]cachedInput }
type cachedInput struct {
	size     int64
	mode     os.FileMode
	modified int64
	metadata string
	hash     string
}

func (c *InputCache) digest(path string) (string, error) {
	info, e := os.Stat(path)
	if e != nil {
		return "", e
	}
	stamp := cachedInput{size: info.Size(), mode: info.Mode(), modified: info.ModTime().UnixNano(), metadata: fmt.Sprint(info.Sys())}
	if c.files == nil {
		c.files = map[string]cachedInput{}
	}
	if old, ok := c.files[path]; ok && old.size == stamp.size && old.mode == stamp.mode && old.modified == stamp.modified && old.metadata == stamp.metadata {
		return old.hash, nil
	}
	stamp.hash, e = fileDigest(path)
	if e != nil {
		return "", e
	}
	c.files[path] = stamp
	return stamp.hash, nil
}

func resolveProgram(program, cwd string, env []string) (string, error) {
	if strings.ContainsRune(program, filepath.Separator) {
		if !filepath.IsAbs(program) {
			program = filepath.Join(cwd, program)
		}
		return program, nil
	}
	pathValue := ""
	for _, entry := range env {
		if strings.HasPrefix(entry, "PATH=") {
			pathValue = strings.TrimPrefix(entry, "PATH=")
		}
	}
	for _, dir := range filepath.SplitList(pathValue) {
		if !filepath.IsAbs(dir) {
			dir = filepath.Join(cwd, dir)
		}
		candidate := filepath.Join(dir, program)
		info, e := os.Stat(candidate)
		if e == nil && info.Mode().IsRegular() && info.Mode()&0111 != 0 {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("program %s is unavailable in the check PATH", program)
}
