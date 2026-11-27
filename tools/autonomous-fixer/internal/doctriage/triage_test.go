package doctriage

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

type fake struct {
	action, reason, attribution string
	err                         error
	request                     jev.Request
}

func (f *fake) Classify(_ context.Context, r jev.Request) (jev.Response, error) {
	f.request = r
	answers := map[string]jev.Answer{}
	choices := map[string]string{"documentation_action": f.action, "documentation_reason": f.reason, "change_attribution": f.attribution}
	for key, q := range r.Questions {
		probabilities := map[string]float64{}
		for option := range q.Criteria {
			probabilities[option] = 0
		}
		probabilities[choices[key]] = 1
		answers[key] = jev.Answer{Type: "choice", Choice: choices[key], Probabilities: probabilities}
	}
	return jev.Response{ID: "test", Model: r.Model, Answers: answers}, f.err
}
func packet() Packet {
	return Packet{ID: "case", Scope: "single behavior", Repository: "owner/repo", Issue: json.RawMessage(`{"title":"issue"}`), Complete: true, BaseSHA: "base", HeadSHA: "head", DocsSHA: "docs", ChangedPaths: []string{"module/file"}, Diff: "actual patch", Documentation: []Document{{Path: "help/docs/readme.md", Content: "actual help"}}}
}
func TestRecommendationsSelectThreeBranchesWithoutGitHubEffects(t *testing.T) {
	cases := []fake{
		{action: "needed", reason: "missing_guidance", attribution: "change_related"},
		{action: "needed", reason: "stale_guidance", attribution: "change_related"},
		{action: "no_change", reason: "already_covered", attribution: "unchanged"},
		{action: "no_change", reason: "restored_behavior", attribution: "unchanged"},
		{action: "no_change", reason: "no_explanation_needed", attribution: "unchanged"},
		{action: "no_change", reason: "internal_only", attribution: "unchanged"},
		{action: "no_change", reason: "outside_help_scope", attribution: "unchanged"},
		{action: "no_change", reason: "preexisting_only", attribution: "preexisting_independent"},
		{action: "investigate", reason: "scope_unclear", attribution: "unclear"},
	}
	for _, f := range cases {
		t.Run(f.reason, func(t *testing.T) {
			r, e := Evaluate(context.Background(), packet(), jev.DefaultModel, &f)
			if e != nil || r.Recommendation.Action != f.action || r.Recommendation.Reason != f.reason || len(r.AssessmentNotes) != 0 {
				t.Fatalf("%+v %v", r, e)
			}
			expected := "docs-assess"
			if f.action == "needed" {
				expected = "docs-write"
			}
			if f.reason == "internal_only" || f.reason == "outside_help_scope" {
				expected = "docs-no-change"
			}
			if r.AssessmentRequired != (expected == "docs-assess") || r.SuggestedRoute != expected || r.EffectiveRoute != "observe_only" || r.Mode != "shadow" {
				t.Fatal("classifier bypassed assessment", r)
			}
		})
	}
}
func TestConflictRetainsRawPredictionAndRequestsInvestigation(t *testing.T) {
	for _, f := range []fake{
		{action: "needed", reason: "already_covered", attribution: "change_related"},
		{action: "needed", reason: "stale_guidance", attribution: "preexisting_independent"},
		{action: "needed", reason: "stale_guidance", attribution: "unclear"},
		{action: "no_change", reason: "missing_guidance", attribution: "unchanged"},
		{action: "no_change", reason: "preexisting_only", attribution: "change_related"},
		{action: "investigate", reason: "scope_unclear", attribution: "unchanged"},
	} {
		r, e := Evaluate(context.Background(), packet(), jev.DefaultModel, &f)
		if e != nil || r.Recommendation.Action != "investigate" || len(r.AssessmentNotes) == 0 || r.Response.Answers["documentation_action"].Choice != f.action {
			t.Fatal(r, e)
		}
	}
}
func TestIncompleteEvidenceCannotBecomeNoChange(t *testing.T) {
	for _, change := range []func(*Packet){func(p *Packet) { p.Complete = false }, func(p *Packet) { p.Diff = "" }, func(p *Packet) { p.Documentation = nil }} {
		p := packet()
		change(&p)
		r, e := Evaluate(context.Background(), p, jev.DefaultModel, &fake{action: "no_change", reason: "already_covered", attribution: "unchanged"})
		if e != nil || r.Recommendation.Action != "investigate" || r.Response.Answers["documentation_action"].Choice != "no_change" {
			t.Fatal(r, e)
		}
	}
}
func TestFailureCannotBecomeNoChange(t *testing.T) {
	r, e := Evaluate(context.Background(), packet(), jev.DefaultModel, &fake{err: errors.New("unavailable")})
	if e == nil || r.Recommendation.Action != "investigate" || r.Response != nil || !r.AssessmentRequired || r.Error == "" {
		t.Fatal(r, e)
	}
}
func TestArchiveIdentifiersDoNotHintAtExpectedOutcome(t *testing.T) {
	p := packet()
	p.ID = "expected-no-change"
	f := &fake{action: "investigate", reason: "insufficient_evidence", attribution: "unclear"}
	a, _ := Evaluate(context.Background(), p, jev.DefaultModel, f)
	input, _ := json.Marshal(f.request.State)
	if strings.Contains(string(input), p.ID) {
		t.Fatal("archive ID leaked into input")
	}
	p.ID = "expected-needed"
	b, _ := Evaluate(context.Background(), p, jev.DefaultModel, f)
	if a.InputSHA256 != b.InputSHA256 || a.PacketSHA256 == b.PacketSHA256 {
		t.Fatal("model and archive identities are not separated")
	}
	p.Documentation[0].Content = "updated"
	c, _ := Evaluate(context.Background(), p, jev.DefaultModel, f)
	if c.InputSHA256 == b.InputSHA256 {
		t.Fatal("evidence change did not affect input")
	}
}

func TestRequestRejectsIncompleteHelpExcerpts(t *testing.T) {
	for name, excerpt := range map[string]Document{
		"blank path":    {Path: " ", Content: "Logging settings"},
		"blank content": {Path: "help/docs/logging.md", Content: "\n"},
	} {
		t.Run(name, func(t *testing.T) {
			p := packet()
			p.Complete = false
			p.PartialContext = true
			p.HelpExcerpts = []Document{p.Documentation[0], excerpt}
			if _, err := Request(p, jev.DefaultModel); err == nil {
				t.Fatal("accepted an incomplete help excerpt")
			}
		})
	}
}
