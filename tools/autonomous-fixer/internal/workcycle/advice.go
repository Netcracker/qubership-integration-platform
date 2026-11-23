package workcycle

import (
	"context"
	"fmt"
)

type AdviceInput struct {
	Stage            Stage
	MandatoryReviews []string
	Phase            string
	Request          Request
	Candidate        Snapshot
	Contract         *Contract
	Failure          string
	Question         *Question
}
type RoutingAdvice struct {
	CoverageCandidates      []string `json:"coverage_candidates,omitempty"`
	QuestionAction          string   `json:"question_action,omitempty"`
	QuestionActionCandidate string   `json:"question_action_candidate,omitempty"`
	ModuleCandidates        []string `json:"module_candidates,omitempty"`
	ReviewCandidates        []string `json:"review_candidates,omitempty"`
	ReadinessCandidate      string   `json:"readiness_candidate,omitempty"`
	RouteCandidate          string   `json:"route_candidate,omitempty"`
	Skills                  []string `json:"skills,omitempty"`
	Phase                   string   `json:"phase"`
	CandidateID             string   `json:"candidate_id"`
	Readiness               string   `json:"readiness,omitempty"`
	Route                   string   `json:"route,omitempty"`
	Modules                 []string `json:"modules,omitempty"`
	ExtraReviews            []string `json:"extra_reviews,omitempty"`
	DuplicateCandidates     []string `json:"duplicate_candidates,omitempty"`
	FailureKind             string   `json:"failure_kind,omitempty"`
	Evidence                string   `json:"evidence"`
}
type Advisor interface {
	Advise(context.Context, AdviceInput) (*RoutingAdvice, error)
}

func advise(ctx context.Context, d any, in AdviceInput) (*RoutingAdvice, error) {
	a, ok := d.(Advisor)
	if !ok {
		return nil, nil
	}
	result, e := a.Advise(ctx, in)
	if e != nil || result == nil {
		return result, e
	}
	if result.Phase != in.Phase || result.CandidateID != in.Candidate.ID || result.Evidence == "" {
		return nil, fmt.Errorf("routing advice does not match the current invocation")
	}
	for _, action := range []string{result.QuestionAction, result.QuestionActionCandidate} {
		switch action {
		case "", "owner_decision", "observable_probe", "unavailable_access", "uncertain":
		default:
			return nil, fmt.Errorf("unknown question action %q", action)
		}
	}
	for _, lens := range append(append([]string(nil), result.ExtraReviews...), result.ReviewCandidates...) {
		switch lens {
		case "compatibility", "data", "security", "concurrency":
		default:
			return nil, fmt.Errorf("unknown additional review lens %q", lens)
		}
	}
	return result, nil
}
func mergeReviews(required, extra []string) []string {
	result := append([]string{}, required...)
	seen := map[string]bool{}
	for _, v := range result {
		seen[v] = true
	}
	for _, v := range extra {
		if !seen[v] {
			result = append(result, v)
			seen[v] = true
		}
	}
	return result
}

func currentAdvice(all []RoutingAdvice, candidate string) []RoutingAdvice {
	var result []RoutingAdvice
	for _, advice := range all {
		if advice.Phase == "intake" || advice.CandidateID == candidate {
			result = append(result, advice)
		}
	}
	return result
}
