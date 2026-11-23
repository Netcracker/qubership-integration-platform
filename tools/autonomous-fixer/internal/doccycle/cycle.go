package doccycle

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docdecision"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

const (
	AssessStage workcycle.Stage = "docs-assess"
	WriteStage  workcycle.Stage = "docs-write"
	ReviewStage workcycle.Stage = "docs-review"
	VerifyStage workcycle.Stage = "docs-verify"
)

var question = errors.New("documentation cycle requires input")

func Run(ctx context.Context, req workcycle.Request, d workcycle.Driver) (report workcycle.Report, err error) {
	report = workcycle.Report{Request: clone(req), Status: "rejected"}
	defer func() { finishReport(ctx, d, &report, &err) }()
	if strings.TrimSpace(req.TaskID) == "" || strings.TrimSpace(req.Task) == "" || strings.TrimSpace(req.Context) == "" {
		return report, errors.New("incomplete documentation request")
	}
	if e := ctx.Err(); e != nil {
		return report, e
	}
	checks := map[string]bool{}
	for _, c := range req.RequiredChecks {
		if c.ID == "" || c.Scope == "" || checks[c.ID] {
			return report, errors.New("incomplete or duplicate required check")
		}
		checks[c.ID] = true
	}
	route, e := entryRoute(req.DocumentationEntry)
	if e != nil {
		return report, e
	}
	if route == "docs-no-change" {
		candidate, e := d.Snapshot(ctx)
		if e != nil {
			return report, e
		}
		if candidate.ID == "" || len(candidate.ChangedPaths) != 0 {
			return report, errors.New("automatic no-change requires a clean source snapshot")
		}
		report.Candidate = clone(candidate)
		report.Documentation = clone(req.DocumentationEntry.Decision)
		report.Status = "docs-no-change"
		return report, nil
	}
	if route == string(WriteStage) {
		if len(checks) == 0 {
			return report, errors.New("direct documentation writing requires configured checks")
		}
		candidate, e := d.Snapshot(ctx)
		if e != nil {
			return report, e
		}
		if candidate.ID == "" || len(candidate.ChangedPaths) != 0 {
			return report, errors.New("direct documentation writing requires a clean source snapshot")
		}
		report.Candidate = clone(candidate)
	}
	call := func(stage workcycle.Stage, findings []workcycle.Finding) (workcycle.Response, error) {
		if e := ctx.Err(); e != nil {
			return workcycle.Response{}, e
		}
		before, e := d.Snapshot(ctx)
		if e != nil {
			return workcycle.Response{}, e
		}
		if before.ID == "" || (report.Candidate.ID != "" && before.ID != report.Candidate.ID) {
			return workcycle.Response{}, errors.New("missing snapshot or candidate changed between stages")
		}
		response, e := d.Run(ctx, workcycle.Invocation{Request: clone(req), Stage: stage, Candidate: clone(before), Documentation: clone(report.Documentation), Findings: clone(findings)})
		after, se := d.Snapshot(ctx)
		report.Observations = append(report.Observations, workcycle.Observation{Stage: stage, Before: clone(before), After: clone(after), Declaration: clone(response)})
		report.Candidate = clone(after)
		if e != nil || se != nil {
			return response, errors.Join(e, se)
		}
		if after.ID == "" {
			return response, errors.New("empty snapshot after invocation")
		}
		if stage != WriteStage && (response.CandidateID != before.ID || after.ID != before.ID) {
			return response, errors.New("read-only stage changed candidate or declared stale candidate")
		}
		if stage == WriteStage {
			for _, p := range after.ChangedPaths {
				if !strings.HasPrefix(p, "help/docs/") || path.Clean(p) != p {
					return response, fmt.Errorf("documentation writer changed out-of-scope path %s", p)
				}
			}
		}
		if response.Question != nil {
			q := response.Question
			if (q.Kind != "needs-human" && q.Kind != "verification-blocked") || q.Text == "" || q.Needed == "" || !evidence(q.Evidence) {
				return response, errors.New("incomplete documentation question")
			}
			report.Question = clone(q)
			report.Status = q.Kind
			return response, question
		}
		if !evidence(response.Evidence) {
			return response, errors.New("documentation stage has no evidence")
		}
		if e = d.Save(ctx, report); e != nil {
			return response, e
		}
		return response, nil
	}
	firstStage := AssessStage
	if route == string(WriteStage) {
		firstStage = WriteStage
	}
	response, e := call(firstStage, nil)
	if e != nil {
		return report, e
	}
	if e = validateDecision(response.Documentation); e != nil {
		return report, e
	}
	report.Documentation = clone(response.Documentation)
	initial := report.Observations[0].Before.ID
	requiredClaims := claimRequirements{}
	requiredClaims.retain(report.Documentation)
	acceptWritten := func(doc *workcycle.DocumentationDecision) error {
		if e := validateDecision(doc); e != nil {
			return e
		}
		if doc.Decision != "needed" || strings.TrimSpace(doc.PRTitle) == "" || strings.TrimSpace(doc.PRBody) == "" {
			return errors.New("documentation writer must provide a needed decision and PR description")
		}
		if e := requiredClaims.check(doc); e != nil {
			return e
		}
		requiredClaims.retain(doc)
		report.Documentation = clone(doc)
		return nil
	}
	switch report.Documentation.Decision {
	case "investigate":
		report.Status = "needs-human"
		report.Question = &workcycle.Question{Kind: "needs-human", Text: report.Documentation.Summary, Needed: "Resolve the documentation scope or provide the missing source evidence.", Evidence: response.Evidence}
		return report, nil
	case "no_change":
		if strings.TrimSpace(report.Documentation.IssueComment) == "" {
			return report, errors.New("no-change decision requires an issue comment")
		}
		if firstStage == WriteStage && (report.Candidate.ID != initial || len(report.Candidate.ChangedPaths) != 0) {
			return report, errors.New("direct documentation writer changed source before returning no change")
		}
		report.Status = "docs-no-change"
		return report, nil
	}
	if len(checks) == 0 {
		return report, errors.New("documentation changes require configured checks")
	}
	protocolFailures := map[string]bool{}
	write := func(findings []workcycle.Finding, initialResponse *workcycle.Response) error {
		for {
			var r workcycle.Response
			if initialResponse != nil {
				r = clone(*initialResponse)
				initialResponse = nil
			} else {
				var e error
				r, e = call(WriteStage, findings)
				if e != nil {
					return e
				}
			}
			if e = acceptWritten(r.Documentation); e == nil {
				return nil
			}
			description := e.Error() + ". Preserve all incoming claim IDs, their attribution and needs_change values, and separate findings, including claims whose documentation is already covered. Return a complete needed decision with the PR title and description."
			key := report.Candidate.ID + "|" + e.Error()
			if protocolFailures[key] {
				report.Status = "needs-human"
				report.Question = &workcycle.Question{Kind: "needs-human", Text: "The documentation writer repeated an invalid declaration for the same candidate: " + e.Error(), Needed: "Resolve the documentation declaration while preserving all accepted claims.", Evidence: clone(r.Evidence)}
				return question
			}
			protocolFailures[key] = true
			claimID := ""
			present := map[string]bool{}
			if r.Documentation != nil {
				for _, claim := range allClaims(r.Documentation) {
					present[claim.ID] = true
				}
			}
			for _, claim := range allClaims(report.Documentation) {
				if claimID == "" || !present[claim.ID] {
					claimID = claim.ID
				}
				if !present[claim.ID] {
					break
				}
			}
			corrective := workcycle.Finding{ID: "documentation-declaration", ScenarioID: claimID, Location: "documentation report", Description: description, Evidence: clone(r.Evidence)}
			retained := []workcycle.Finding{}
			for _, finding := range findings {
				if finding.ID != corrective.ID {
					retained = append(retained, finding)
				}
			}
			findings = append(retained, corrective)
		}
	}

	var initialResponse *workcycle.Response
	if firstStage == WriteStage {
		initialResponse = &response
	}
	if e = write(nil, initialResponse); e != nil {
		return report, e
	}
	seen := map[string]bool{}
	for {
		response, e = call(ReviewStage, nil)
		if e != nil {
			return report, e
		}
		findings := response.Findings
		claims := map[string]bool{}
		for _, c := range allClaims(report.Documentation) {
			claims[c.ID] = true
		}
		ids := map[string]bool{}
		for _, f := range findings {
			if f.ID == "" || ids[f.ID] || !claims[f.ScenarioID] || f.Location == "" || f.Description == "" || !evidence(f.Evidence) {
				return report, errors.New("incomplete, duplicate, or unknown documentation finding")
			}
			ids[f.ID] = true
		}
		if len(findings) == 0 {
			response, e = call(VerifyStage, nil)
			if e != nil {
				return report, e
			}
			if response.Documentation != nil {
				if e = acceptWritten(response.Documentation); e != nil {
					return report, e
				}
			}
			for _, claim := range allClaims(report.Documentation) {
				claims[claim.ID] = true
			}
			for _, f := range response.Findings {
				if f.ID == "" || ids[f.ID] || !claims[f.ScenarioID] || f.Location == "" || f.Description == "" || !evidence(f.Evidence) {
					return report, errors.New("incomplete, duplicate, or unknown final documentation finding")
				}
				ids[f.ID] = true
				findings = append(findings, f)
			}
			results := map[string]bool{}
			for _, r := range response.Checks {
				if !checks[r.ID] || results[r.ID] || !evidence(r.Evidence) || (r.Outcome != "pass" && r.Outcome != "fail") {
					return report, errors.New("unexpected, duplicate, or malformed documentation check")
				}
				results[r.ID] = true
				if r.Outcome == "fail" {
					findings = append(findings, workcycle.Finding{ID: "check:" + r.ID, Location: r.ID, Description: "Required documentation check failed: " + r.ID, Evidence: r.Evidence})
				}
			}
			if len(results) != len(checks) {
				return report, errors.New("missing required documentation check")
			}
			if len(findings) == 0 {
				if report.Candidate.ID == initial || len(report.Candidate.ChangedPaths) == 0 {
					return report, errors.New("needed documentation produced no patch")
				}
				report.Status = "docs-ready-for-pr"
				return report, nil
			}
		}
		keys := []string{}
		refs := []string{}
		for _, f := range findings {
			keys = append(keys, f.ID+":"+f.ScenarioID+":"+f.Description)
			refs = append(refs, f.Evidence...)
		}
		sort.Strings(keys)
		key := report.Candidate.ID + "|" + strings.Join(keys, "|")
		if seen[key] {
			report.Status = "needs-human"
			report.Question = &workcycle.Question{Kind: "needs-human", Text: "The same documentation candidate still has the same findings after repair.", Needed: "Decide how to resolve the repeated findings.", Evidence: refs}
			return report, nil
		}
		seen[key] = true
		if e = write(findings, nil); e != nil {
			return report, e
		}
	}
}

