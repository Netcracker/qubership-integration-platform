package experiment

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentworkspace"
)

type Candidate struct {
	JobID          string                       `json:"job_id"`
	CreatedAt      time.Time                    `json:"created_at"`
	Snapshot       experimentworkspace.Snapshot `json:"snapshot"`
	PatchHash      string                       `json:"patch_sha256"`
	Verification   string                       `json:"verification"`
	EvidenceHashes map[string]string            `json:"evidence_sha256"`
}

func Hash(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

func Capture(ctx context.Context, root, id string) (Candidate, error) {
	var c Candidate
	p, e := Load(root)
	if e != nil {
		return c, e
	}
	if _, e = p.Job(id); e != nil {
		return c, e
	}
	dir, e := RunDirectory(root, id, "implement")
	if e != nil {
		return c, e
	}
	cd := filepath.Join(root, "runs", id, "candidate")
	if _, e = os.Lstat(cd); !os.IsNotExist(e) {
		return c, fmt.Errorf("candidate already exists: %s", cd)
	}
	var prepared Prepared
	if e = ReadJSON(filepath.Join(dir, "prepared.json"), &prepared); e != nil {
		return c, e
	}
	w := prepared.Snapshot.Directory
	h, e := InstructionHashes(w)
	if e != nil {
		return c, e
	}
	if !reflect.DeepEqual(h, prepared.Instructions) {
		return c, fmt.Errorf("project instructions changed; candidate requires investigation")
	}
	if e = os.Mkdir(cd, 0700); e != nil {
		return c, e
	}
	staging, e := experimentworkspace.Create(ctx, p.Source, p.Revision, filepath.Join(cd, "capture-base"), "capture")
	if e != nil {
		return c, e
	}
	defer os.RemoveAll(staging.Directory)
	stageGit := func(args ...string) ([]byte, error) { return trustedWorktreeGit(ctx, staging.Directory, w, args...) }
	if _, e = stageGit("add", "--all"); e != nil {
		return c, e
	}
	patch, e := stageGit("diff", "--cached", "--binary", "--no-ext-diff", "--no-textconv", staging.BaseSHA, "--")
	if e != nil {
		return c, e
	}
	if _, e = stageGit("-c", "user.name=Experiment", "-c", "user.email=experiment@localhost", "commit", "--allow-empty", "-m", "fix: record experiment candidate"); e != nil {
		return c, e
	}
	sha, e := stageGit("rev-parse", "HEAD")
	if e != nil {
		return c, e
	}
	s, e := experimentworkspace.Create(ctx, staging.Directory, strings.TrimSpace(string(sha)), filepath.Join(cd, "repository"), "candidate")
	if e != nil {
		return c, e
	}
	if e = os.WriteFile(filepath.Join(cd, "candidate.patch"), patch, 0600); e != nil {
		return c, e
	}
	evidence, e := captureEvidence(dir, filepath.Join(cd, "evidence"), prepared.Profile.Runtime)
	if e != nil {
		return c, e
	}
	c = Candidate{JobID: id, CreatedAt: time.Now().UTC(), Snapshot: s, PatchHash: Hash(patch), Verification: "unverified", EvidenceHashes: evidence}
	if e = WriteJSON(filepath.Join(cd, "candidate.json"), c); e != nil {
		return c, e
	}
	return c, nil
}

func trustedWorktreeGit(ctx context.Context, repository, worktree string, args ...string) ([]byte, error) {
	return git(ctx, repository, append([]string{"--git-dir=" + filepath.Join(repository, ".git"), "--work-tree=" + worktree}, args...)...)
}

func captureEvidence(runDir, dest, runtime string) (map[string]string, error) {
	if e := os.Mkdir(dest, 0700); e != nil {
		return nil, e
	}
	text := "No final author report is available. Verify the candidate independently.\n"
	f, e := os.Open(filepath.Join(runDir, "execution", "stdout.jsonl"))
	if e == nil {
		defer f.Close()
		text, e = experimentruntime.FinalText(f, runtime)
		if e != nil {
			text = "The recorded runtime output has no supported final author report. Verify the candidate independently.\n"
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	if e = os.WriteFile(filepath.Join(dest, "author-report.txt"), []byte(text), 0600); e != nil {
		return nil, e
	}
	status := map[string]any{"verification": "unverified", "runtime_outcome": "unknown", "exit_code": nil, "terminal_event_observed": nil}
	var execution Execution
	if e = ReadJSON(filepath.Join(runDir, "execution", "result.json"), &execution); e == nil {
		status["runtime_outcome"] = execution.Outcome
		status["exit_code"] = execution.Process.ExitCode
		status["terminal_event_observed"] = execution.Events.TerminalObserved
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	if e = WriteJSON(filepath.Join(dest, "runtime-status.json"), status); e != nil {
		return nil, e
	}
	source := filepath.Join(runDir, "execution", "home", "evidence")
	if _, e = os.Lstat(source); e == nil {
		if e = copyEvidence(source, filepath.Join(dest, "artifacts")); e != nil {
			return nil, e
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	return HashFiles(dest)
}

func copyEvidence(source, dest string) error {
	return filepath.WalkDir(source, func(path string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		rel, e := filepath.Rel(source, path)
		if e != nil {
			return e
		}
		target := filepath.Join(dest, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0700)
		}
		if !d.Type().IsRegular() {
			return fmt.Errorf("evidence must contain only regular files: %s", rel)
		}
		in, e := os.Open(path)
		if e != nil {
			return e
		}
		defer in.Close()
		out, e := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return e
		}
		_, e = io.Copy(out, in)
		return firstError(e, out.Close())
	})
}

type Assessment struct {
	JobID           string      `json:"job_id"`
	CandidateTree   string      `json:"candidate_tree"`
	Outcome         string      `json:"outcome"`
	PrimaryCause    string      `json:"primary_cause"`
	EngineerMinutes *float64    `json:"engineer_minutes"`
	Criteria        []Criterion `json:"criteria"`
	Findings        []Finding   `json:"findings"`
	Notes           string      `json:"notes"`
}
type Criterion struct {
	Description string   `json:"description"`
	Status      string   `json:"status"`
	Evidence    []string `json:"evidence"`
}
type Finding struct {
	Reviewer    string   `json:"reviewer"`
	Description string   `json:"description"`
	Verdict     string   `json:"verdict"`
	Evidence    []string `json:"evidence"`
}

func AssessmentTemplate(root, id string) (Assessment, error) {
	var a Assessment
	p, e := Load(root)
	if e != nil {
		return a, e
	}
	j, e := p.Job(id)
	if e != nil {
		return a, e
	}
	var t Task
	if e = ReadJSON(filepath.Join(p.Inputs, fmt.Sprint(j.Issue), "task.json"), &t); e != nil {
		return a, e
	}
	a = Assessment{JobID: id, Outcome: "unassessed", PrimaryCause: "unknown", Criteria: []Criterion{}, Findings: []Finding{}}
	for _, criterion := range t.Criteria {
		a.Criteria = append(a.Criteria, Criterion{criterion, "unknown", []string{}})
	}
	return a, nil
}
