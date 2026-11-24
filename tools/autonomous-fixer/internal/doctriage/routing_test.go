package doctriage

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

func classified(t *testing.T, action, reason, attribution string) Report {
	t.Helper()
	r, e := Evaluate(context.Background(), packet(), jev.DefaultModel, &fake{action: action, reason: reason, attribution: attribution})
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestRouteRecomputesRecommendationAndRejectsStaleEvidence(t *testing.T) {
	p := packet()
	r := classified(t, "no_change", "outside_help_scope", "unchanged")
	r.Recommendation = Recommendation{Action: "needed"}
	r.SuggestedRoute = "docs-write"
	route, e := SelectRoute(p, r, DefaultRoutingPolicy())
	if e != nil || route.Route != "docs-no-change" || route.ReasonCode != RouteRecommendationAccepted {
		t.Fatal(route, e)
	}
	p.Documentation[0].Content = "new docs"
	if _, e = SelectRoute(p, r, DefaultRoutingPolicy()); e == nil {
		t.Fatal("stale packet accepted")
	}
	r.PolicyVersion = "docs-impact-v4"
	if _, e = SelectRoute(packet(), r, DefaultRoutingPolicy()); e == nil {
		t.Fatal("old policy accepted")
	}
}
func TestRoutingProbabilityMarginsAndReasonCertainty(t *testing.T) {
	for _, tc := range []struct {
		name string
		p    map[string]float64
		want string
		code RoutingReason
	}{
		{"confident", map[string]float64{"needed": .01, "no_change": .98, "investigate": .01}, "docs-no-change", RouteRecommendationAccepted},
		{"uncertain", map[string]float64{"needed": .2, "no_change": .79, "investigate": .01}, "docs-assess", RouteBelowThreshold},
		{"tie", map[string]float64{"needed": .49, "no_change": .49, "investigate": .02}, "docs-assess", RouteNoActionLead},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := classified(t, "no_change", "outside_help_scope", "unchanged")
			a := r.Response.Answers["documentation_action"]
			a.Probabilities = tc.p
			r.Response.Answers["documentation_action"] = a
			route, e := SelectRoute(packet(), r, DefaultRoutingPolicy())
			if e != nil || route.Route != tc.want || route.ReasonCode != tc.code {
				t.Fatal(route, e)
			}
		})
	}
	r := classified(t, "needed", "stale_guidance", "change_related")
	a := r.Response.Answers["documentation_reason"]
	a.Probabilities["stale_guidance"] = .49
	a.Probabilities["missing_guidance"] = .49
	a.Probabilities["scope_unclear"] = .02
	r.Response.Answers["documentation_reason"] = a
	route, e := SelectRoute(packet(), r, DefaultRoutingPolicy())
	if e != nil || route.Route != "docs-assess" || route.ReasonCode != RouteUncertainClassification {
		t.Fatal(route, e)
	}
}
func TestNegativeReasonsAreConfigurableButOldFindingsNeedAssessment(t *testing.T) {
	r := classified(t, "no_change", "already_covered", "unchanged")
	policy := DefaultRoutingPolicy()
	a, e := SelectRoute(packet(), r, policy)
	if e != nil || a.Route != "docs-assess" || a.ReasonCode != RouteAssessmentRequired {
		t.Fatal(a, e)
	}
	policy.AutoNoChangeReasons = append(policy.AutoNoChangeReasons, "already_covered")
	a, e = SelectRoute(packet(), r, policy)
	if e != nil || a.Route != "docs-no-change" {
		t.Fatal(a, e)
	}
	r = classified(t, "no_change", "preexisting_only", "preexisting_independent")
	a, e = SelectRoute(packet(), r, policy)
	if e != nil || a.Route != "docs-assess" {
		t.Fatal(a, e)
	}
	policy.AutoNoChangeReasons = append(policy.AutoNoChangeReasons, "preexisting_only")
	if _, e = SelectRoute(packet(), r, policy); e == nil {
		t.Fatal("cannot silently lose separate finding")
	}
}
func TestInconsistentNeededAnswerCannotReachWriter(t *testing.T) {
	r := classified(t, "needed", "already_covered", "change_related")
	a, e := SelectRoute(packet(), r, DefaultRoutingPolicy())
	if e != nil || a.Route != "docs-assess" || a.Recommendation.Action != "investigate" || a.ReasonCode != RouteAnswersConflict {
		t.Fatal(a, e)
	}
}

func TestRouteDistinguishesClassifierInvestigationFromMissingHelpEvidence(t *testing.T) {
	p := packet()
	r := classified(t, "investigate", "scope_unclear", "unclear")
	selected, err := SelectRoute(p, r, DefaultRoutingPolicy())
	if err != nil || selected.ReasonCode != RouteInvestigation {
		t.Fatal(selected, err)
	}
	p.Documentation = nil
	r, err = Evaluate(context.Background(), p, jev.DefaultModel, &fake{action: "needed", reason: "stale_guidance", attribution: "change_related"})
	if err != nil {
		t.Fatal(err)
	}
	selected, err = SelectRoute(p, r, DefaultRoutingPolicy())
	if err != nil || selected.ReasonCode != RouteMissingHelpEvidence {
		t.Fatal(selected, err)
	}
}

func TestPartialPolicyPreservesUnspecifiedDefaults(t *testing.T) {
	var p RoutingPolicy
	if e := json.Unmarshal([]byte(`{"positive_probability":0.9}`), &p); e != nil {
		t.Fatal(e)
	}
	want := DefaultRoutingPolicy()
	if p.PositiveProbability != .9 || p.NegativeProbability != want.NegativeProbability || p.PositiveMargin != want.PositiveMargin || len(p.AutoNoChangeReasons) != len(want.AutoNoChangeReasons) {
		t.Fatal(p)
	}
	if e := json.Unmarshal([]byte(`{"positive_probabilty":0.9}`), &p); e == nil {
		t.Fatal("misspelled policy silently ignored")
	}
}
