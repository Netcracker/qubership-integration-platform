package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"reflect"
	"strconv"
	"strings"
)

type ActionConfig struct {
	Client, Variables                                        *GitHubClient
	Repository, EventName, Actor, TriggeringActor, Ref, Mode string
	Enabled                                                  bool
	ScopePaths                                               []string
	Payload                                                  GitHubPayload
	Inputs                                                   map[string]string
	RunID                                                    int64
	RunAttempt                                               int
	Checkpoint                                               *Checkpoint
	HostedResult                                             *HostedResult
	HostedPublication                                        *HostedPublication
}

func (cfg ActionConfig) IsCompletionEvent() bool {
	return cfg.EventName == "workflow_run" || (cfg.EventName == "workflow_dispatch" && cfg.Inputs["operation"] == "reconcile-run")
}

func (cfg ActionConfig) allowedActors(ctx context.Context) (string, error) {
	enabled, err := cfg.Variables.RepositoryVariable(ctx, "AGENT_ENABLED")
	if err != nil {
		return "", err
	}
	if enabled != "true" {
		return "", fmt.Errorf("AGENT_ENABLED must be true")
	}
	if cfg.Mode == "hosted" {
		mode, err := cfg.Variables.RepositoryVariable(ctx, "AGENT_MODE")
		if err != nil {
			return "", err
		}
		if mode != "hosted" {
			return "", fmt.Errorf("AGENT_MODE must be hosted")
		}
	}
	return cfg.Variables.RepositoryVariable(ctx, "AGENT_ALLOWED_ACTORS")
}

type ActionResult struct {
	Outputs    map[string]string
	Result     *Result
	Checkpoint *Checkpoint
	Dispatch   *DispatchDiagnostic
}
type Checkpoint struct {
	SchemaVersion   int            `json:"schemaVersion"`
	Mode            string         `json:"mode"`
	Repository      string         `json:"repository,omitempty"`
	TaskID          string         `json:"taskId"`
	IssueNumber     int64          `json:"issueNumber"`
	AttemptID       string         `json:"attemptId,omitempty"`
	ExecutionID     string         `json:"executionId"`
	Generation      int            `json:"generation"`
	ContractVersion int            `json:"contractVersion"`
	Run             RunIdentity    `json:"run"`
	TrustedSHA      string         `json:"trustedSha"`
	Contract        Contract       `json:"contract"`
	Answers         []HostedAnswer `json:"answers,omitempty"`
	RevisionSHA256  string         `json:"revisionSha256,omitempty"`
}
type actionSession struct {
	cfg       ActionConfig
	issue     Issue
	repo      Repository
	adapter   *GitHubAdapter
	state     *State
	execution *Execution
	run       *WorkflowRun
	bot       GitHubUser
}