func finishReport(ctx context.Context, d workcycle.Driver, report *workcycle.Report, err *error) {
	if errors.Is(*err, question) {
		*err = nil
	}
	if *err != nil {
		report.Status = "rejected"
		report.Error = (*err).Error()
	}
	if saveErr := d.Save(context.WithoutCancel(ctx), *report); saveErr != nil {
		*err = errors.Join(*err, saveErr)
		report.Status = "rejected"
		report.Error = (*err).Error()
	}
}

func ValidateDocumentation(d *workcycle.DocumentationDecision) error {
	return validateDecision(d)
}

func validateDecision(d *workcycle.DocumentationDecision) error {
	if d == nil || strings.TrimSpace(d.Summary) == "" || len(d.Claims) == 0 {
		return errors.New("missing documentation decision, summary, or claims")
	}
	if err := docdecision.Validate(d.Decision, d.Reason); err != nil {
		return err
	}
	ids := map[string]bool{}
	required := false
	for _, group := range []struct {
		claims   []workcycle.DocClaim
		separate bool
	}{{d.Claims, false}, {d.SeparateFindings, true}} {
		for _, c := range group.claims {
			if c.ID == "" || ids[c.ID] || strings.TrimSpace(c.Behavior) == "" || !evidence(c.SourceEvidence) || !evidence(c.DocumentationEvidence) || strings.TrimSpace(c.Action) == "" {
				return errors.New("incomplete or duplicate documentation claim")
			}
			ids[c.ID] = true
			if group.separate {
				if c.Attribution != "preexisting_independent" {
					return errors.New("separate documentation findings must be preexisting and independent of the issue")
				}
				continue
			}
			switch c.Attribution {
			case "change_related", "unchanged":
			case "unclear":
				if d.Decision != "investigate" {
					return errors.New("unclear documentation attribution requires investigation")
				}
			default:
				return errors.New("main claims require change-related, unchanged, or unclear attribution; record independent defects as separate findings")
			}
			if c.NeedsChange {
				if d.Decision == "no_change" || (d.Decision == "needed" && c.Attribution != "change_related") {
					return errors.New("required documentation changes must be attributed to the issue")
				}
				required = required || c.Attribution == "change_related"
			}
		}
	}
	if d.Decision == "no_change" && d.Reason == "preexisting_only" && len(d.SeparateFindings) == 0 {
		return errors.New("preexisting-only decisions require a separate finding")
	}
	if d.Decision == "needed" && !required {
		return errors.New("needed documentation requires a change-related claim needing an edit")
	}
	return nil
}
func evidence(refs []string) bool {
	if len(refs) == 0 {
		return false
	}
	for _, r := range refs {
		if strings.TrimSpace(r) == "" {
			return false
		}
	}
	return true
}
func clone[T any](v T) T {
	b, _ := json.Marshal(v)
	var result T
	_ = json.Unmarshal(b, &result)
	return result
}
