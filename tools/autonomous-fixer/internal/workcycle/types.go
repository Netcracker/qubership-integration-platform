package workcycle

import "context"

type Stage string

const (
	ContractStage  Stage = "contract"
	ReproduceStage Stage = "reproduce"
	ImplementStage Stage = "implement"
	ReviewStage    Stage = "review"
	VerifyStage    Stage = "verify"
)

type Coverage struct {
	CriterionID string `json:"criterion_id"`
	Consumer    string `json:"consumer"`
}

type Request struct {
	ManagedChecks         []Check                `json:"managed_checks,omitempty"`
	DocumentationEntry    *DocumentationEntry    `json:"documentation_entry,omitempty"`
	Kind                  string                 `json:"kind,omitempty"`
	RequiredCoverage      []Coverage             `json:"required_coverage"`
	VerificationScenarios []VerificationScenario `json:"verification_scenarios,omitempty"`
	TaskID                string                 `json:"task_id"`
	Task                  string                 `json:"task"`
	Context               string                 `json:"context"`
	RequiredCriteria      []string               `json:"required_criteria"`
	RequiredChecks        []Check                `json:"required_checks"`
	RequiredConsumers     []string               `json:"required_consumers"`
	EnvironmentID         string                 `json:"environment_id"`
}
type Snapshot struct {
	FileHashes   map[string]string `json:"file_hashes,omitempty"`
	ID           string            `json:"id"`
	ChangedPaths []string          `json:"changed_paths"`
}
type Scenario struct {
	ID          string `json:"id"`
	CriterionID string `json:"criterion_id"`
	Consumer    string `json:"consumer"`
	Behavior    string `json:"behavior"`
	Procedure   string `json:"procedure"`
	Baseline    string `json:"baseline"`
}
type Check struct {
	ID    string `json:"id"`
	Scope string `json:"scope"`
}
type Contract struct {
	Scenarios []Scenario `json:"scenarios"`
	Checks    []Check    `json:"checks"`
	Risks     []string   `json:"risks"`
}
type CompatibilityRegression struct {
	Scenario        Scenario `json:"scenario"`
	BaselineResult  Result   `json:"baseline_result"`
	CandidateResult Result   `json:"candidate_result"`
}

type Finding struct {
	Compatibility *CompatibilityRegression `json:"compatibility,omitempty"`
	Location      string                   `json:"location"`
	ScenarioID    string                   `json:"scenario_id"`
	ID            string                   `json:"id"`
	Description   string                   `json:"description"`
	Evidence      []string                 `json:"evidence"`
}
type Question struct {
	Kind     string   `json:"kind"`
	Text     string   `json:"text"`
	Evidence []string `json:"evidence"`
	Needed   string   `json:"needed"`
}
type Result struct {
	ObservationIDs []string `json:"observation_ids,omitempty"`
	ProductTests   []string `json:"product_tests,omitempty"`
	ID             string   `json:"id"`
	Outcome        string   `json:"outcome"`
	Evidence       []string `json:"evidence"`
}
type Response struct {
	Narrative     string                 `json:"narrative,omitempty"`
	Assessment    *AgentAssessment       `json:"assessment,omitempty"`
	Handoff       *Handoff               `json:"handoff,omitempty"`
	Documentation *DocumentationDecision `json:"documentation,omitempty"`
	CandidateID   string                 `json:"candidate_id"`
	Contract      *Contract              `json:"contract,omitempty"`
	Findings      []Finding              `json:"findings,omitempty"`
	Question      *Question              `json:"question,omitempty"`
	Scenarios     []Result               `json:"scenarios,omitempty"`
	Checks        []Result               `json:"checks,omitempty"`
	EnvironmentID string                 `json:"environment_id,omitempty"`
	DataID        string                 `json:"data_id,omitempty"`
	Evidence      []string               `json:"evidence,omitempty"`
}

// Invocation contains stage inputs and measured results from earlier author stages.
type Invocation struct {
	PriorVerificationDataIDs []string               `json:"prior_verification_data_ids,omitempty"`
	Handoff                  []StageHandoff         `json:"handoff,omitempty"`
	StaticChecks             []Result               `json:"static_checks,omitempty"`
	ReviewDelta              *ReviewDelta           `json:"review_delta,omitempty"`
	PendingQuestion          *Question              `json:"pending_question,omitempty"`
	Routing                  []RoutingAdvice        `json:"routing,omitempty"`
	Documentation            *DocumentationDecision `json:"documentation,omitempty"`
	Request                  Request                `json:"request"`
	Stage                    Stage                  `json:"stage"`
	Contract                 *Contract              `json:"contract,omitempty"`
	Candidate                Snapshot               `json:"candidate"`
	Lens                     string                 `json:"lens,omitempty"`
	Findings                 []Finding              `json:"findings,omitempty"`
	BaselineDataID           string                 `json:"baseline_data_id,omitempty"`
}
type Observation struct {
	Stage       Stage    `json:"stage"`
	Lens        string   `json:"lens,omitempty"`
	Before      Snapshot `json:"before"`
	After       Snapshot `json:"after"`
	Declaration Response `json:"declaration"`
}
type ContractAdjustment struct {
	Kind     string   `json:"kind"`
	ID       string   `json:"id"`
	Before   string   `json:"before"`
	After    string   `json:"after"`
	Evidence []string `json:"evidence,omitempty"`
}