func PositiveID(value string) (int64, error) {
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil || n <= 0 || strconv.FormatInt(n, 10) != value {
		return 0, fmt.Errorf("invalid positive identifier")
	}
	return n, nil
}
func actionIssue(cfg ActionConfig) (int64, error) {
	if cfg.EventName == "workflow_run" {
		identity, err := ParseWorkRunName(cfg.Payload.WorkflowRun.DisplayTitle)
		return identity.IssueNumber, err
	}
	if value := cfg.Inputs["issue"]; value != "" {
		return PositiveID(value)
	}
	if value := cfg.Payload.Inputs["issue"]; value != "" {
		return PositiveID(value)
	}
	if cfg.Payload.Issue.Number > 0 {
		return cfg.Payload.Issue.Number, nil
	}
	return 0, fmt.Errorf("issue number is missing")
}
func loadAction(ctx context.Context, cfg ActionConfig) (*actionSession, error) {
	if !cfg.Enabled || !validExecutionMode(cfg.Mode) {
		return nil, fmt.Errorf("AGENT_ENABLED must be true and AGENT_MODE must be fake or hosted")
	}
	if cfg.Client == nil || cfg.Variables == nil || cfg.Payload.Repository.FullName != cfg.Repository {
		return nil, fmt.Errorf("repository context does not match")
	}
	issueNumber, err := actionIssue(cfg)
	if err != nil {
		return nil, err
	}
	session := &actionSession{cfg: cfg}
	if err := cfg.Client.Request(ctx, "GET", "", nil, &session.repo); err != nil {
		return nil, err
	}
	if session.repo.FullName != cfg.Repository || cfg.Ref != "refs/heads/"+session.repo.DefaultBranch {
		return nil, fmt.Errorf("execution must use the repository default branch")
	}
	if err := cfg.Client.Request(ctx, "GET", fmt.Sprintf("/issues/%d", issueNumber), nil, &session.issue); err != nil {
		return nil, err
	}
	if session.issue.Number != issueNumber || (len(session.issue.PullRequest) > 0 && string(session.issue.PullRequest) != "null") {
		return nil, fmt.Errorf("target is not an issue")
	}
	authors, err := cfg.Client.EngineeringAuthors(ctx)
	if err != nil {
		return nil, err
	}
	session.bot = authors.Actions
	store := NewEngineeringCommentStore(cfg.Client, cfg.Repository, issueNumber, authors, fmt.Sprintf("qip-%d", issueNumber))
	observed, err := store.ReadState(ctx)
	if err != nil {
		return nil, err
	}
	if observed.Status != "ok" {
		return nil, fmt.Errorf("canonical state requires recovery: %s", observed.Reason)
	}
	session.state = observed.State
	scopes := cfg.ScopePaths
	if observed.State != nil {
		scopes = observed.State.Contract.ScopePaths
	}
	if len(scopes) == 0 || len(CheckPaths(scopes, scopes, false)) != 0 {
		return nil, fmt.Errorf("AGENT_SCOPE_PATHS must name permitted product paths")
	}
	var commit struct {
		SHA string `json:"sha"`
	}
	if err := cfg.Client.Request(ctx, "GET", "/commits/"+url.PathEscape(session.repo.DefaultBranch), nil, &commit); err != nil {
		return nil, err
	}
	if !commitPattern.MatchString(commit.SHA) {
		return nil, fmt.Errorf("invalid trusted commit")
	}
	var workflow struct {
		ID   int64  `json:"id"`
		Path string `json:"path"`
	}
	if err := cfg.Client.Request(ctx, "GET", "/actions/workflows/agent-work.yaml", nil, &workflow); err != nil {
		return nil, err
	}
	if workflow.ID <= 0 || workflow.Path != ".github/workflows/agent-work.yaml" {
		return nil, fmt.Errorf("worker workflow identity does not match")
	}
	task := Seed{TaskID: fmt.Sprintf("qip-%d", issueNumber), Repository: cfg.Repository, IssueNumber: issueNumber, BaseSHA: commit.SHA, TrustedSHA: commit.SHA, Requirements: session.issue.Requirements(), ScopePaths: scopes}
	session.adapter = &GitHubAdapter{Client: cfg.Client, Store: store, Task: task, DefaultBranch: session.repo.DefaultBranch, WorkflowID: workflow.ID, AllowedActors: cfg.allowedActors, Mode: cfg.Mode, state: session.state}
	return session, nil
}
func (s *actionSession) worker(ctx context.Context, callback bool) error {
	var executionID string
	var generation, version int
	var runID int64
	var attempt int
	if callback {
		identity, err := ParseWorkRunName(s.cfg.Payload.WorkflowRun.DisplayTitle)
		if err != nil {
			return err
		}
		executionID, generation, version = identity.ExecutionID, identity.Generation, identity.ContractVersion
		runID, attempt = s.cfg.Payload.WorkflowRun.ID, s.cfg.Payload.WorkflowRun.RunAttempt
	} else {
		executionID = s.cfg.Inputs["execution"]
		g, err := PositiveID(s.cfg.Inputs["generation"])
		if err != nil {
			return err
		}
		v, err := PositiveID(s.cfg.Inputs["contract_version"])
		if err != nil {
			return err
		}
		generation, version = int(g), int(v)
		runID, attempt = s.cfg.RunID, s.cfg.RunAttempt
	}
	if runID <= 0 || attempt <= 0 {
		return fmt.Errorf("run identity is missing")
	}
	execution, err := s.adapter.execution(executionID)
	if err != nil {
		return err
	}
	if execution.Generation != generation || execution.ContractVersion != version {
		return fmt.Errorf("worker versions do not match the dispatch")
	}
	var run WorkflowRun
	if err := s.cfg.Client.Request(ctx, "GET", fmt.Sprintf("/actions/runs/%d/attempts/%d", runID, attempt), nil, &run); err != nil {
		return err
	}
	expected := s.adapter.expected(*execution)
	expected.RunID = runID
	expected.RunAttempt = attempt
	if !MatchWorkerRun(run, expected) {
		return fmt.Errorf("worker provenance does not match")
	}
	if !callback && s.cfg.Inputs["trusted_sha"] != expected.TrustedSHA {
		return fmt.Errorf("worker input commit does not match")
	}
	if !callback && (executionMode(execution.Mode) != executionMode(s.cfg.Inputs["mode"]) || executionMode(execution.Mode) != executionMode(s.cfg.Mode)) {
		return fmt.Errorf("worker mode does not match the dispatch and repository configuration")
	}
	if callback && (run.Status != "completed" || run.Conclusion != s.cfg.Payload.WorkflowRun.Conclusion) {
		return fmt.Errorf("worker completion is not confirmed")
	}
	s.execution, s.run = execution, &run
	return nil
}
func (s *actionSession) callback(kind string, id int64) Event {
	return Event{Type: kind, Source: Source{Kind: "workflow", RunID: s.run.ID, RunAttempt: s.run.RunAttempt, CallbackID: id}, ExecutionID: s.execution.ID, Generation: s.execution.Generation, ContractVersion: s.execution.ContractVersion}
}
func (s *actionSession) currentEvent(ctx context.Context) (*Event, error) {
	payload := s.cfg.Payload
	if s.cfg.EventName == "issue_comment" {
		var current Comment
		if err := s.cfg.Client.Request(ctx, "GET", fmt.Sprintf("/issues/comments/%d", payload.Comment.ID), nil, &current); err != nil {
			return nil, err
		}
		if current.ID != payload.Comment.ID || current.Body != payload.Comment.Body || current.User != payload.Comment.User || current.CreatedAt != current.UpdatedAt {
			return nil, nil
		}
		payload.Issue = s.issue
	}
	if s.cfg.EventName == "issues" && payload.Action == "edited" {
		if payload.Issue.ID != s.issue.ID || payload.Issue.Title != s.issue.Title || !reflect.DeepEqual(payload.Issue.Body, s.issue.Body) {
			return nil, nil
		}
	}
	var labelID int64
	if s.cfg.EventName == "issues" && (payload.Action == "labeled" || payload.Action == "unlabeled") {
		if payload.Label.Name != "ready-for-agent" {
			return nil, nil
		}
		raw, err := s.cfg.Client.List(ctx, fmt.Sprintf("/issues/%d/events", s.issue.Number), "")
		if err != nil {
			return nil, err
		}
		var laterRemoval int64
		for _, data := range raw {
			var event struct {
				ID        int64      `json:"id"`
				Event     string     `json:"event"`
				Actor     GitHubUser `json:"actor"`
				Label     Label      `json:"label"`
				CreatedAt string     `json:"created_at"`
			}
			if err := json.Unmarshal(data, &event); err != nil {
				return nil, err
			}
			if event.Label.Name != "ready-for-agent" {
				continue
			}
			if event.Event == "unlabeled" && event.ID > laterRemoval {
				laterRemoval = event.ID
			}
			if event.Event == payload.Action && event.Actor.ID == payload.Sender.ID && event.CreatedAt == payload.Issue.UpdatedAt {
				if labelID != 0 {
					return nil, fmt.Errorf("label event identity is ambiguous")
				}
				labelID = event.ID
			}
		}
		if labelID == 0 {
			return nil, fmt.Errorf("label event identity is unavailable")
		}
		if payload.Action == "labeled" && s.state == nil && laterRemoval > labelID {
			return nil, nil
		}
	}
	return DecodeGitHubEvent(s.cfg.EventName, payload, s.cfg.Repository, s.state, labelID), nil
}
func (s *actionSession) validateCheckpoint(checkpoint *Checkpoint) error {
	if checkpoint == nil {
		return fmt.Errorf("checkpoint is missing")
	}
	if checkpoint.SchemaVersion != SchemaVersion || checkpoint.Mode != executionMode(s.execution.Mode) || checkpoint.TaskID != s.state.TaskID || checkpoint.IssueNumber != s.issue.Number ||
		checkpoint.ExecutionID != s.execution.ID || checkpoint.Generation != s.execution.Generation || checkpoint.ContractVersion != s.execution.ContractVersion ||
		checkpoint.Run != (RunIdentity{RunID: s.run.ID, RunAttempt: s.run.RunAttempt}) || checkpoint.TrustedSHA != s.run.HeadSHA || !reflect.DeepEqual(checkpoint.Contract, s.state.Contract) {
		return fmt.Errorf("checkpoint does not match the observed execution")
	}
	if checkpoint.Mode == "hosted" && (checkpoint.Repository != s.state.Repository || checkpoint.AttemptID != s.execution.AttemptID || !reflect.DeepEqual(checkpoint.Answers, hostedAnswers(s.state))) {
		return fmt.Errorf("hosted checkpoint does not match the attempt")
	}
	return nil
}

