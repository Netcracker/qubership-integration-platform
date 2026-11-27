package localwork

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcontext"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type EngineeringTriage struct {
	Candidates     []tasktriage.Candidate `json:"candidates,omitempty"`
	DisableRouting bool                   `json:"disable_routing,omitempty"`
	CoverageAudit  string                 `json:"coverage_audit,omitempty"`
}

func (d *Driver) Advise(ctx context.Context, in workcycle.AdviceInput) (*workcycle.RoutingAdvice, error) {
	if d.Config.EngineeringTriage == nil || d.Config.EngineeringTriage.DisableRouting {
		return nil, nil
	}
	if e := d.checkArtifacts(); e != nil {
		return nil, e
	}
	if e := workcontext.Verify(d.Config.Workspace, d.Config.TaskDirectory, d.Packet); e != nil {
		return nil, e
	}
	p := tasktriage.Packet{SourceAvailable: true, TaskID: in.Request.TaskID, Phase: in.Phase, Task: in.Request.Task, BaseSHA: d.base, CandidateID: in.Candidate.ID, ChangedPaths: in.Candidate.ChangedPaths, MandatoryReviews: in.MandatoryReviews, ConsumerHints: tasktriage.KnownConsumers(in.Candidate.ChangedPaths)}
	contract, e := json.Marshal(in.Contract)
	if e != nil {
		return nil, e
	}
	p.Contract = contract
	requirements, _ := json.Marshal(map[string]any{"criteria": in.Request.RequiredCriteria, "checks": in.Request.RequiredChecks, "consumers": in.Request.RequiredConsumers, "environment_id": in.Request.EnvironmentID, "stage": in.Stage})
	p.Context = string(requirements)
	if in.Phase == "intake" || in.Phase == "contract" {
		p.Candidates = d.Config.EngineeringTriage.Candidates
		p, e = tasktriage.Discover(ctx, d.Config.Workspace, p)
		if e != nil {
			return nil, e
		}
	}
	if in.Phase == "review" {
		before, e := d.Snapshot(ctx)
		if e != nil {
			return nil, e
		}
		if before.ID != in.Candidate.ID {
			return nil, fmt.Errorf("candidate changed before review routing")
		}

		files := map[string]string{}
		for _, name := range in.Candidate.ChangedPaths {
			diff, e := git(ctx, d.Config.Workspace, "diff", "--no-ext-diff", "--no-textconv", d.base, "--", name)
			if e != nil {
				return nil, e
			}
			if len(diff) > 0 {
				files[name] = string(diff)
				continue
			}
			_, trackedErr := git(ctx, d.Config.Workspace, "ls-files", "--error-unmatch", "--", name)
			if trackedErr == nil {
				continue
			}
			file := filepath.Join(d.Config.Workspace, name)
			info, e := os.Lstat(file)
			if e != nil {
				return nil, e
			}
			if !info.Mode().IsRegular() {
				p.Limitations = append(p.Limitations, "Non-regular new file: "+name)
				continue
			}
			content, e := os.ReadFile(file)
			if e != nil {
				return nil, e
			}
			files[name] = "New file: " + name + "\n" + string(content)
		}
		p.Diff, p.DiffCoverage = tasktriage.ReviewDiff(files, 48000)
		for _, coverage := range p.DiffCoverage {
			if coverage.IncludedBytes < coverage.TotalBytes {
				p.Limitations = append(p.Limitations, fmt.Sprintf("Partial diff for %s: %d of %d bytes", coverage.Path, coverage.IncludedBytes, coverage.TotalBytes))
			}
		}

		if strings.TrimSpace(p.Diff) == "" {
			p.Diff = "No textual diff is available."
			p.Limitations = append(p.Limitations, "Missing textual diff")
		}
	}
	if in.Phase == "failure" {
		p.Failure = d.engineeringFailure(in)
		if in.Question != nil {
			p.Failure.Question = &tasktriage.Question{Text: in.Question.Text, Needed: in.Question.Needed, Evidence: in.Question.Evidence}
			p.Failure.Message += "\n" + in.Question.Text + "\n" + in.Question.Needed
		}
	}
	if len(p.Task) > 12000 {
		p.Task = p.Task[:12000]
		p.Limitations = append(p.Limitations, "Task text truncated at 12000 bytes")
	}
	request, e := tasktriage.Build(p)
	if e != nil {
		return nil, e
	}
	client := d.triageClient
	if len(request.Questions) > 0 && client == nil && (p.Failure == nil || p.Failure.Kind == "") {
		client, e = jev.NewClient(os.Getenv("OPENROUTER_API_KEY"), jev.Options{})
		if e != nil {
			return nil, e
		}
	}
	report, e := tasktriage.Evaluate(ctx, p, client)
	if e != nil {
		return nil, e
	}
	path, e := d.archiveEngineeringAdvice(report)
	if e != nil {
		return nil, e
	}
	s := report.Selection
	return &workcycle.RoutingAdvice{CoverageCandidates: s.CoverageCandidates, QuestionAction: s.QuestionAction, QuestionActionCandidate: s.QuestionActionCandidate, ModuleCandidates: s.ModuleCandidates, ReviewCandidates: s.ReviewCandidates, ReadinessCandidate: s.ReadinessCandidate, RouteCandidate: s.RouteCandidate, Phase: in.Phase, CandidateID: in.Candidate.ID, Readiness: s.Readiness, Route: s.Route, Modules: s.Modules, Skills: s.Skills, ExtraReviews: s.ExtraReviews, DuplicateCandidates: s.DuplicateCandidates, FailureKind: s.FailureKind, Evidence: path}, nil
}