type Report struct {
	Warnings            []string               `json:"warnings,omitempty"`
	Workflow            string                 `json:"workflow,omitempty"`
	Summary             string                 `json:"summary,omitempty"`
	StaticCheckRuns     []StaticCheckRun       `json:"static_check_runs,omitempty"`
	ReviewDecisions     []ReviewDecision       `json:"review_decisions,omitempty"`
	ContractAdjustments []ContractAdjustment   `json:"contract_adjustments,omitempty"`
	Routing             []RoutingAdvice        `json:"routing,omitempty"`
	Documentation       *DocumentationDecision `json:"documentation,omitempty"`
	Request             Request                `json:"request"`
	Status              string                 `json:"status"`
	Contract            *Contract              `json:"contract,omitempty"`
	Candidate           Snapshot               `json:"candidate"`
	Observations        []Observation          `json:"observations"`
	Question            *Question              `json:"question,omitempty"`
	Error               string                 `json:"error,omitempty"`
}

type CommandRecipe struct {
	Purpose          string   `json:"purpose"`
	Argv             []string `json:"argv"`
	WorkingDirectory string   `json:"working_directory"`
	EnvironmentKeys  []string `json:"environment_keys"`
}

type Handoff struct {
	Recipes    []CommandRecipe `json:"recipes,omitempty"`
	Summary    string          `json:"summary"`
	Commands   []string        `json:"commands"`
	Deployment string          `json:"deployment"`
	Evidence   []string        `json:"evidence"`
}

type StageHandoff struct {
	Stage         Stage    `json:"stage"`
	CandidateID   string   `json:"candidate_id"`
	EnvironmentID string   `json:"environment_id,omitempty"`
	DataID        string   `json:"data_id,omitempty"`
	Results       []Result `json:"results,omitempty"`
	Evidence      []string `json:"evidence,omitempty"`
	Details       *Handoff `json:"details,omitempty"`
}

type StaticCheckRun struct {
	CandidateID string   `json:"candidate_id"`
	Results     []Result `json:"results"`
	Reused      []string `json:"reused,omitempty"`
}

type StaticChecker interface {
	StaticChecks(context.Context, Snapshot) (StaticCheckRun, error)
}

type ReviewDelta struct {
	PreviousCandidateID string    `json:"previous_candidate_id"`
	ChangedPaths        []string  `json:"changed_paths"`
	Findings            []Finding `json:"findings,omitempty"`
}

type ReviewDecision struct {
	CandidateID string `json:"candidate_id"`
	Lens        string `json:"lens"`
	ReusedFrom  string `json:"reused_from,omitempty"`
	Reason      string `json:"reason"`
}

// Driver validates artifact references before returning declarations from Run.
type Driver interface {
	Snapshot(context.Context) (Snapshot, error)
	Run(context.Context, Invocation) (Response, error)
	Save(context.Context, Report) error
}

type DocumentationDecision struct {
	Decision         string     `json:"decision"`
	Reason           string     `json:"reason"`
	Summary          string     `json:"summary"`
	Claims           []DocClaim `json:"claims"`
	SeparateFindings []DocClaim `json:"separate_findings,omitempty"`
	PRTitle          string     `json:"pr_title,omitempty"`
	PRBody           string     `json:"pr_body,omitempty"`
	IssueComment     string     `json:"issue_comment,omitempty"`
}

type DocumentationEntry struct {
	Route    string                 `json:"route"`
	Decision *DocumentationDecision `json:"decision,omitempty"`
	Evidence []string               `json:"evidence,omitempty"`
}

type DocClaim struct {
	ID                    string   `json:"id"`
	Behavior              string   `json:"behavior"`
	Attribution           string   `json:"attribution"`
	NeedsChange           bool     `json:"needs_change"`
	SourceEvidence        []string `json:"source_evidence"`
	DocumentationEvidence []string `json:"documentation_evidence"`
	Action                string   `json:"action"`
}

// Successful reports that the engineering or documentation task reached its intended outcome.
func (r Report) Successful() bool {
	return r.Status == "verified" || r.Status == "docs-ready-for-pr" || r.Status == "docs-no-change"
}
