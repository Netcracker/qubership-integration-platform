package doctriage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"slices"
	"strings"

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
	ReasonCode             RoutingReason  `json:"reason_code,omitempty"`
	Notes                  []string       `json:"notes"`
	Policy                 RoutingPolicy  `json:"policy"`
	Recommendation         Recommendation `json:"recommendation"`
	ActionProbability      float64        `json:"action_probability"`
	Margin                 float64        `json:"margin"`
	ReasonProbability      float64        `json:"reason_probability"`
	AttributionProbability float64        `json:"attribution_probability"`
}

const (
	legacyRoutingVersion = "docs-routing-v1"
	routingVersion       = "docs-routing-v2"
)

type RoutingReason string

const (
	RouteIncompleteEvidence      RoutingReason = "incomplete_evidence"
	RouteClassifierFailed        RoutingReason = "classifier_failed"
	RouteAnswersConflict         RoutingReason = "answers_conflict"
	RouteMissingHelpEvidence     RoutingReason = "missing_help_evidence"
	RouteInvestigation           RoutingReason = "investigation_recommended"
	RouteUncertainClassification RoutingReason = "uncertain_classification"
	RouteNoActionLead            RoutingReason = "no_action_lead"
	RouteAssessmentRequired      RoutingReason = "assessment_required"
	RouteBelowThreshold          RoutingReason = "below_threshold"
	RouteRecommendationAccepted  RoutingReason = "recommendation_accepted"
)

const (
	classifierFailedNote    = "Classifier request failed; use the assessor."
	answersConflictNote     = "The action, reason, and change attribution conflict."
	incompleteEvidenceNote  = "The issue evidence is incomplete."
	missingHelpEvidenceNote = "The recommendation requires current help evidence."
	investigationNote       = "The classification requires investigation."
	uncertainNote           = "The reason or change attribution is uncertain."
	noLeadNote              = "The selected action has no clear lead."
	assessmentRequiredNote  = "This no-change reason requires an assessor."
	negativeThresholdNote   = "No-change probability or margin is below the configured threshold."
	positiveThresholdNote   = "Required-change probability or margin is below the configured threshold."
)

func (r Routing) DecisionReason() RoutingReason {
	if r.Version != legacyRoutingVersion {
		return r.ReasonCode
	}
	for _, note := range r.Notes {
		switch note {
		case IncompleteSkipReason, incompleteEvidenceNote:
			return RouteIncompleteEvidence
		case classifierFailedNote:
			return RouteClassifierFailed
		case answersConflictNote:
			return RouteAnswersConflict
		case missingHelpEvidenceNote:
			return RouteMissingHelpEvidence
		case uncertainNote:
			return RouteUncertainClassification
		case noLeadNote:
			return RouteNoActionLead
		case assessmentRequiredNote:
			return RouteAssessmentRequired
		case negativeThresholdNote, positiveThresholdNote:
			return RouteBelowThreshold
		case investigationNote:
			return RouteInvestigation
		default:
			if strings.HasPrefix(note, "inconsistent documentation decision ") {
				return RouteAnswersConflict
			}
		}
	}
	if r.Route == "docs-no-change" || r.Route == "docs-write" {
		return RouteRecommendationAccepted
	}
	return ""
}

func ReplayRoute(packet Packet, report Report, policy RoutingPolicy, version string) (Routing, error) {
	if version != legacyRoutingVersion && version != routingVersion {
		return Routing{}, fmt.Errorf("unsupported documentation routing version %q", version)
	}
	routing, err := SelectRoute(packet, report, policy)
	if version == legacyRoutingVersion {
		routing.Version = legacyRoutingVersion
		routing.ReasonCode = ""
	}
	return routing, err
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
		if packet.CanClassify() || !IsPreflightSkip(report) {
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
	r := Routing{Version: routingVersion, Route: "docs-assess", Notes: []string{}, Policy: policy, Recommendation: Recommendation{Action: "investigate", Reason: "insufficient_evidence", Attribution: "unclear"}}
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
		r.ReasonCode = RouteIncompleteEvidence
		r.Notes = append(r.Notes, IncompleteSkipReason)
		return r, nil
	}
	if report.Error != "" || report.Response == nil {
		r.ReasonCode = RouteClassifierFailed
		r.Notes = append(r.Notes, classifierFailedNote)
		return r, nil
	}
	r.Recommendation, r.Notes, r.ReasonCode = normalize(packet, *report.Response)
	if r.Recommendation.Action == "investigate" {
		if r.ReasonCode == "" {
			r.ReasonCode = RouteInvestigation
		}
		r.Notes = append(r.Notes, investigationNote)
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
		r.ReasonCode = RouteUncertainClassification
		r.Notes = append(r.Notes, uncertainNote)
		return r, nil
	}
	if r.Margin <= 0 {
		r.ReasonCode = RouteNoActionLead
		r.Notes = append(r.Notes, noLeadNote)
		return r, nil
	}
	switch r.Recommendation.Action {
	case "no_change":
		if !slices.Contains(policy.AutoNoChangeReasons, r.Recommendation.Reason) {
			r.ReasonCode = RouteAssessmentRequired
			r.Notes = append(r.Notes, assessmentRequiredNote)
			return r, nil
		}
		if r.ActionProbability < policy.NegativeProbability || r.Margin < policy.NegativeMargin {
			r.ReasonCode = RouteBelowThreshold
			r.Notes = append(r.Notes, negativeThresholdNote)
			return r, nil
		}
		r.Route = "docs-no-change"
	case "needed":
		if r.ActionProbability < policy.PositiveProbability || r.Margin < policy.PositiveMargin {
			r.ReasonCode = RouteBelowThreshold
			r.Notes = append(r.Notes, positiveThresholdNote)
			return r, nil
		}
		r.Route = "docs-write"
	}
	r.ReasonCode = RouteRecommendationAccepted
	return r, nil
}
