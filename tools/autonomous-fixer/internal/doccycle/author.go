package doccycle

import (
	"context"
	"errors"
	"fmt"
	"path"
	"reflect"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

const AuthorStage workcycle.Stage = "docs-author"

func PublicationChecks() []workcycle.Check {
	return []workcycle.Check{
		{ID: "diff_scope", Scope: "help-only patch"},
		{ID: "source_facts", Scope: "changed claims match source"},
		{ID: "structure_links", Scope: "changed page structure and links"},
		{ID: "documentation_index", Scope: "help index build"},
	}
}

func RunAuthor(ctx context.Context, req workcycle.Request, d workcycle.Driver) (report workcycle.Report, err error) {
	report = workcycle.Report{Workflow: string(AuthorStage), Request: clone(req), Status: "rejected"}
	defer func() { finishReport(ctx, d, &report, &err) }()
	if strings.TrimSpace(req.TaskID) == "" || strings.TrimSpace(req.Task) == "" || strings.TrimSpace(req.Context) == "" {
		return report, errors.New("incomplete documentation request")
	}
	route, err := entryRoute(req.DocumentationEntry)
	if err != nil {
		return report, err
	}
	before, err := d.Snapshot(ctx)
	if err != nil {
		return report, err
	}
	if before.ID == "" || len(before.ChangedPaths) != 0 {
		return report, errors.New("documentation author requires a clean source snapshot")
	}
	report.Candidate = clone(before)
	if route == "docs-no-change" {
		report.Documentation = clone(req.DocumentationEntry.Decision)
		report.Status = "docs-no-change"
		return report, nil
	}

	inv := workcycle.Invocation{Request: clone(req), Stage: AuthorStage, Candidate: clone(before)}
	response, runErr := d.Run(ctx, inv)
	after, snapshotErr := d.Snapshot(ctx)
	report.Candidate = clone(after)
	if runErr == nil && response.CandidateID != before.ID {
		runErr = errors.New("documentation author response does not match its invocation")
	}
	// The driver binds the response to its invocation. The final report binds it to the resulting snapshot.
	if runErr == nil && snapshotErr == nil {
		response.CandidateID = after.ID
	}
	report.Observations = []workcycle.Observation{{Stage: AuthorStage, Before: clone(before), After: clone(after), Declaration: clone(response)}}
	if err = errors.Join(runErr, snapshotErr); err != nil {
		return report, err
	}
	for _, name := range after.ChangedPaths {
		if !strings.HasPrefix(name, "help/docs/") || path.Clean(name) != name || strings.ContainsAny(name, "\\\x00") {
			return report, fmt.Errorf("documentation author changed out-of-scope path %s", name)
		}
	}
	if q := response.Question; q != nil {
		if (q.Kind != "needs-human" && q.Kind != "verification-blocked") || q.Text == "" || q.Needed == "" || !evidence(q.Evidence) {
			return report, errors.New("incomplete documentation question")
		}
		report.Question, report.Status = clone(q), q.Kind
		return report, nil
	}
	if err := validateDecision(response.Documentation); err != nil {
		return report, err
	}
	report.Documentation = clone(response.Documentation)
	switch report.Documentation.Decision {
	case "investigate":
		report.Status = "needs-human"
		report.Question = &workcycle.Question{Kind: "needs-human", Text: report.Documentation.Summary, Needed: "Resolve the documentation scope or provide the missing source evidence.", Evidence: response.Evidence}
		return report, nil
	case "no_change":
		report.Status = "docs-no-change"
	case "needed":
		report.Status = "docs-ready-for-pr"
	}
	return report, ValidateAuthorCompletion(report, evidence)
}

func ValidateAuthorCompletion(report workcycle.Report, freshEvidence func([]string) bool) error {
	entry := report.Request.DocumentationEntry
	if entry != nil && (entry.Decision != nil || entry.Route != string(AssessStage) && entry.Route != string(WriteStage)) {
		return errors.New("documentation author requires an agent entry route")
	}
	if report.Workflow != string(AuthorStage) || len(report.Observations) != 1 || len(report.ReviewDecisions) != 0 || report.Question != nil {
		return errors.New("documentation author completion requires one author observation")
	}
	observation := report.Observations[0]
	response := observation.Declaration
	if observation.Stage != AuthorStage || observation.Before.ID == "" || len(observation.Before.ChangedPaths) != 0 ||
		report.Candidate.ID == "" || !reflect.DeepEqual(observation.After, report.Candidate) || response.CandidateID != report.Candidate.ID ||
		response.Question != nil || len(response.Findings) != 0 || !freshEvidence(response.Evidence) || !reflect.DeepEqual(response.Documentation, report.Documentation) {
		return errors.New("documentation author observation does not match the final candidate and evidence")
	}
	doc := report.Documentation
	if err := validateDecision(doc); err != nil {
		return err
	}
	for _, claim := range allClaims(doc) {
		if !freshEvidence(claim.SourceEvidence) || !freshEvidence(claim.DocumentationEvidence) {
			return errors.New("documentation claim has no evidence from the author session")
		}
	}
	if report.Status == "docs-no-change" {
		if doc.Decision != "no_change" || strings.TrimSpace(doc.IssueComment) == "" || len(report.Candidate.ChangedPaths) != 0 || observation.Before.ID != report.Candidate.ID || len(response.Checks) != 0 {
			return errors.New("no-change completion requires an unchanged candidate and an issue comment")
		}
		return nil
	}
	if report.Status != "docs-ready-for-pr" || doc.Decision != "needed" || strings.TrimSpace(doc.PRTitle) == "" || strings.TrimSpace(doc.PRBody) == "" ||
		len(report.Candidate.ChangedPaths) == 0 || observation.Before.ID == report.Candidate.ID {
		return errors.New("documentation author completion requires a help patch and PR description")
	}
	required := make(map[string]bool, len(report.Request.RequiredChecks))
	for _, check := range report.Request.RequiredChecks {
		if check.ID == "" || check.Scope == "" || required[check.ID] {
			return errors.New("incomplete or duplicate required documentation check")
		}
		required[check.ID] = true
	}
	if len(required) == 0 {
		return errors.New("documentation author completion requires configured checks")
	}
	seen := make(map[string]bool, len(response.Checks))
	for _, check := range response.Checks {
		label := "additional documentation check"
		if required[check.ID] {
			label = fmt.Sprintf("required documentation check %q", check.ID)
		}
		if strings.TrimSpace(check.ID) == "" {
			return errors.New("documentation check is missing an ID")
		}
		if seen[check.ID] {
			return fmt.Errorf("%s has more than one result", label)
		}
		if check.Outcome == "fail" {
			return fmt.Errorf("%s failed", label)
		}
		if check.Outcome != "pass" {
			return fmt.Errorf("%s requires a pass or fail outcome", label)
		}
		if !freshEvidence(check.Evidence) {
			return fmt.Errorf("%s is missing evidence from the author session", label)
		}
		seen[check.ID] = true
	}
	var missing []string
	for _, check := range report.Request.RequiredChecks {
		if !seen[check.ID] {
			missing = append(missing, check.ID)
		}
	}
	if len(missing) != 0 {
		return fmt.Errorf("missing required documentation checks: %s", strings.Join(missing, ", "))
	}
	return nil
}
