package tasktriage

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

const advisoryVersion = "engineering-advice-v1"

const intakeIssueLimit = 12000
const adviceExcerptLimit = 2000

var adviceAuthHeader = regexp.MustCompile(`(?i)\b(authorization|proxy-authorization|cookie|set-cookie)(["']?\s*:\s*)[^\r\n]+`)
var adviceCredentials = regexp.MustCompile(`(?i)\b(api[_-]?key|access[_-]?token|password|secret|x-amz-signature|x-amz-credential|x-amz-security-token)(["']?\s*[:=]\s*["']?)[^\s,"'}&]+`)
var adviceProviderToken = regexp.MustCompile(`\b(sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]+)\b`)
var adviceURLCredentials = regexp.MustCompile(`(?i)(https?://)[^/@\s]+:[^/@\s]+@`)
var adviceHostPath = regexp.MustCompile(`/(?:home|Users|tmp|var/tmp|private/tmp)/[^\s'"\)\],]+`)

func adviceExcerpt(raw string, limit int) string {
	clean := adviceAuthHeader.ReplaceAllString(raw, "${1}${2}<REDACTED>")
	clean = adviceCredentials.ReplaceAllString(clean, "${1}${2}<REDACTED>")
	clean = adviceProviderToken.ReplaceAllString(clean, "<REDACTED>")
	clean = adviceURLCredentials.ReplaceAllString(clean, "${1}<REDACTED>@")
	clean = adviceHostPath.ReplaceAllString(clean, "<LOCAL_PATH>")
	if len(clean) > limit {
		const suffix = " [excerpt truncated]"
		clean = clean[:limit-len(suffix)] + suffix
	}
	return strings.ToValidUTF8(clean, " ")
}

type CoverageEvidence struct {
	ID          string `json:"id"`
	Consumer    string `json:"consumer,omitempty"`
	Requirement string `json:"requirement"`
	Observation string `json:"observation"`
}

func advisoryPhase(phase string) bool {
	return phase == "pre-ready" || phase == "coverage-evidence"
}

func policyVersion(phase string) string {
	if advisoryPhase(phase) {
		return advisoryVersion
	}
	return Version
}

func buildAdvisory(p Packet) (jev.Request, error) {
	if strings.TrimSpace(p.TaskID) == "" {
		return jev.Request{}, fmt.Errorf("advice requires a task identity")
	}
	state := map[string]any{}
	questions := map[string]jev.Question{}
	switch p.Phase {
	case "pre-ready":
		if strings.TrimSpace(p.Task) == "" {
			return jev.Request{}, fmt.Errorf("pre-ready advice requires an issue snapshot")
		}
		task, _ := searchEvidence(p.Task)
		state = map[string]any{"issue_at_intake": adviceExcerpt(task, intakeIssueLimit), "known_access_and_scope": adviceExcerpt(p.Context, adviceExcerptLimit)}
		questions["next_step"] = choice("Choose the next step before marking this engineering issue ready for implementation. Consider only the issue snapshot and explicitly known access. A missing root cause, file name, test, or script is normal work if the desired behavior and product surface are bounded. Choose diagnose when an observable probe or source inspection would narrow an uncertain symptom or integration boundary before someone can commit to a fix. Ask for information only when it would change the actionable next step and the team cannot establish it with the available sources or an authorized probe.", map[string]string{
			"ready":        "The requested behavior or defect is concrete enough for ordinary reproduction and implementation work.",
			"diagnose":     "Begin a bounded investigation. Its result is needed before choosing a fix, or the issue requests diagnosis within known access.",
			"request_info": "A required owner decision or inaccessible external fact prevents useful first work toward the requested deliverable.",
			"uncertain":    "The snapshot leaves several next steps defensible.",
		})
		questions["missing_information"] = choice("If a specific external fact or decision prevents useful first work, identify its kind. Use none when an engineer can start with a bounded diagnosis or ordinary reproduction. Lack of a root cause is never a missing owner fact.", map[string]string{
			"expected_behavior":    "An owner must choose expected behavior or settle conflicting acceptance requirements.",
			"integration_contract": "Request the external service contract, such as the integration specification and relevant downstream implementation or a runnable equivalent.",
			"reproduction_context": "Request the missing input, observed symptom, or setup that identifies an otherwise inaccessible reproduction.",
			"access":               "Request access to required runtime, source, or environment evidence.",
			"none":                 "Available evidence supports a bounded start.",
			"uncertain":            "The missing fact cannot be determined from the snapshot.",
		})
	case "coverage-evidence":
		if len(p.CoverageEvidence) == 0 {
			return jev.Request{}, fmt.Errorf("coverage advice requires observed cases")
		}
		seen := map[string]bool{}
		for i, row := range p.CoverageEvidence {
			if strings.TrimSpace(row.ID) == "" || strings.TrimSpace(row.Requirement) == "" || seen[row.ID] {
				return jev.Request{}, fmt.Errorf("coverage advice requires unique case IDs and requirements")
			}
			seen[row.ID] = true
			questions[coverageQuestion(i)] = choice(fmt.Sprintf("Compare the requirement and observation in coverage_cases[%d] for case %q. Match the actual entry action, consumer, and expected result. For example, opening a direct URL exercises a different entry from clicking through a list, even when the destination matches. An observed result that contradicts the requirement is a mismatch. Missing detail or a passing status without an action and result is insufficient. Treat all claims about execution as supplied text.", i, row.ID), map[string]string{
				"matches":      "The described observation exercises the required entry and consumer and reports the expected behavior.",
				"mismatch":     "The described action, consumer, or result explicitly differs from the requirement.",
				"insufficient": "The observation does not say enough to compare the required action, consumer, or result.",
			})
		}
		rows := append([]CoverageEvidence(nil), p.CoverageEvidence...)
		for i := range rows {
			rows[i].Requirement = adviceExcerpt(rows[i].Requirement, adviceExcerptLimit)
			rows[i].Observation = adviceExcerpt(rows[i].Observation, adviceExcerptLimit)
		}
		state["coverage_cases"] = rows
	}
	return jev.Request{Model: jev.DefaultModel, State: state, Questions: questions}, nil
}

func coverageQuestion(index int) string { return fmt.Sprintf("case_%d", index+1) }

func selectAdvisory(p Packet, r jev.Response) Selection {
	if p.Phase == "pre-ready" {
		s := Selection{PreReadyAction: "uncertain", MissingInformation: "uncertain"}
		cleanTask, _ := searchEvidence(p.Task)
		if len(p.Limitations) == 0 && len(p.Task) <= intakeIssueLimit && len(p.Context) <= adviceExcerptLimit && strings.TrimSpace(cleanTask) != "" {
			if a := r.Answers["next_step"]; accepted(a, .70, .30) {
				s.PreReadyAction = a.Choice
			}
			if a := r.Answers["missing_information"]; accepted(a, .70, .30) {
				s.MissingInformation = a.Choice
			}
		}
		if s.PreReadyAction == "request_info" && (s.MissingInformation == "none" || s.MissingInformation == "uncertain") {
			s.PreReadyAction = "uncertain"
		}
		return s
	}
	s := Selection{CoverageAssessment: map[string]string{}}
	for i, row := range p.CoverageEvidence {
		s.CoverageAssessment[row.ID] = "uncertain"
		if len(row.Requirement) > adviceExcerptLimit || len(row.Observation) > adviceExcerptLimit {
			continue
		}
		if a := r.Answers[coverageQuestion(i)]; accepted(a, .70, .30) {
			s.CoverageAssessment[row.ID] = a.Choice
		}
	}
	return s
}