func (d *Driver) archiveEngineeringAdvice(report tasktriage.Report) (string, error) {
	if e := tasktriage.Validate(report); e != nil {
		return "", e
	}
	d.triageStep++
	path := filepath.Join(d.evidence, fmt.Sprintf("engineering-triage-%04d.json", d.triageStep))
	if e := writeJSON(path, report); e != nil {
		return "", e
	}
	b, e := os.ReadFile(path)
	if e != nil {
		return "", e
	}
	if d.artifacts == nil {
		d.artifacts = map[string]string{}
	}
	// Artifact hashes cover the exact bytes written, as with native evidence.
	d.artifacts[path] = bytesDigest(b)
	return path, nil
}

func bytesDigest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

func (d *Driver) engineeringFailure(in workcycle.AdviceInput) *tasktriage.Failure {
	result := &tasktriage.Failure{Kind: d.failureKind, Message: in.Failure, Stage: string(in.Stage)}
	if in.Stage == workcycle.Stage("check") {
		if check := d.lastStaticCheck; check != nil {
			result.Stage = "check:" + check.ID
			result.ProcessExit = &check.ExitCode
			result.Log = "Command: " + sanitizeDiagnostic(strings.Join(check.Recipe.Command, " "))
			for _, path := range []string{check.Stdout, check.Stderr} {
				f, e := os.Open(path)
				if e != nil {
					continue
				}
				info, e := f.Stat()
				if e == nil && info.Size() > 16384 {
					_, _ = f.Seek(-16384, io.SeekEnd)
				}
				b, _ := io.ReadAll(io.LimitReader(f, 16384))
				f.Close()
				result.Log += "\n" + diagnosticExcerpt(string(b))
			}
		}
		return result
	}
	var directory string
	if in.Stage == workcycle.ReviewStage && d.ReviewParallelism() > 0 {
		directory = d.reviewFailureArchive
	} else if paths, _ := filepath.Glob(filepath.Join(d.Config.OutputDirectory, fmt.Sprintf("step-%04d-*", d.step))); len(paths) == 1 {
		directory = paths[0]
	}
	if directory == "" {
		return result
	}
	var process struct {
		ExitCode       *int   `json:"ExitCode"`
		Classification string `json:"classification"`
	}
	if b, e := os.ReadFile(filepath.Join(directory, "process.json")); e == nil && json.Unmarshal(b, &process) == nil {
		result.ProcessExit = process.ExitCode
		result.ProcessClassification = process.Classification
	}

	var progress struct {
		Stage       string `json:"stage"`
		LastCommand *struct {
			Command       string `json:"command"`
			Status        string `json:"status"`
			ExitCode      *int   `json:"exit_code"`
			OutputExcerpt string `json:"output_excerpt"`
		} `json:"last_command"`
	}
	if b, e := os.ReadFile(filepath.Join(directory, "progress.json")); e == nil && json.Unmarshal(b, &progress) == nil {
		if result.Stage == "" {
			result.Stage = progress.Stage
		}
		if progress.LastCommand != nil {
			command := progress.LastCommand
			exit := "unknown"
			if command.ExitCode != nil {
				exit = fmt.Sprint(*command.ExitCode)
			}
			text := fmt.Sprintf("Last command status: %s; exit: %s\n%s\n%s", command.Status, exit, command.Command, command.OutputExcerpt)
			result.Log = "progress.json:\n" + sanitizeDiagnostic(text)
		}
	}
	for _, name := range []string{"stderr.log"} {
		f, e := os.Open(filepath.Join(directory, name))
		if e != nil {
			continue
		}
		info, e := f.Stat()
		if e != nil {
			f.Close()
			continue
		}
		if info.Size() > 16384 {
			_, _ = f.Seek(-16384, io.SeekEnd)
		}
		b, _ := io.ReadAll(io.LimitReader(f, 16384))
		f.Close()
		excerpt := diagnosticExcerpt(string(b))
		if excerpt != "" {
			result.Log += "\n" + name + ":\n" + excerpt
		}
	}
	return result
}

