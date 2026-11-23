package fixer

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
)

const SchemaVersion = 1

type Seed struct {
	TaskID       string   `json:"taskId"`
	Repository   string   `json:"repository"`
	IssueNumber  int64    `json:"issueNumber"`
	BaseSHA      string   `json:"baseSha"`
	TrustedSHA   string   `json:"trustedSha"`
	Requirements string   `json:"requirements"`
	ScopePaths   []string `json:"scopePaths"`
}

type Contract struct {
	Version            int      `json:"version"`
	BaseSHA            string   `json:"baseSha"`
	TrustedSHA         string   `json:"trustedSha"`
	Requirements       string   `json:"requirements,omitempty"`
	RequirementsSHA256 string   `json:"requirementsSha256,omitempty"`
	ScopePaths         []string `json:"scopePaths"`
	AllowCIChanges     bool     `json:"allowCiChanges"`
	Criteria           []string `json:"criteria"`
}

type Attempt struct {
	ID      string `json:"id"`
	Number  int    `json:"number"`
	BaseSHA string `json:"baseSha"`
}
type RunIdentity struct {
	RunID      int64 `json:"runId"`
	RunAttempt int   `json:"runAttempt"`
}
type Execution struct {
	ID              string       `json:"id"`
	Mode            string       `json:"mode,omitempty"`
	AttemptID       string       `json:"attemptId"`
	Generation      int          `json:"generation"`
	ContractVersion int          `json:"contractVersion"`
	TrustedSHA      string       `json:"trustedSha"`
	Stage           string       `json:"stage"`
	Status          string       `json:"status"`
	Run             *RunIdentity `json:"run"`
}
type WorkItem struct {
	ID           string   `json:"id"`
	ParentID     string   `json:"parentId"`
	Role         string   `json:"role"`
	Dependencies []string `json:"dependencies"`
	ScopePaths   []string `json:"scopePaths"`
	ResultRefs   []string `json:"resultRefs"`
}
type Answer struct {
	Actor  string `json:"actor"`
	Text   string `json:"text"`
	Source string `json:"source"`
}
type Decision struct {
	ID              string             `json:"id"`
	Version         int                `json:"version"`
	ContractVersion int                `json:"contractVersion"`
	Reason          string             `json:"reason"`
	Question        string             `json:"question"`
	ResumeCondition string             `json:"resumeCondition"`
	Kind            string             `json:"kind"`
	Status          string             `json:"status"`
	Answer          *Answer            `json:"answer"`
	Basis           string             `json:"basis,omitempty"`
	EvidenceRef     string             `json:"evidenceRef,omitempty"`
	HostedResult    *HostedIssueResult `json:"hostedResult,omitempty"`
}
type StopEpisode struct {
	ID         string `json:"id"`
	DecisionID string `json:"decisionId"`
	Reason     string `json:"reason"`
	Status     string `json:"status"`
}
type EffectPayload struct {
	ContractVersion int    `json:"contractVersion,omitempty"`
	TrustedSHA      string `json:"trustedSha,omitempty"`
	CandidateSHA    string `json:"candidateSha,omitempty"`
	DecisionID      string `json:"decisionId,omitempty"`
}
type EffectResult struct {
	RunID       int64  `json:"runId,omitempty"`
	RunAttempt  int    `json:"runAttempt,omitempty"`
	Conclusion  string `json:"conclusion,omitempty"`
	PullRequest int64  `json:"pullRequest,omitempty"`
	CommentID   int64  `json:"commentId,omitempty"`
}
type Effect struct {
	ID          string        `json:"id"`
	Kind        string        `json:"kind"`
	ExecutionID string        `json:"executionId"`
	Generation  int           `json:"generation"`
	Status      string        `json:"status"`
	Payload     EffectPayload `json:"payload"`
	Result      *EffectResult `json:"result"`
}
type State struct {
	SchemaVersion     int                `json:"schemaVersion"`
	TaskID            string             `json:"taskId"`
	Repository        string             `json:"repository"`
	IssueNumber       int64              `json:"issueNumber"`
	Revision          int                `json:"revision"`
	Phase             string             `json:"phase"`
	Desired           string             `json:"desired"`
	Generation        int                `json:"generation"`
	Initiator         string             `json:"initiator"`
	Mode              string             `json:"mode,omitempty"`
	ControllerSHA     string             `json:"controllerSha,omitempty"`
	Contract          Contract           `json:"contract"`
	Attempt           Attempt            `json:"attempt"`
	Executions        []Execution        `json:"executions"`
	WorkItems         []WorkItem         `json:"workItems"`
	Decisions         []Decision         `json:"decisions"`
	StopEpisodes      []StopEpisode      `json:"stopEpisodes"`
	Effects           []Effect           `json:"effects"`
	Receipts          []string           `json:"receipts"`
	Candidate         *Manifest          `json:"candidate"`
	HostedCandidate   *HostedCandidate   `json:"hostedCandidate,omitempty"`
	HostedPublication *HostedPublication `json:"hostedPublication,omitempty"`
	Outcome           string             `json:"outcome,omitempty"`
	Recovery          string             `json:"recovery,omitempty"`
	Replan            bool               `json:"replan"`
	NoChangeConfirmed bool               `json:"noChangeConfirmed"`
}
type Source struct {
	Kind       string `json:"kind"`
	ID         int64  `json:"id,omitempty"`
	Action     string `json:"action,omitempty"`
	Revision   int64  `json:"revision,omitempty"`
	RunID      int64  `json:"runId,omitempty"`
	RunAttempt int    `json:"runAttempt,omitempty"`
	CallbackID int64  `json:"callbackId,omitempty"`
}
type Event struct {
	Type              string             `json:"type"`
	Actor             string             `json:"actor,omitempty"`
	Source            Source             `json:"source"`
	ExecutionID       string             `json:"executionId,omitempty"`
	Generation        int                `json:"generation,omitempty"`
	ContractVersion   int                `json:"contractVersion,omitempty"`
	EffectID          string             `json:"effectId,omitempty"`
	EffectResult      *EffectResult      `json:"effectResult,omitempty"`
	Result            string             `json:"result,omitempty"`
	Requirements      string             `json:"requirements,omitempty"`
	QuestionID        string             `json:"questionId,omitempty"`
	QuestionVersion   int                `json:"questionVersion,omitempty"`
	Question          string             `json:"question,omitempty"`
	Reason            string             `json:"reason,omitempty"`
	ResumeCondition   string             `json:"resumeCondition,omitempty"`
	Answer            string             `json:"answer,omitempty"`
	Basis             string             `json:"basis,omitempty"`
	EvidenceRef       string             `json:"evidenceRef,omitempty"`
	Manifest          *Manifest          `json:"manifest,omitempty"`
	HostedCandidate   *HostedCandidate   `json:"hostedCandidate,omitempty"`
	HostedIssueResult *HostedIssueResult `json:"hostedIssueResult,omitempty"`
}
type TransitionContext struct {
	Task                Seed
	AllowedActors       string
	IssueUnavailable    bool
	RequirementsCurrent bool
	Mode                string
	WorkflowSHA         string
	TrustedWorkflow     bool
	Internal            bool
	EvidenceVerified    bool
	ExpectedCandidate   *ManifestExpected
}
type Result struct {
	State       *State `json:"state"`
	Disposition string `json:"disposition"`
	Reason      string `json:"reason"`
}
type StateRead struct {
	State  *State
	Status string
	Reason string
}
type Observation struct {
	Status string
	Result *EffectResult
}
type Adapter interface {
	ReadState(context.Context) (StateRead, error)
	WriteState(context.Context, *State) error
	Context(context.Context, *Event) (TransitionContext, error)
	Perform(context.Context, Effect) error
	Observe(context.Context, Effect) (Observation, error)
}

