package workcycle

import "context"

const AuthorStage Stage = "author"
const VerificationReassessmentStage Stage = "verify-reassessment"

type AgentAuthorRequest struct {
	Request   Request         `json:"request"`
	Candidate Snapshot        `json:"candidate"`
	Feedback  []string        `json:"feedback,omitempty"`
	Routing   []RoutingAdvice `json:"routing,omitempty"`
}

type AgentAuthorResult struct {
	Text     string   `json:"text"`
	Evidence []string `json:"evidence"`
}

type AgentAssessmentRequest struct {
	PreviousVerification *AgentAssessment      `json:"previous_verification,omitempty"`
	VerificationRecheck  []VerificationRecheck `json:"verification_recheck,omitempty"`
	VerificationToken    string                `json:"verification_token,omitempty"`
	ManagedResults       []Result              `json:"managed_results,omitempty"`
	Request              Request               `json:"request"`
	Candidate            Snapshot              `json:"candidate"`
	Stage                Stage                 `json:"stage"`
	Lens                 string                `json:"lens,omitempty"`
	Evidence             []string              `json:"evidence"`
	Routing              []RoutingAdvice       `json:"routing,omitempty"`
	ReviewDelta          *AgentReviewDelta     `json:"review_delta,omitempty"`
	CodeOnly             bool                  `json:"code_only,omitempty"`
}

type AgentReviewDelta struct {
	PreviousCandidateID string   `json:"previous_candidate_id"`
	ChangedPaths        []string `json:"changed_paths,omitempty"`
	ChangedPathsKnown   bool     `json:"changed_paths_known"`
	Findings            []string `json:"findings,omitempty"`
}

// Assessment is the independent checker's disposition, not an author's work report.
type AgentAssessment struct {
	Reassessment      *AgentAssessment     `json:"reassessment,omitempty"`
	CoverageValidated bool                 `json:"coverage_validated,omitempty"`
	CoverageAudit     *CoverageAudit       `json:"coverage_audit,omitempty"`
	Decision          string               `json:"decision"`
	Summary           string               `json:"summary"`
	Findings          []string             `json:"findings"`
	Evidence          []string             `json:"evidence,omitempty"`
	Coverage          []VerificationResult `json:"coverage,omitempty"`
}

type VerificationRecheck struct {
	CaseID   string `json:"case_id"`
	Observed string `json:"previous_observation"`
}

type CoverageAudit struct {
	Mode        string            `json:"mode"`
	Assessments map[string]string `json:"assessments,omitempty"`
	Evidence    string            `json:"evidence,omitempty"`
	Error       string            `json:"error,omitempty"`
	Recheck     *AgentAssessment  `json:"recheck,omitempty"`
}

type AgentDriver interface {
	Snapshot(context.Context) (Snapshot, error)
	Author(context.Context, AgentAuthorRequest) (AgentAuthorResult, error)
	Assess(context.Context, AgentAssessmentRequest) (AgentAssessment, error)
	Save(context.Context, Report) error
}

type AgentReviewBatcher interface {
	ReviewParallelism() int
	AssessReviewBatch(context.Context, []AgentAssessmentRequest) ([]AgentAssessment, error)
}