var credentialValue = regexp.MustCompile(`(?i)(authorization|cookie|api[_-]?key|access[_-]?token|password|secret)(["'\s:=]+)([^\s,"'}]+)`)
var bearerValue = regexp.MustCompile(`(?i)bearer\s+[A-Za-z0-9._~+/=-]+`)
var urlCredentials = regexp.MustCompile(`(?i)(https?://)[^/@\s]+:[^/@\s]+@`)
var providerToken = regexp.MustCompile(`(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]+)`)

func sanitizeDiagnostic(text string) string {
	for _, key := range []string{"OPENROUTER_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN", "GH_ACCESS_TOKEN", "AGENT_PROJECT_TOKEN"} {
		if secret := os.Getenv(key); secret != "" {
			text = strings.ReplaceAll(text, secret, "<REDACTED>")
		}
	}
	text = bearerValue.ReplaceAllString(text, "Bearer <REDACTED>")
	text = credentialValue.ReplaceAllString(text, "${1}${2}<REDACTED>")
	text = urlCredentials.ReplaceAllString(text, "${1}<REDACTED>@")
	text = providerToken.ReplaceAllString(text, "<REDACTED>")
	if len(text) > 6000 {
		text = text[len(text)-6000:]
	}
	return text
}

func diagnosticExcerpt(text string) string {
	text = sanitizeDiagnostic(text)
	lines := strings.Split(text, "\n")
	var selected []string
	for i, line := range lines {
		lower := strings.ToLower(line)
		if strings.Contains(lower, "error") || strings.Contains(lower, "exception") || strings.Contains(lower, "fail") || strings.Contains(lower, "exit_code") || strings.Contains(lower, "classification") || strings.Contains(lower, "lastcommand") || strings.Contains(lower, "output_excerpt") {
			selected = append(selected, strings.Join(lines[max(0, i-1):min(len(lines), i+2)], "\n"))
		}
	}
	result := strings.Join(selected, "\n")
	if len(result) > 4000 {
		result = result[:4000] + "\n[diagnostic excerpt truncated]"
	}
	return result
}
