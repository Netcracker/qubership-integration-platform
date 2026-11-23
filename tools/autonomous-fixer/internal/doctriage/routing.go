package doctriage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"slices"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
)

type RoutingPolicy struct {
	NegativeProbability    float64  `json:"negative_probability"`
	PositiveProbability    float64  `json:"positive_probability"`
	NegativeMargin         float64  `json:"negative_margin"`
	PositiveMargin         float64  `json:"positive_margin"`
	ReasonProbability      float64  `json:"reason_probability"`
	AttributionProbability float64  `json:"attribution_probability"`
	AutoNoChangeReasons    []string `json:"auto_no_change_reasons"`
}

func (p *RoutingPolicy) UnmarshalJSON(data []byte) error {
	type plain RoutingPolicy
	value := plain(DefaultRoutingPolicy())
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&value); err != nil {
		return err
	}
	*p = RoutingPolicy(value)
	return nil
}

func DefaultRoutingPolicy() RoutingPolicy {
	return RoutingPolicy{NegativeProbability: .95, PositiveProbability: .8, NegativeMargin: .8, PositiveMargin: .5, ReasonProbability: .5, AttributionProbability: .7, AutoNoChangeReasons: []string{"internal_only", "outside_help_scope"}}
}

type Routing struct {
	Version                string         `json:"version"`
	Route                  string         `json:"route"`
	Notes                  []string       `json:"notes"`
	Policy                 RoutingPolicy  `json:"policy"`
	Recommendation         Recommendation `json:"recommendation"`
	ActionProbability      float64        `json:"action_probability"`
	Margin                 float64        `json:"margin"`
	ReasonProbability      float64        `json:"reason_probability"`
	AttributionProbability float64        `json:"attribution_probability"`
}

func ValidateReport(packet Packet, report Report) error {
	if report.PolicyVersion != PolicyVersion {
		return fmt.Errorf("classifier policy %q does not match %q", report.PolicyVersion, PolicyVersion)
	}
	req, err := Request(packet, report.RequestedModel)
	if err != nil {
		return err
	}
	b, err := json.Marshal(req)
	if err != nil {
		return err
	}
	h := sha256.Sum256(b)
	if report.InputSHA256 != hex.EncodeToString(h[:]) {
		return fmt.Errorf("classifier report does not match the current request")
	}
	b, err = json.Marshal(packet)
	if err != nil {
		return err
	}
	h = sha256.Sum256(b)
	if report.PacketSHA256 != hex.EncodeToString(h[:]) || report.PacketID != packet.ID {
		return fmt.Errorf("classifier report does not match the evidence packet")
	}
	if report.Mode == "preflight" {
		if packet.Complete || !IsPreflightSkip(report) {
			return fmt.Errorf("invalid incomplete-input preflight report")
		}
		return nil
	}
	if report.Response == nil {
		if report.Error != "" {
			return nil
		}
		return fmt.Errorf("classifier report has neither a response nor an error")
	}
	return jev.ValidateResponse(req, *report.Response)
}
func SelectRoute(packet Packet, report Report, policy RoutingPolicy) (Routing, error) {
	r := Routing{Version: "docs-routing-v1", Route: "docs-assess", Notes: []string{}, Policy: policy, Recommendation: Recommendation{Action: "investigate", Reason: "insufficient_evidence", Attribution: "unclear"}}
	for _, v := range []float64{policy.NegativeProbability, policy.PositiveProbability, policy.NegativeMargin, policy.PositiveMargin, policy.ReasonProbability, policy.AttributionProbability} {
		if math.IsNaN(v) || math.IsInf(v, 0) || v < 0 || v > 1 {
			return r, fmt.Errorf("routing thresholds must be finite probabilities")
		}
	}
	for _, reason := range policy.AutoNoChangeReasons {
		if !slices.Contains([]string{"already_covered", "restored_behavior", "no_explanation_needed", "internal_only", "outside_help_scope"}, reason) {
			return r, fmt.Errorf("reason %q cannot authorize automatic no-change completion", reason)
		}
	}
	if err := ValidateReport(packet, report); err != nil {
		return r, err
	}
	if IsPreflightSkip(report) {
		r.Notes = append(r.Notes, IncompleteSkipReason)
		return r, nil
	}
	if report.Error != "" || report.Response == nil {
		r.Notes = append(r.Notes, "Classifier request failed; use the assessor.")
		return r, nil
	}
	r.Recommendation, r.Notes = normalize(packet, *report.Response)
	if r.Recommendation.Action == "investigate" {
		r.Notes = append(r.Notes, "The classification requires investigation.")
		return r, nil
	}
	a := report.Response.Answers["documentation_action"]
	r.ActionProbability = a.Probabilities[a.Choice]
	second := 0.0
	for k, p := range a.Probabilities {
		if k != a.Choice && p > second {
			second = p
		}
	}
	r.Margin = r.ActionProbability - second
	reason := report.Response.Answers["documentation_reason"]
	attribution := report.Response.Answers["change_attribution"]
	r.ReasonProbability = reason.Probabilities[reason.Choice]
	r.AttributionProbability = attribution.Probabilities[attribution.Choice]
	if r.ReasonProbability < policy.ReasonProbability || r.AttributionProbability < policy.AttributionProbability {
		r.Notes = append(r.Notes, "The reason or change attribution is uncertain.")
		return r, nil
	}
	if r.Margin <= 0 {
		r.Notes = append(r.Notes, "The selected action has no clear lead.")
		return r, nil
	}
	switch r.Recommendation.Action {
	case "no_change":
		if !slices.Contains(policy.AutoNoChangeReasons, r.Recommendation.Reason) {
			r.Notes = append(r.Notes, "This no-change reason requires an assessor.")
			return r, nil
		}
		if r.ActionProbability < policy.NegativeProbability || r.Margin < policy.NegativeMargin {
			r.Notes = append(r.Notes, "No-change probability or margin is below the configured threshold.")
			return r, nil
		}
		r.Route = "docs-no-change"
	case "needed":
		if r.ActionProbability < policy.PositiveProbability || r.Margin < policy.PositiveMargin {
			r.Notes = append(r.Notes, "Required-change probability or margin is below the configured threshold.")
			return r, nil
		}
		r.Route = "docs-write"
	}
	return r, nil
}