type ManifestRun struct {
	RunID      int64  `json:"runId"`
	RunAttempt int    `json:"runAttempt"`
	Workflow   string `json:"workflow"`
	TrustedSHA string `json:"trustedSha"`
}
type ManifestFile struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
}
type Bundle struct {
	ArtifactID int64  `json:"artifactId"`
	SHA256     string `json:"sha256"`
	Format     string `json:"format"`
}
type Review struct {
	WorkItemID       string `json:"workItemId"`
	Lens             string `json:"lens"`
	CandidateSHA     string `json:"candidateSha"`
	ContextSHA256    string `json:"contextSha256"`
	TestSetSHA256    string `json:"testSetSha256"`
	Verdict          string `json:"verdict"`
	ReportArtifactID int64  `json:"reportArtifactId"`
}
type ScenarioVerification struct {
	ID                    string `json:"id"`
	CriterionID           string `json:"criterionId"`
	Consumer              string `json:"consumer"`
	CandidateSHA          string `json:"candidateSha"`
	TestSetSHA256         string `json:"testSetSha256"`
	Status                string `json:"status"`
	EnvironmentID         string `json:"environmentId"`
	BaselineEnvironmentID string `json:"baselineEnvironmentId"`
	DataAID               string `json:"dataAId"`
	DataBID               string `json:"dataBId"`
	ReportArtifactID      int64  `json:"reportArtifactId"`
}
type ScenarioExpected struct {
	ID          string
	CriterionID string
	Consumer    string
}
type ModuleCheck struct {
	ID               string `json:"id"`
	CandidateSHA     string `json:"candidateSha"`
	TestSetSHA256    string `json:"testSetSha256"`
	Status           string `json:"status"`
	ReportArtifactID int64  `json:"reportArtifactId"`
}
type Verification struct {
	CandidateSHA          string                 `json:"candidateSha"`
	ContractVersion       int                    `json:"contractVersion"`
	Status                string                 `json:"status"`
	EnvironmentID         string                 `json:"environmentId"`
	BaselineEnvironmentID string                 `json:"baselineEnvironmentId"`
	DataAID               string                 `json:"dataAId"`
	DataBID               string                 `json:"dataBId"`
	FullScenarioRepeated  bool                   `json:"fullScenarioRepeated"`
	ReportArtifactID      int64                  `json:"reportArtifactId"`
	Scenarios             []ScenarioVerification `json:"scenarios"`
	Checks                []ModuleCheck          `json:"checks"`
}
type Manifest struct {
	SchemaVersion       int            `json:"schemaVersion"`
	TaskID              string         `json:"taskId"`
	AttemptID           string         `json:"attemptId"`
	ExecutionID         string         `json:"executionId"`
	Generation          int            `json:"generation"`
	ContractVersion     int            `json:"contractVersion"`
	Repository          string         `json:"repository"`
	BaseSHA             string         `json:"baseSha"`
	CandidateSHA        string         `json:"candidateSha"`
	ParentSHA           string         `json:"parentSha"`
	Run                 ManifestRun    `json:"run"`
	Files               []ManifestFile `json:"files"`
	Bundle              Bundle         `json:"bundle"`
	TestSetSHA256       string         `json:"testSetSha256"`
	ContextSHA256       string         `json:"contextSha256"`
	ProcessReportSHA256 string         `json:"processReportSha256"`
	Reviews             []Review       `json:"reviews"`
	Verification        Verification   `json:"verification"`
}
type ManifestExpected struct {
	TaskID, AttemptID, ExecutionID, Repository, BaseSHA, CandidateSHA, ParentSHA, TestSetSHA256 string
	Generation, ContractVersion                                                                 int
	Run                                                                                         ManifestRun
	ScopePaths                                                                                  []string
	AllowCIChanges                                                                              bool
	ContextSHA256, ProcessReportSHA256, EnvironmentID                                           string
	RequiredReviewLenses, RequiredChecks                                                        []string
	RequiredScenarios                                                                           []ScenarioExpected
}

func cloneState(state *State) *State {
	if state == nil {
		return nil
	}
	data, _ := json.Marshal(state)
	var copy State
	_ = json.Unmarshal(data, &copy)
	return &copy
}

func DecodeJSON(data []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return fmt.Errorf("expected one JSON value")
	}
	return nil
}