func (s *actionSession) artifact(ctx context.Context, run WorkflowRun, name string) (int64, error) {
	raw, err := s.cfg.Client.List(ctx, fmt.Sprintf("/actions/runs/%d/artifacts", run.ID), "artifacts")
	if err != nil {
		return 0, err
	}
	var id int64
	for _, data := range raw {
		var artifact struct {
			ID          int64  `json:"id"`
			Name        string `json:"name"`
			Expired     bool   `json:"expired"`
			WorkflowRun struct {
				ID      int64  `json:"id"`
				HeadSHA string `json:"head_sha"`
			} `json:"workflow_run"`
		}
		if err := json.Unmarshal(data, &artifact); err != nil {
			return 0, err
		}
		if artifact.Name == name {
			if id != 0 || artifact.ID <= 0 || artifact.Expired || artifact.WorkflowRun.ID != run.ID || artifact.WorkflowRun.HeadSHA != run.HeadSHA {
				return 0, fmt.Errorf("worker artifact provenance does not match")
			}
			id = artifact.ID
		}
	}
	if id == 0 {
		return 0, fmt.Errorf("expected one worker artifact")
	}
	return id, nil
}

func (s *actionSession) workerArtifactName() string {
	if s.execution.Mode == "hosted" {
		return hostedArtifactName(RunIdentity{RunID: s.run.ID, RunAttempt: s.run.RunAttempt})
	}
	return fmt.Sprintf("agent-checkpoint-%d-%d", s.run.ID, s.run.RunAttempt)
}

