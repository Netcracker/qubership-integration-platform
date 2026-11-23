package fixer

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

const MaxHostedCandidateBytes = 24 * 1024 * 1024
const maxHostedReportBytes = 4 * 1024 * 1024

type HostedResult struct {
	Checkpoint      Checkpoint                `json:"checkpoint"`
	CandidateSHA256 string                    `json:"candidateSha256,omitempty"`
	ReportSHA256    string                    `json:"reportSha256"`
	Signature       *agentsignature.Signature `json:"signature,omitempty"`
	Outcome         string                    `json:"-"`
	Summary         string                    `json:"-"`
}

type HostedAnswer struct {
	QuestionID      string `json:"questionId"`
	QuestionVersion int    `json:"questionVersion"`
	ContractVersion int    `json:"contractVersion"`
	Question        string `json:"question"`
	Answer          string `json:"answer"`
	Actor           string `json:"actor"`
	Source          string `json:"source"`
}

func hostedAnswers(state *State) []HostedAnswer {
	var answers []HostedAnswer
	for _, decision := range state.Decisions {
		if decision.ContractVersion == state.Contract.Version && decision.Kind == "decision" && decision.Answer != nil && decision.Status == "answered" {
			answers = append(answers, HostedAnswer{QuestionID: decision.ID, QuestionVersion: decision.Version, ContractVersion: decision.ContractVersion,
				Question: decision.Question, Answer: decision.Answer.Text, Actor: decision.Answer.Actor, Source: decision.Answer.Source})
		}
	}
	return answers
}

type HostedCandidate struct {
	ExecutionID     string                    `json:"executionId"`
	Generation      int                       `json:"generation"`
	ContractVersion int                       `json:"contractVersion"`
	Run             RunIdentity               `json:"run"`
	ArtifactID      int64                     `json:"artifactId"`
	CandidateSHA256 string                    `json:"candidateSha256"`
	ReportSHA256    string                    `json:"reportSha256"`
	Signature       *agentsignature.Signature `json:"signature,omitempty"`
}

type HostedIssueResult struct {
	Run          RunIdentity              `json:"run"`
	ArtifactID   int64                    `json:"artifactId"`
	ReportSHA256 string                   `json:"reportSha256"`
	Signature    agentsignature.Signature `json:"signature"`
}

type HostedPublication struct {
	Number  int64  `json:"number"`
	SHA     string `json:"sha"`
	Branch  string `json:"branch"`
	URL     string `json:"url,omitempty"`
	BaseRef string `json:"baseRef,omitempty"`
	BaseSHA string `json:"baseSha,omitempty"`
}

func validExecutionMode(mode string) bool { return mode == "" || mode == "fake" || mode == "hosted" }

func executionMode(mode string) string {
	if mode == "" {
		return "fake"
	}
	return mode
}

func hostedArtifactName(run RunIdentity) string {
	return fmt.Sprintf("agent-hosted-%d-%d", run.RunID, run.RunAttempt)
}

func hostedBranch(issue int64, run RunIdentity) string {
	return fmt.Sprintf("agent/fix/%d/%d-%d", issue, run.RunID, run.RunAttempt)
}

func validateHostedCandidate(state *State, candidate HostedCandidate) error {
	if candidate.ArtifactID <= 0 || !digestPattern.MatchString(candidate.CandidateSHA256) || !digestPattern.MatchString(candidate.ReportSHA256) || candidate.Generation != state.Generation || candidate.ContractVersion != state.Contract.Version {
		return fmt.Errorf("hosted candidate does not match the active contract")
	}
	if candidate.Signature != nil && (candidate.Signature.Validate() != nil || candidate.Signature.Line() == "") {
		return fmt.Errorf("invalid hosted candidate signature")
	}
	for _, execution := range state.Executions {
		if execution.ID == candidate.ExecutionID && execution.Mode == "hosted" && execution.AttemptID == state.Attempt.ID && execution.Run != nil && *execution.Run == candidate.Run && execution.Generation == candidate.Generation && execution.ContractVersion == candidate.ContractVersion {
			return nil
		}
	}
	return fmt.Errorf("hosted candidate does not match an admitted execution")
}

func validHostedIssueResult(result *HostedIssueResult) bool {
	return result != nil && result.Run.RunID > 0 && result.Run.RunAttempt > 0 && result.ArtifactID > 0 && digestPattern.MatchString(result.ReportSHA256) && result.Signature.Validate() == nil && result.Signature.Line() != ""
}

