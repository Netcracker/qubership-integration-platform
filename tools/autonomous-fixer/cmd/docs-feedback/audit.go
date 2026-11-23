package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docfeedback"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

type auditCandidate struct {
	Sample          doctriage.AuditSample `json:"sample"`
	FeedbackRecords []string              `json:"feedback_records"`
	RecordIDs       []string              `json:"record_ids"`
	ObservationIDs  []string              `json:"observation_ids"`
}
type auditResult struct {
	Version                  string           `json:"version"`
	Seed                     string           `json:"seed"`
	Fraction                 float64          `json:"fraction"`
	EligibleRevisions        int              `json:"eligible_revisions"`
	AlreadyAssessedRevisions int              `json:"already_assessed_revisions"`
	PendingRevisions         int              `json:"pending_revisions"`
	Candidates               []auditCandidate `json:"candidates"`
	Notes                    []string         `json:"notes"`
}

func auditRecords(paths []string, seed string, fraction float64) (auditResult, error) {
	result := auditResult{Version: doctriage.AuditVersion, Seed: seed, Fraction: fraction, Candidates: []auditCandidate{}, Notes: []string{
		"Candidates are sampled automatic no-change outcomes, not evidence that an issue was closed or that the decision was correct.",
		"No model, publication, closure, or routing change runs from this command.",
		"Supply all pending and adjudicated feedback records. A supplied judgment excludes that revision from a new audit.",
		"Use Terra for the independent documentation assessment, then record its evidence with docs-feedback adjudicate. Model and human judgments remain distinct.",
	}}
	// Validate the policy even when no record is eligible.
	if _, e := doctriage.SampleAudit(doctriage.AuditIdentity{Repository: "validation/validation", Issue: 1, BaseSHA: "base", ImplementationHeadSHA: "head", SourceSHA: "source", DocsSHA: "docs"}, seed, fraction); e != nil {
		return result, e
	}
	records := []docfeedback.Record{}
	pathByID := map[string]string{}
	for _, path := range paths {
		r, e := readRecord(path)
		if e != nil {
			return result, e
		}
		records = append(records, r)
		if old, ok := pathByID[r.ID]; !ok || path < old {
			pathByID[r.ID] = path
		}
	}
	if _, e := docfeedback.Summarize(records); e != nil {
		return result, e
	}
	superseded := map[string]bool{}
	for _, r := range records {
		if r.Supersedes != "" {
			superseded[r.Supersedes] = true
		}
	}
	groups := map[string]*auditCandidate{}
	assessed := map[string]bool{}
	seen := map[string]bool{}
	for _, r := range records {
		if seen[r.ID] || superseded[r.ID] {
			continue
		}
		seen[r.ID] = true
		if r.Routing.Route != "docs-no-change" || r.Observed.Origin != "classifier" || r.Observed.Status != "docs-no-change" || r.Observed.Decision != "no_change" {
			continue
		}
		sample, e := doctriage.SampleAudit(doctriage.AuditIdentity{Repository: r.Repository, Issue: r.Issue, BaseSHA: r.BaseSHA, ImplementationHeadSHA: r.ImplementationHeadSHA, SourceSHA: r.SourceSHA, DocsSHA: r.DocsSHA}, seed, fraction)
		if e != nil {
			return result, fmt.Errorf("feedback %s: %w", r.ID, e)
		}
		group := groups[sample.RevisionID]
		if group == nil {
			group = &auditCandidate{Sample: sample}
			groups[sample.RevisionID] = group
		}
		if r.Adjudication != nil {
			assessed[sample.RevisionID] = true
		}
		group.FeedbackRecords = append(group.FeedbackRecords, pathByID[r.ID])
		group.RecordIDs = append(group.RecordIDs, r.ID)
		group.ObservationIDs = append(group.ObservationIDs, r.ObservationID)
	}
	result.EligibleRevisions = len(groups)
	ids := []string{}
	for id := range groups {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		group := groups[id]
		if assessed[id] {
			result.AlreadyAssessedRevisions++
			continue
		}
		result.PendingRevisions++
		if !group.Sample.Selected {
			continue
		}
		sort.Strings(group.FeedbackRecords)
		sort.Strings(group.RecordIDs)
		sort.Strings(group.ObservationIDs)
		result.Candidates = append(result.Candidates, *group)
	}
	return result, nil
}

func runAudit(args []string) error {
	f := flag.NewFlagSet("audit", flag.ContinueOnError)
	seed := f.String("seed", "", "stable campaign seed")
	fraction := f.Float64("fraction", -1, "fraction of automatic no-change revisions to audit, from 0 to 1")
	output := f.String("output", "", "new immutable audit-candidate JSON file")
	if e := f.Parse(args); e != nil {
		return e
	}
	if f.NArg() == 0 || *output == "" {
		return fmt.Errorf("audit requires feedback record paths and output")
	}
	result, e := auditRecords(f.Args(), *seed, *fraction)
	if e != nil {
		return e
	}
	b, e := json.MarshalIndent(result, "", "  ")
	if e != nil {
		return e
	}
	out, e := os.OpenFile(*output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	_, writeErr := out.Write(append(b, '\n'))
	closeErr := out.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"output": *output, "eligible_revisions": result.EligibleRevisions, "pending_revisions": result.PendingRevisions, "audit_candidates": len(result.Candidates)})
}