func (s *actionSession) activeWorker() bool {
	e, st := s.execution, s.state
	return e.Run != nil && e.Run.RunID == s.run.ID && e.Run.RunAttempt == s.run.RunAttempt && e.Status == "running" && st.Desired == "running" && e.Generation == st.Generation && e.ContractVersion == st.Contract.Version
}

func RunAction(ctx context.Context, mode string, cfg ActionConfig) (ActionResult, error) {
	output := ActionResult{Outputs: map[string]string{"process": "false", "download": "false", "admitted": "false"}}
	session, err := loadAction(ctx, cfg)
	if err != nil {
		return output, err
	}
	output.Outputs["issue"] = strconv.FormatInt(session.issue.Number, 10)
	controller := NewController(session.adapter)
	switch mode {
	case "route":
		if cfg.IsCompletionEvent() {
			if err := session.completion(ctx); err != nil {
				return output, err
			}
			output.Outputs["run_id"] = strconv.FormatInt(session.run.ID, 10)
			output.Outputs["run_attempt"] = strconv.Itoa(session.run.RunAttempt)
			if session.activeWorker() && session.run.Conclusion == "success" {
				name := session.workerArtifactName()
				id, err := session.artifact(ctx, *session.run, name)
				if err != nil {
					return output, err
				}
				output.Outputs["artifact_name"] = name
				output.Outputs["artifact_id"] = strconv.FormatInt(id, 10)
				output.Outputs["download"] = "true"
			}
			output.Outputs["process"] = "true"
		} else if cfg.EventName == "workflow_dispatch" {
			actors, err := cfg.allowedActors(ctx)
			if err != nil {
				return output, err
			}
			if Allowed(actors, cfg.Actor, cfg.TriggeringActor) {
				output.Outputs["process"] = "true"
			}
		} else {
			event, err := session.currentEvent(ctx)
			if err != nil {
				return output, err
			}
			if event != nil {
				actors, err := cfg.allowedActors(ctx)
				if err != nil {
					return output, err
				}
				if Allowed(actors, event.Actor) {
					output.Outputs["process"] = "true"
				}
			}
		}
		return output, nil
	case "admit", "work":
		if err := session.worker(ctx, false); err != nil {
			return output, err
		}
		if session.issue.State != "open" {
			return output, fmt.Errorf("issue is not open")
		}
		if mode == "work" {
			e, s := session.execution, session.state
			current, err := session.adapter.Context(ctx, nil)
			if err != nil {
				return output, err
			}
			if current.IssueUnavailable || (e.Mode == "hosted" && !current.RequirementsCurrent) || !Allowed(current.AllowedActors, s.Initiator) || !session.activeWorker() {
				return output, fmt.Errorf("worker admission is no longer valid")
			}
			checkpoint := &Checkpoint{SchemaVersion: SchemaVersion, Mode: executionMode(e.Mode), TaskID: s.TaskID, IssueNumber: s.IssueNumber, ExecutionID: e.ID, Generation: e.Generation, ContractVersion: e.ContractVersion, Run: *e.Run, TrustedSHA: e.TrustedSHA, Contract: s.Contract}
			if e.Mode == "hosted" {
				checkpoint.Repository, checkpoint.AttemptID = s.Repository, e.AttemptID
				checkpoint.Answers = hostedAnswers(s)
			}
			output.Checkpoint = checkpoint
			output.Outputs["base_sha"] = s.Contract.BaseSHA
			return output, nil
		}
		reconciled, err := controller.Reconcile(ctx)
		output.Dispatch = session.adapter.dispatch
		if err != nil {
			return output, err
		}
		if reconciled.Disposition == "recovery-required" {
			output.Result = &reconciled
			return output, nil
		}
		result, err := controller.Handle(ctx, session.callback("run-started", 1))
		output.Result = &result
		if err == nil && result.Disposition == "accepted" {
			output.Outputs["admitted"] = "true"
		}
		return output, err
	case "control":
		var result Result
		if cfg.IsCompletionEvent() {
			if err := session.completion(ctx); err != nil {
				return output, err
			}
			if session.activeWorker() {
				result, err = session.handleWorkerResult(ctx, controller)
				if err != nil {
					return output, err
				}
				if result.Disposition == "recovery-required" {
					output.Result = &result
					return output, nil
				}
			}
			finished := session.callback("run-finished", 3)
			finished.Result = "completed"
			if session.run.Conclusion == "cancelled" {
				finished.Result = "cancelled"
			}
			result, err = controller.Handle(ctx, finished)
		} else if cfg.EventName == "workflow_dispatch" {
			actors, err := cfg.allowedActors(ctx)
			if err != nil {
				return output, err
			}
			if !Allowed(actors, cfg.Actor, cfg.TriggeringActor) {
				return output, fmt.Errorf("actor is not allowed")
			}
			result, err = controller.Reconcile(ctx)
		} else {
			event, eventErr := session.currentEvent(ctx)
			if eventErr != nil {
				return output, eventErr
			}
			if event == nil {
				return output, nil
			}
			if session.issue.State != "open" && event.Type != "stop" && event.Type != "status" && event.Type != "label-removed" {
				return output, fmt.Errorf("issue is not open")
			}
			result, err = controller.Handle(ctx, *event)
		}
		output.Result = &result
		output.Dispatch = session.adapter.dispatch
		if cfg.IsCompletionEvent() && err == nil && result.Disposition == "ignored" && result.State != nil {
			err = session.adapter.repairHostedReport(ctx, RunIdentity{RunID: session.run.ID, RunAttempt: session.run.RunAttempt})
		}
		if err == nil && result.State != nil && result.Disposition != "recovery-required" {
			err = SyncStopLabels(ctx, cfg.Client, session.adapter.Store.authors.Writer(), session.issue.Number, result.State)
		}
		if cfg.IsCompletionEvent() && err == nil && result.State != nil && result.State.Phase == "awaiting-publication" && result.State.HostedCandidate != nil {
			candidate := result.State.HostedCandidate
			if candidate.Run.RunID == session.run.ID && candidate.Run.RunAttempt == session.run.RunAttempt {
				output.Outputs["hosted_candidate"] = "true"
				if cfg.EventName == "workflow_run" || session.completionNotifier() {
					output.Outputs["automatic_publish"] = "true"
				}
				output.Outputs["candidate_run"] = strconv.FormatInt(candidate.Run.RunID, 10)
				output.Outputs["candidate_attempt"] = strconv.Itoa(candidate.Run.RunAttempt)
			}
		}
		return output, err
	case "route-publish", "publish", "confirm-publish":
		if executionMode(cfg.Mode) == "hosted" {
			return session.publishAction(ctx, mode, output)
		}
		if mode == "route-publish" {
			return output, nil
		}
		if mode == "confirm-publish" {
			return output, fmt.Errorf("hosted mode is required to confirm publication")
		}
		actors, err := cfg.allowedActors(ctx)
		if err != nil {
			return output, err
		}
		if !Allowed(actors, cfg.Actor, cfg.TriggeringActor) {
			return output, fmt.Errorf("actor is not allowed")
		}
		result := Result{State: session.state, Disposition: "disabled", Reason: "Publication is disabled in fake mode."}
		output.Result = &result
		return output, nil
	default:
		return output, fmt.Errorf("unknown action mode")
	}
}

