package agentci

import (
	"context"
	"crypto/sha256"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type workflowJob struct {
	ID         int64  `json:"id"`
	RunID      int64  `json:"run_id"`
	SHA        string `json:"head_sha"`
	Status     string `json:"status"`
	Conclusion string `json:"conclusion"`
	Steps      []struct {
		Name       string `json:"name"`
		Number     int    `json:"number"`
		Status     string `json:"status"`
		Conclusion string `json:"conclusion"`
	} `json:"steps"`
}

type annotation struct {
	Level   string `json:"annotation_level"`
	Path    string `json:"path"`
	Title   string `json:"title"`
	Message string `json:"message"`
}

type failureEvidence struct {
	diagnostics    []string
	infrastructure bool
	unavailable    bool
}

func inspectFailure(ctx context.Context, client *fixer.GitHubClient, check Check) failureEvidence {
	if check.ID <= 0 || check.action.ID <= 0 || check.action.WorkflowID <= 0 || check.action.SHA != check.SHA {
		return failureEvidence{}
	}
	var job workflowJob
	if err := client.Request(ctx, http.MethodGet, fmt.Sprintf("/actions/jobs/%d", check.ID), nil, &job); err != nil ||
		job.ID != check.ID || job.RunID != check.action.ID || job.SHA != check.SHA || job.Status != "completed" || job.Conclusion != check.Conclusion {
		return failureEvidence{unavailable: true}
	}
	var annotations []annotation
	_ = client.Request(ctx, http.MethodGet, fmt.Sprintf("/check-runs/%d/annotations?per_page=30", check.ID), nil, &annotations)
	if beforeRepositorySteps(job) {
		for _, note := range annotations {
			if note.Level == "failure" && (note.Path == "" || note.Path == ".github") && runnerFailure(note.Title+"\n"+note.Message) {
				return failureEvidence{infrastructure: true}
			}
		}
	}
	if check.Conclusion != "failure" {
		return failureEvidence{}
	}
	var diagnostics []string
	var dependencyFailure dependencyErrors
	for _, note := range annotations {
		if note.Level == "failure" {
			diagnostics = readDiagnostics(diagnostics, &dependencyFailure, note.Title+"\n"+note.Message, note.Path)
		}
	}
	var readSteps int
	var logUnavailable bool
	for _, step := range job.Steps {
		if len(diagnostics) >= maxDiagnostics && !slices.ContainsFunc(diagnostics, isJestSuiteDiagnostic) {
			break
		}
		if step.Status != "completed" || step.Conclusion != "failure" {
			continue
		}
		if readSteps == 3 {
			logUnavailable = true
			break
		}
		readSteps++
		if log, err := client.ActionsStepLog(ctx, job.ID, step.Number); err == nil {
			if beforeRepositorySteps(job) && runnerFailure(log) {
				return failureEvidence{infrastructure: true}
			}
			diagnostics = readDiagnostics(diagnostics, &dependencyFailure, log, "")
		} else {
			logUnavailable = true
		}
	}
	if len(diagnostics) == 0 && dependencyFailure.transient && !dependencyFailure.configuration && !logUnavailable {
		return failureEvidence{infrastructure: true}
	}
	for i, diagnostic := range diagnostics {
		diagnostics[i] = client.RedactGitHubToken(redactProviderTokens(diagnostic))
	}
	return failureEvidence{diagnostics: diagnostics, unavailable: logUnavailable}
}

func beforeRepositorySteps(job workflowJob) bool {
	for _, step := range job.Steps {
		if (step.Number != 1 || step.Name != "Set up job") && step.Conclusion != "skipped" && step.Status != "queued" {
			return false
		}
	}
	return true
}

func runnerFailure(text string) bool {
	text = strings.ToLower(text)
	for _, message := range []string{
		"the job was not acquired by runner of type hosted even after multiple attempts",
		"the hosted runner encountered an error while running your job",
		"the runner has received a shutdown signal",
		"lost communication with the server",
		"our servers are currently too busy to handle your request",
	} {
		if strings.Contains(text, message) {
			return true
		}
	}
	return false
}

const maxDiagnostics = 3
const maxDiagnosticBytes = 200

var ansiSequence = regexp.MustCompile(`\x1b\[[0-?]*[ -/]*[@-~]`)
var logTimestamp = regexp.MustCompile(`^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\S*\s+`)
var providerToken = regexp.MustCompile(`(?i)\b(?:sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9_]{12,}|github_pat_[a-z0-9_]+|xox[bpars]-[a-z0-9-]{12,}|(?:AKIA|ASIA)[A-Z0-9]{16})\b`)

func redactProviderTokens(value string) string {
	return providerToken.ReplaceAllString(value, "[redacted]")
}

type diagnosticPattern struct {
	pattern *regexp.Regexp
	format  string
}

const sourcePath = `[a-zA-Z0-9_.@/\\-]+`

const jestFailurePrefix = "Jest reported a failure in "
const jestCaseSeparator = " (test title SHA-256 prefix "

var jestSuiteHeader = regexp.MustCompile(`^FAIL\s+(` + sourcePath + `\.(?:test|spec)\.[cm]?[jt]sx?)\s*(?:$|\()`)

func isJestSuiteDiagnostic(excerpt string) bool {
	return strings.HasPrefix(excerpt, jestFailurePrefix) && !strings.Contains(excerpt, jestCaseSeparator)
}

func jestSuiteForCase(excerpt string) string {
	if !strings.HasPrefix(excerpt, jestFailurePrefix) {
		return ""
	}
	base, _, found := strings.Cut(excerpt, jestCaseSeparator)
	if !found {
		return ""
	}
	return base + "."
}

func addJestCase(diagnostics []string, suite, title string) []string {
	// Hash the title so only structured evidence enters the revision prompt.
	digest := sha256.Sum256([]byte(title))
	excerpt := fmt.Sprintf("%s%s%x).", strings.TrimSuffix(suite, "."), jestCaseSeparator, digest[:16])
	if i := slices.Index(diagnostics, suite); i >= 0 {
		diagnostics[i] = excerpt
	} else if len(diagnostics) < maxDiagnostics && !slices.Contains(diagnostics, excerpt) {
		diagnostics = append(diagnostics, excerpt)
	}
	return diagnostics
}

var diagnosticPatterns = []diagnosticPattern{
	{regexp.MustCompile(`^(` + sourcePath + `\.tsx?)\((\d+),(\d+)\):\s+error (TS\d+):`), `TypeScript reported $4 at $1:$2:$3.`},
	{regexp.MustCompile(`^(` + sourcePath + `\.go):(\d+):(\d+):\s+(?:undefined:|cannot |declared and not used:|syntax error:|missing |too many |not enough )`), `Go reported a source error at $1:$2:$3.`},
	{regexp.MustCompile(`^\[ERROR\]\s+(` + sourcePath + `\.java):\[(\d+),(\d+)\]`), `The Java compiler reported a source error at $1:$2:$3.`},
	{regexp.MustCompile(`^(?:\[ERROR\]\s+)?(` + sourcePath + `\.java):(\d+(?::\d+)?): .*\[([A-Za-z][A-Za-z0-9]+)\]$`), `Checkstyle reported $3 at $1:$2.`},
	{regexp.MustCompile(`^(` + sourcePath + `\.go):(\d+):(\d+): .*\(([a-z][a-z0-9]+)\)$`), `golangci-lint reported $4 at $1:$2:$3.`},
	{regexp.MustCompile(`^--- FAIL:\s+(Test[A-Za-z0-9_]+(?:/[a-zA-Z0-9_.@-]+)*)\s+\(`), `Go reported a failure in $1.`},
	{regexp.MustCompile(`^\d+\)\s+\[[a-zA-Z0-9_-]+\]\s+›\s+(` + sourcePath + `\.spec\.[jt]s):(\d+):(\d+)\s+›`), `Playwright reported a failed test at $1:$2:$3.`},
	{regexp.MustCompile(`^\[ERROR\]\s+([A-Za-z0-9_.$]+Test[A-Za-z0-9_.$]*)\s*»`), `Maven reported a failure in $1.`},
}

type dependencyErrors struct {
	transient     bool
	configuration bool
}

var mavenDownloadError = regexp.MustCompile(`^\[ERROR\].*Could not transfer artifact .*\(https://(?:repo\.maven\.apache\.org|repo1\.maven\.org)/[^)]*\).*status code: ([45]\d\d)\b`)
var npmDownloadError = regexp.MustCompile(`(?i)^npm (?:error|ERR!) ([45]\d\d)\s+.* - GET https://(?:registry\.npmjs\.org|npm\.pkg\.github\.com)/`)

var eslintFile = regexp.MustCompile(`^` + sourcePath + `\.[cm]?[jt]sx?$`)
var eslintFinding = regexp.MustCompile(`^(\d+):(\d+)\s+(error|warning)\s+.+?\s{2,}(@?[a-z][a-z0-9_/-]+)\s*$`)

func eslintPath(value string) string {
	if !eslintFile.MatchString(value) {
		return ""
	}
	value = strings.ReplaceAll(value, `\`, "/")
	if workspace, ok := strings.CutPrefix(value, "/home/runner/work/"); ok {
		parts := strings.SplitN(workspace, "/", 3)
		if len(parts) == 3 && parts[0] == parts[1] {
			return parts[2]
		}
	}
	return strings.TrimPrefix(value, "./")
}

func readDiagnostics(diagnostics []string, dependency *dependencyErrors, output, annotationPath string) []string {
	annotationPath = eslintPath(annotationPath)
	file := annotationPath
	var jestSuite string
	for line := range strings.SplitSeq(output, "\n") {
		line = ansiSequence.ReplaceAllString(line, "")
		line = logTimestamp.ReplaceAllString(line, "")
		line = strings.TrimSpace(strings.TrimPrefix(line, "##[error]"))
		if match := jestSuiteHeader.FindStringSubmatch(line); match != nil {
			file = annotationPath
			jestSuite = jestFailurePrefix + match[1] + "."
			if len(jestSuite) > maxDiagnosticBytes {
				jestSuite = ""
			} else if len(diagnostics) < maxDiagnostics && !slices.ContainsFunc(diagnostics, func(excerpt string) bool {
				return excerpt == jestSuite || jestSuiteForCase(excerpt) == jestSuite
			}) {
				diagnostics = append(diagnostics, jestSuite)
			}
			continue
		}
		if strings.HasPrefix(line, "Test Suites:") || strings.HasPrefix(line, "PASS ") {
			jestSuite = ""
		}
		if title, ok := strings.CutPrefix(line, "● "); ok && jestSuite != "" {
			if title == "Test suite failed to run" {
				jestSuite = ""
			} else {
				diagnostics = addJestCase(diagnostics, jestSuite, title)
			}
			continue
		}
		if len(diagnostics) >= maxDiagnostics {
			continue
		}
		if path := eslintPath(line); path != "" {
			file = path
			continue
		}
		if match := eslintFinding.FindStringSubmatch(line); match != nil {
			if file != "" && match[3] == "error" {
				value := fmt.Sprintf("ESLint reported %s at %s:%s:%s.", match[4], file, match[1], match[2])
				if len(value) <= maxDiagnosticBytes && !slices.Contains(diagnostics, value) {
					diagnostics = append(diagnostics, value)
				}
			}
			continue
		}
		file = annotationPath
		for _, downloadError := range []*regexp.Regexp{mavenDownloadError, npmDownloadError} {
			if match := downloadError.FindStringSubmatch(line); match != nil {
				switch match[1] {
				case "502", "503", "504":
					dependency.transient = true
				default:
					dependency.configuration = true
				}
			}
		}
		for _, diagnostic := range diagnosticPatterns {
			match := diagnostic.pattern.FindStringSubmatchIndex(line)
			if match == nil {
				continue
			}
			value := string(diagnostic.pattern.ExpandString(nil, diagnostic.format, line, match))
			if len(value) <= maxDiagnosticBytes && !slices.Contains(diagnostics, value) {
				diagnostics = append(diagnostics, value)
			}
			break
		}
	}
	return diagnostics
}
