package doccycle

import (
	"context"
	"errors"
	"path"
	"reflect"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func ResumeVerification(ctx context.Context, req workcycle.Request, d workcycle.Driver, seed workcycle.Report) (report workcycle.Report, err error) {
	report = clone(seed)
	report.Status = "rejected"
	report.Error = ""
	report.Question = nil
	defer func() {
		if err != nil {
			report.Status = "rejected"
			report.Error = err.Error()
		}
		if e := d.Save(context.WithoutCancel(ctx), report); e != nil {
			err = errors.Join(err, e)
			report.Status = "rejected"
			report.Error = err.Error()
		}
	}()
	if !reflect.DeepEqual(req, seed.Request) {
		return report, errors.New("resume request differs from checkpoint")
	}
	route, e := entryRoute(req.DocumentationEntry)
	if e != nil {
		return report, e
	}
	if route == "docs-no-change" || len(seed.Observations) == 0 {
		return report, errors.New("resume checkpoint has no agent documentation work")
	}
	first := seed.Observations[0]
	if first.Before.ID == "" {
		return report, errors.New("resume checkpoint has no initial snapshot")
	}
	required := claimRequirements{}
	start := 0
	chainID := first.Before.ID
	if route == string(AssessStage) {
		if first.Stage != AssessStage || first.Before.ID != first.After.ID || first.Declaration.CandidateID != first.Before.ID || first.Declaration.Question != nil || !evidence(first.Declaration.Evidence) {
			return report, errors.New("invalid initial documentation assessment")
		}
		if e := validateDecision(first.Declaration.Documentation); e != nil {
			return report, e
		}
		if first.Declaration.Documentation.Decision != "needed" {
			return report, errors.New("resume assessment does not require documentation")
		}
		required.retain(first.Declaration.Documentation)
		start = 1
		chainID = first.After.ID
	} else {
		if first.Stage != WriteStage || len(first.Before.ChangedPaths) != 0 || first.Declaration.Question != nil || !evidence(first.Declaration.Evidence) {
			return report, errors.New("direct writing resume requires an initial writer on a clean snapshot")
		}
		if e := validateDecision(first.Declaration.Documentation); e != nil {
			return report, e
		}
		if first.Declaration.Documentation.Decision != "needed" {
			return report, errors.New("direct writing resume requires an initial needed decision")
		}
		required.retain(first.Declaration.Documentation)
	}
	validate := func(doc *workcycle.DocumentationDecision) error {
		if e := validateDecision(doc); e != nil {
			return e
		}
		if doc.Decision != "needed" || strings.TrimSpace(doc.PRTitle) == "" || strings.TrimSpace(doc.PRBody) == "" {
			return errors.New("resume requires a needed decision and PR description")
		}
		if e := required.check(doc); e != nil {
			return e
		}
		return nil
	}
	wrote := false
	reviewed := false
	for _, o := range seed.Observations[start:] {
		if o.Before.ID != chainID || o.After.ID == "" {
			return report, errors.New("resume checkpoint snapshot history is discontinuous")
		}
		chainID = o.After.ID
		switch o.Stage {
		case WriteStage:
			wrote = false
			reviewed = false
			for _, p := range o.After.ChangedPaths {
				if !strings.HasPrefix(p, "help/docs/") || path.Clean(p) != p {
					return report, errors.New("resume writer changed source outside documentation")
				}
			}
			if o.Declaration.Question != nil || !evidence(o.Declaration.Evidence) {
				return report, errors.New("resume writer has no accepted evidence")
			}
			if validate(o.Declaration.Documentation) == nil {
				wrote = true
				required.retain(o.Declaration.Documentation)
			}
		case ReviewStage:
			if !wrote || o.Before.ID != o.After.ID || o.Declaration.CandidateID != o.Before.ID || o.Declaration.Question != nil || !evidence(o.Declaration.Evidence) {
				return report, errors.New("resume requires a clean read-only documentation review")
			}
			reviewed = len(o.Declaration.Findings) == 0
		case VerifyStage:
			if !reviewed || o.Before.ID != o.After.ID || o.Declaration.Question != nil {
				return report, errors.New("resume verification changed the reviewed candidate")
			}
			if o.Declaration.Documentation != nil && o.Declaration.CandidateID == o.Before.ID && evidence(o.Declaration.Evidence) && validate(o.Declaration.Documentation) == nil {
				required.retain(o.Declaration.Documentation)
			}
		default:
			return report, errors.New("unexpected stage in documentation resume checkpoint")
		}
	}
	if !wrote || !reviewed || chainID != seed.Candidate.ID || seed.Candidate.ID == first.Before.ID || len(seed.Candidate.ChangedPaths) == 0 {
		return report, errors.New("resume checkpoint has no reviewed documentation patch")
	}
	if e := validate(seed.Documentation); e != nil {
		return report, e
	}
	required.retain(seed.Documentation)
	checks := map[string]bool{}
	for _, c := range req.RequiredChecks {
		if c.ID == "" || c.Scope == "" || checks[c.ID] {
			return report, errors.New("invalid resume required checks")
		}
		checks[c.ID] = true
	}
	if len(checks) == 0 {
		return report, errors.New("resume requires configured documentation checks")
	}
	before, e := d.Snapshot(ctx)
	if e != nil {
		return report, e
	}
	if before.ID != seed.Candidate.ID {
		return report, errors.New("resume workspace differs from reviewed candidate")
	}
	response, e := d.Run(ctx, workcycle.Invocation{Request: clone(req), Stage: VerifyStage, Candidate: clone(before), Documentation: clone(seed.Documentation)})
	after, se := d.Snapshot(ctx)
	report.Candidate = clone(after)
	report.Observations = append(report.Observations, workcycle.Observation{Stage: VerifyStage, Before: clone(before), After: clone(after), Declaration: clone(response)})
	if e != nil || se != nil {
		return report, errors.Join(e, se)
	}
	if after.ID != before.ID || response.CandidateID != before.ID {
		return report, errors.New("resumed verification changed candidate or declared a stale candidate")
	}
	if response.Question != nil {
		q := response.Question
		if (q.Kind != "needs-human" && q.Kind != "verification-blocked") || q.Text == "" || q.Needed == "" || !evidence(q.Evidence) {
			return report, errors.New("invalid resumed verification question")
		}
		report.Status = q.Kind
		report.Question = clone(q)
		return report, nil
	}
	if !evidence(response.Evidence) {
		return report, errors.New("resumed verification has no evidence")
	}
	if e = validate(response.Documentation); e != nil {
		return report, e
	}
	report.Documentation = clone(response.Documentation)
	seen := map[string]bool{}
	failed := false
	for _, r := range response.Checks {
		if !checks[r.ID] || seen[r.ID] || !evidence(r.Evidence) || (r.Outcome != "pass" && r.Outcome != "fail") {
			return report, errors.New("unexpected, duplicate, or malformed resumed check")
		}
		seen[r.ID] = true
		failed = failed || r.Outcome == "fail"
	}
	if len(seen) != len(checks) {
		return report, errors.New("missing resumed documentation check")
	}
	if failed || len(response.Findings) > 0 {
		report.Status = "verification-blocked"
		report.Question = &workcycle.Question{Kind: "verification-blocked", Text: "Fresh documentation verification reported failures or findings.", Needed: "Resolve the fresh verification failures before preparing the documentation PR.", Evidence: clone(response.Evidence)}
		return report, nil
	}
	report.Status = "docs-ready-for-pr"
	return report, nil
}