func ReadHostedFile(path string, limit int64) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 || info.Size() > limit {
		return nil, fmt.Errorf("invalid hosted file %s", filepath.Base(path))
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) != info.Size() {
		return nil, fmt.Errorf("hosted file changed while reading %s", filepath.Base(path))
	}
	return data, nil
}

type hostedAuthorReport struct {
	Workflow string `json:"workflow"`
	Status   string `json:"status"`
	Summary  string `json:"summary"`
	Error    string `json:"error"`
}

func readHostedAuthorReport(path string) (hostedAuthorReport, string, error) {
	var result hostedAuthorReport
	report, err := ReadHostedFile(path, maxHostedReportBytes)
	if err != nil {
		return result, "", err
	}
	if err := json.Unmarshal(report, &result); err != nil {
		return result, "", err
	}
	if result.Workflow != "fix-issue" || !contains([]string{"author-reported-fixed", "needs-human", "blocked"}, result.Status) || strings.TrimSpace(result.Summary) == "" || result.Error != "" {
		return result, "", fmt.Errorf("the worker report has no usable author outcome")
	}
	hash := sha256.Sum256(report)
	return result, hex.EncodeToString(hash[:]), nil
}

func ReadHostedAuthorSummary(path, expectedDigest string) (string, error) {
	report, digest, err := readHostedAuthorReport(path)
	if err != nil {
		return "", err
	}
	if digest != expectedDigest || report.Status != "author-reported-fixed" {
		return "", fmt.Errorf("the author report differs from the authorized fixed result")
	}
	return report.Summary, nil
}

func SealHostedResult(checkpoint Checkpoint, dir string) (HostedResult, error) {
	sealed := HostedResult{Checkpoint: checkpoint}
	if checkpoint.Mode != "hosted" || checkpoint.SchemaVersion != SchemaVersion || !commitPattern.MatchString(checkpoint.Contract.BaseSHA) {
		return sealed, fmt.Errorf("a hosted checkpoint is required")
	}
	result, digest, err := readHostedAuthorReport(filepath.Join(dir, "report.json"))
	if err != nil {
		return sealed, err
	}
	sealed.Outcome, sealed.Summary = result.Status, result.Summary
	sealed.ReportSHA256 = digest
	if result.Status == "author-reported-fixed" {
		data, err := ReadHostedFile(filepath.Join(dir, "candidate.json"), MaxHostedCandidateBytes)
		if err != nil {
			return sealed, err
		}
		var candidate struct {
			Version int    `json:"version"`
			BaseSHA string `json:"baseSha"`
			Files   []struct {
				Path string `json:"path"`
			} `json:"files"`
		}
		if json.Unmarshal(data, &candidate) != nil || candidate.Version != 1 || candidate.BaseSHA != checkpoint.Contract.BaseSHA || len(candidate.Files) == 0 {
			return sealed, fmt.Errorf("the candidate package does not match the task base")
		}
		paths := make([]string, len(candidate.Files))
		for i, file := range candidate.Files {
			paths[i] = file.Path
		}
		if len(CheckPaths(paths, checkpoint.Contract.ScopePaths, false)) != 0 {
			return sealed, fmt.Errorf("the hosted candidate changes paths outside the approved scope")
		}
		hash := sha256.Sum256(data)
		sealed.CandidateSHA256 = hex.EncodeToString(hash[:])
	}
	return sealed, nil
}

func LoadHostedResult(dir string) (HostedResult, error) {
	var result HostedResult
	data, err := ReadHostedFile(filepath.Join(dir, "hosted-result.json"), maxHostedReportBytes)
	if err != nil {
		return result, err
	}
	if err := DecodeJSON(data, &result); err != nil {
		return result, err
	}
	sealed, err := SealHostedResult(result.Checkpoint, dir)
	if err != nil {
		return result, err
	}
	if sealed.CandidateSHA256 != result.CandidateSHA256 || sealed.ReportSHA256 != result.ReportSHA256 {
		return result, fmt.Errorf("hosted package or author report differs from the sealed result")
	}
	if result.Signature != nil && (result.Signature.Validate() != nil || result.Signature.Line() == "") {
		return result, fmt.Errorf("invalid hosted result signature")
	}
	sealed.Signature = result.Signature
	return sealed, nil
}
