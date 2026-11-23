package experiment

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

type Profile struct {
	ID      string `json:"id"`
	Runtime string `json:"runtime"`
	Model   string `json:"model"`
	Effort  string `json:"effort"`
}

func (p Profile) RuntimeProfile() experimentruntime.Profile {
	return experimentruntime.Profile{Runtime: p.Runtime, Model: p.Model, Effort: p.Effort}
}

func Profiles() []Profile {
	return []Profile{
		{"c1", "codex", "gpt-6.1-sol", "medium"},
		{"c2", "codex", "gpt-6.1-terra", "medium"},
		{"c3", "codex", "gpt-6-luna", "high"},
		{"o1", "opencode", "opencode/deepseek-v4.1-flash-free", "high"},
		{"o2", "opencode", "opencode/glm-5.3-flash-free", "high"},
		{"o3", "opencode", "opencode/mimo-v2.6-flash-free", ""},
		{"o4", "opencode", "opencode/beam-free", "high"},
	}
}

func Reviewers(executor Profile) []Profile {
	return []Profile{
		{"r1", "codex", "gpt-6.1-sol", "high"},
		{"r2", "codex", "gpt-6.1-terra", "high"},
		{"r3", executor.Runtime, executor.Model, executor.Effort},
	}
}

type Task struct {
	Issue          int      `json:"issue"`
	Title          string   `json:"title"`
	URL            string   `json:"url"`
	Objective      string   `json:"objective"`
	Criteria       []string `json:"criteria"`
	Prerequisites  []string `json:"prerequisites"`
	EvaluationKind string   `json:"evaluation_kind"`
	SourceFiles    []string `json:"source_files"`
}

type Job struct {
	ID        string    `json:"id"`
	RunToken  string    `json:"run_token"`
	Issue     int       `json:"issue"`
	Executor  Profile   `json:"executor"`
	Reviewers []Profile `json:"reviewers"`
}

type Plan struct {
	Version     int               `json:"version"`
	CreatedAt   time.Time         `json:"created_at"`
	Source      string            `json:"source"`
	Revision    string            `json:"revision"`
	Inputs      string            `json:"inputs"`
	InputHashes map[string]string `json:"input_sha256"`
	Jobs        []Job             `json:"jobs"`
	Concurrency int               `json:"concurrency"`
	Limitations []string          `json:"limitations"`
}

var safeID = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

func NewPlan(source, revision, inputs string) (Plan, error) {
	p := Plan{Version: 1, CreatedAt: time.Now().UTC(), Source: source, Revision: revision, Inputs: inputs, Concurrency: 1,
		Limitations: []string{"Local runs do not establish GitHub Actions readiness.", "Shared network access does not prevent access to published solutions or local services.", "A completed process does not establish task success.", "Model access and effective reasoning variants require a live capability probe before the comparison.", "Product baseline and repeated verification on data B require recorded evidence."}}
	if !regexp.MustCompile(`^[a-f0-9]{40}$`).MatchString(revision) {
		return p, fmt.Errorf("source revision must be a full commit SHA")
	}
	var err error
	p.Source, err = filepath.Abs(source)
	if err != nil {
		return p, err
	}
	p.Inputs, err = filepath.Abs(inputs)
	if err != nil {
		return p, err
	}
	p.InputHashes, err = HashFiles(p.Inputs)
	if err != nil {
		return p, err
	}
	profiles := Profiles()
	tokens := map[string]bool{}
	for i, issue := range []int{1215, 878, 1093, 1168, 1158} {
		var task Task
		if err := ReadJSON(filepath.Join(p.Inputs, fmt.Sprint(issue), "task.json"), &task); err != nil {
			return p, err
		}
		if task.Issue != issue || task.Objective == "" || len(task.Criteria) == 0 {
			return p, fmt.Errorf("invalid task %d", issue)
		}
		for j := range profiles {
			x := profiles[(i+j)%len(profiles)]
			reviewers := Reviewers(x)
			rotation := (i + j) % len(reviewers)
			reviewers = append(reviewers[rotation:], reviewers[:rotation]...)
			token := ""
			for token == "" || tokens[token] {
				var b [6]byte
				if _, err = rand.Read(b[:]); err != nil {
					return p, err
				}
				const letters = "abcdefghijklmnopqrstuvwxyz"
				const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
				value := []byte{letters[int(b[0])%len(letters)]}
				for _, v := range b[1:] {
					value = append(value, alphabet[int(v)%len(alphabet)])
				}
				token = string(value)
			}
			tokens[token] = true
			p.Jobs = append(p.Jobs, Job{ID: fmt.Sprintf("%d-%s-01", issue, x.ID), RunToken: token, Issue: issue, Executor: x, Reviewers: reviewers})
		}
	}
	return p, nil
}

func ReadJSON(path string, target any) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	d := json.NewDecoder(f)
	d.DisallowUnknownFields()
	if e = d.Decode(target); e != nil {
		return e
	}
	var extra any
	if e = d.Decode(&extra); e != io.EOF {
		return fmt.Errorf("unexpected data after JSON in %s", path)
	}
	return nil
}

func WriteJSON(path string, value any) error {
	b, e := json.MarshalIndent(value, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	_, e = f.Write(append(b, '\n'))
	if e == nil {
		e = f.Sync()
	}
	return firstError(e, f.Close())
}

func HashFiles(root string) (map[string]string, error) {
	hashes := map[string]string{}
	e := filepath.WalkDir(root, func(path string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if d.IsDir() {
			return nil
		}
		if !d.Type().IsRegular() {
			return fmt.Errorf("archive input is not a regular file: %s", path)
		}
		b, e := os.ReadFile(path)
		if e != nil {
			return e
		}
		rel, e := filepath.Rel(root, path)
		if e != nil {
			return e
		}
		h := sha256.Sum256(b)
		hashes[filepath.ToSlash(rel)] = hex.EncodeToString(h[:])
		return nil
	})
	return hashes, e
}

func firstError(a, b error) error {
	if a != nil {
		return a
	}
	return b
}

func (p Plan) Job(id string) (Job, error) {
	if !safeID.MatchString(id) {
		return Job{}, fmt.Errorf("invalid job ID")
	}
	for _, j := range p.Jobs {
		if j.ID == id {
			if !regexp.MustCompile(`^[a-z][a-z0-9]{5}$`).MatchString(j.RunToken) {
				return Job{}, fmt.Errorf("job requires a six-character E2E run token")
			}
			return j, nil
		}
	}
	return Job{}, fmt.Errorf("unknown job %s", id)
}