func (s *actionSession) handleWorkerResult(ctx context.Context, controller *Controller) (Result, error) {
	decision := s.callback("decision-request", 2)
	decision.QuestionID = fmt.Sprintf("%s/checkpoint/%d", s.execution.ID, s.execution.ContractVersion)
	decision.Reason = "environment-blocked"
	decision.Question = "The worker ended without a confirmed checkpoint. Inspect the run before continuing."
	decision.ResumeCondition = "An allowed user resolves the worker failure or stops the task."
	if s.execution.Mode == "hosted" {
		decision.Question = "The hosted worker produced no usable candidate. Inspect the run and its evidence before continuing."
	}
	if s.run.Conclusion != "success" {
		return controller.Handle(ctx, decision)
	}
	id, err := s.artifact(ctx, *s.run, s.workerArtifactName())
	if err != nil {
		return Result{}, err
	}
	if s.execution.Mode != "hosted" {
		if err := s.validateCheckpoint(s.cfg.Checkpoint); err != nil {
			return Result{}, err
		}
		decision.Reason = "approval-required"
		decision.Question = "The model-free worker saved its checkpoint. Answer to run another model-free cycle, or stop the task."
		decision.ResumeCondition = "An allowed user answers this version of the question."
		return controller.Handle(ctx, decision)
	}
	result := s.cfg.HostedResult
	if result != nil && s.validateCheckpoint(&result.Checkpoint) == nil && result.Outcome == "author-reported-fixed" {
		event := s.callback("hosted-candidate", 4)
		event.HostedCandidate = &HostedCandidate{ExecutionID: s.execution.ID, Generation: s.execution.Generation, ContractVersion: s.execution.ContractVersion,
			Run: *s.execution.Run, ArtifactID: id, CandidateSHA256: result.CandidateSHA256, ReportSHA256: result.ReportSHA256, Signature: result.Signature}
		observed, err := controller.Handle(ctx, event)
		if err != nil || observed.Disposition != "ignored" {
			return observed, err
		}
	}
	if result != nil && s.validateCheckpoint(&result.Checkpoint) == nil && (result.Outcome == "needs-human" || result.Outcome == "blocked") {
		question := strings.TrimSpace(result.Summary)
		if newline := strings.LastIndexByte(question, '\n'); newline >= 0 && strings.HasPrefix(strings.ToLower(strings.TrimSpace(question[newline+1:])), "outcome:") {
			question = strings.TrimSpace(question[:newline])
		}
		if runes := []rune(question); len(runes) > 4000 {
			question = string(runes[:4000]) + "…"
		}
		decision.Question = question
		decision.Reason = "decision-required"
		if result.Outcome == "blocked" {
			decision.Reason = "verification-blocked"
		}
		decision.ResumeCondition = "An allowed user answers this question or stops the task."
		if result.Signature != nil {
			decision.HostedIssueResult = &HostedIssueResult{Run: *s.execution.Run, ArtifactID: id, ReportSHA256: result.ReportSHA256, Signature: *result.Signature}
		}
		return controller.Handle(ctx, decision)
	}
	decision.Reason = "verification-blocked"
	return controller.Handle(ctx, decision)
}

func ParseScopes(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
		if parts[i] == "" {
			return nil
		}
	}
	return parts
}
