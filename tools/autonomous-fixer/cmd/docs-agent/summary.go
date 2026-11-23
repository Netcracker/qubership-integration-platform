package main

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doccycle"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type checkDiagnostic struct {
	ID      string `json:"id"`
	Outcome string `json:"outcome"`
}

type workDiagnostics struct {
	WorkStepOutcome  string            `json:"work_step_outcome"`
	Status           *string           `json:"status"`
	Route            *string           `json:"route"`
	Stages           []string          `json:"stages"`
	RequiredChecks   []checkDiagnostic `json:"required_check_results"`
	MissingChecks    []string          `json:"missing_required_checks"`
	AdditionalChecks int               `json:"additional_check_count"`
	AuthorAttempts   *int              `json:"native_openrouter_attempts"`
	AuthorCost       *float64          `json:"native_openrouter_cost_usd"`
	JevAttempted     *bool             `json:"jev_request_attempted"`
	JevCost          *float64          `json:"jev_cost_usd"`
	RejectedDiff     bool              `json:"rejected_diff_saved"`
	RoutingReason    string            `json:"routing_reason,omitempty"`
	JevActivity      modelActivity     `json:"jev"`
	AuthorActivity   modelActivity     `json:"author"`
	Notice           *workNotice       `json:"notice,omitempty"`
}

type classifierDiagnostics struct {
	Attempted      *bool         `json:"request_attempted"`
	Mode           string        `json:"mode"`
	RequestedModel string        `json:"requested_model"`
	Response       *jev.Response `json:"response"`
}

type authorDiagnostics struct {
	Attempts        *int                         `json:"attempts"`
	Cost            *float64                     `json:"reported_cost_usd"`
	ReportedPortion *float64                     `json:"known_cost_usd"`
	Generations     []openrouterusage.Generation `json:"generations"`
}

type modelActivity struct {
	State       string   `json:"state"`
	Description string   `json:"description"`
	Cost        *float64 `json:"cost_usd"`
	Subtotal    *float64 `json:"reported_subtotal_usd,omitempty"`
}

func writeWorkSummary(prepared, run, output, outcome, checkIDs string) error {
	if !filepath.IsAbs(run) || !filepath.IsAbs(output) {
		return fmt.Errorf("work-summary requires absolute run and output paths")
	}
	var required []string
	if checkIDs != "" {
		if err := json.Unmarshal([]byte(checkIDs), &required); err != nil {
			return fmt.Errorf("invalid documentation check IDs")
		}
	}
	work := filepath.Join(run, "work")
	report := readDiagnostic[workcycle.Report](run, "work/report.json")
	route := readDiagnostic[doctriage.Routing](run, "work/routing.json")
	classifier := readDiagnostic[classifierDiagnostics](run, "jev-report.json")
	provider := readDiagnostic[authorDiagnostics](run, "work/"+openrouterusage.Filename)
	diagnostics := collectWorkDiagnostics(report, route, classifier, provider, required, outcome)
	if info, err := os.Lstat(filepath.Join(output, "rejected.patch")); err == nil {
		diagnostics.RejectedDiff = info.Mode().IsRegular() && info.Size() > 0
	}
	progress := readDiagnostic[experimentruntime.Progress](run, "work/runtime-progress.json")
	const stage = "work/step-0001-docs-author/"
	profile := readDiagnostic[experimentruntime.Profile](run, stage+"profile.json")
	session := readDiagnostic[agentsignature.Identity](run, stage+agentsignature.IdentityFilename)
	diagnostics.JevActivity = describeClassifier(classifier, diagnostics.JevCost, outcome == "skipped")
	diagnostics.AuthorActivity = describeAuthor(diagnostics, provider, progress, profile, session)
	diagnostics.RoutingReason = explainRoute(diagnostics.Route, route, classifier)
	diagnostics.Notice = documentationStopNotice(prepared, work, report)
	if diagnostics.Notice != nil && !safeModel(diagnostics.Notice.Signature.Model) {
		diagnostics.Notice.Signature = agentsignature.Signature{}
	}
	if err := save(filepath.Join(output, "summary.json"), diagnostics); err != nil {
		return err
	}
	fmt.Print(formatWorkSummary(diagnostics, report, workflowLogURL()))
	return nil
}

func readDiagnostic[T any](root, name string) T {
	var data, empty T
	path := filepath.Join(root, name)
	if regularPath(root, path, false) != nil {
		return empty
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || read(path, &data) != nil {
		return empty
	}
	return data
}

func collectWorkDiagnostics(report workcycle.Report, routing doctriage.Routing, classifier classifierDiagnostics, provider authorDiagnostics, required []string, outcome string) workDiagnostics {
	result := workDiagnostics{WorkStepOutcome: outcome, Stages: []string{}, RequiredChecks: []checkDiagnostic{}, MissingChecks: []string{},
		JevAttempted: classifier.Attempted, AuthorCost: validCost(provider.Cost), AuthorAttempts: provider.Attempts}
	if provider.Attempts != nil && *provider.Attempts < 0 {
		result.AuthorAttempts = nil
	}
	if classifier.Response != nil && classifier.Response.Usage != nil {
		result.JevCost = validCost(classifier.Response.Usage.Cost)
	}
	switch report.Status {
	case "rejected", "needs-human", "verification-blocked", "docs-no-change", "docs-ready-for-pr":
		result.Status = &report.Status
	}
	if entry := report.Request.DocumentationEntry; entry != nil {
		result.Route = recognizedRoute(entry.Route)
	}
	if result.Route == nil {
		result.Route = recognizedRoute(routing.Route)
	}
	var checks []workcycle.Result
	for _, observation := range report.Observations {
		if observation.Stage == doccycle.AuthorStage {
			result.Stages = append(result.Stages, string(observation.Stage))
			checks = append(checks, observation.Declaration.Checks...)
		}
	}
	for _, id := range required {
		found := false
		for _, check := range checks {
			if check.ID == id {
				status := check.Outcome
				if status != "pass" && status != "fail" {
					status = "invalid"
				}
				result.RequiredChecks = append(result.RequiredChecks, checkDiagnostic{ID: id, Outcome: status})
				found = true
			}
		}
		if !found && report.Documentation != nil && report.Documentation.Decision == "needed" {
			result.MissingChecks = append(result.MissingChecks, id)
		}
	}
	for _, check := range checks {
		if !slices.Contains(required, check.ID) {
			result.AdditionalChecks++
		}
	}
	return result
}

func recognizedRoute(route string) *string {
	switch route {
	case "docs-assess", "docs-no-change", "docs-write":
		return &route
	default:
		return nil
	}
}

func explainRoute(selected *string, routing doctriage.Routing, classifier classifierDiagnostics) string {
	if selected == nil {
		return ""
	}
	if *selected == "docs-assess" && classifier.Mode == "preflight" && classifier.Attempted != nil && !*classifier.Attempted {
		return "Issue evidence was incomplete, so the workflow selected assessment."
	}
	if routing.Route != *selected {
		return ""
	}
	if *selected == "docs-assess" {
		for _, note := range routing.Notes {
			switch note {
			case "Classifier request failed; use the assessor.":
				return "The Jev request failed, so the workflow selected assessment."
			case "The reason or change attribution is uncertain.", "The selected action has no clear lead.",
				"No-change probability or margin is below the configured threshold.",
				"Required-change probability or margin is below the configured threshold.":
				return "The Jev recommendation fell below the routing policy thresholds."
			case "This no-change reason requires an assessor.":
				return "The routing policy requires assessment for this no-change reason."
			}
		}
	}
	switch routing.Recommendation.Reason {
	case "missing_guidance":
		return "Jev identified missing product help."
	case "stale_guidance":
		return "Jev identified outdated product help."
	case "outside_help_scope":
		return "Jev classified the change as outside the scope of product help."
	case "internal_only":
		return "Jev classified the change as internal."
	case "already_covered":
		return "Jev found that the current help covers the changed behavior."
	case "restored_behavior":
		return "Jev found that the change restores behavior described in the current help."
	case "no_explanation_needed":
		return "Jev found no additional explanation was needed for product help readers."
	case "preexisting_only":
		return "Jev identified a help issue that predates the change."
	case "insufficient_evidence":
		return "Jev found insufficient evidence to determine the effect on product help."
	case "scope_unclear":
		return "Jev could not determine the scope of the help update."
	default:
		return ""
	}
}

func describeClassifier(report classifierDiagnostics, cost *float64, skipped bool) modelActivity {
	switch {
	case skipped || report.Attempted != nil && !*report.Attempted:
		return modelActivity{State: "skipped", Description: "Skipped.", Cost: new(float64)}
	case report.Response != nil:
		return modelActivity{State: "responded", Description: describeModels([]string{report.Response.Model}, "", report.RequestedModel), Cost: cost}
	case report.Attempted != nil && *report.Attempted:
		return modelActivity{State: "attempted", Description: "Request attempted. " + describeModels(nil, "", report.RequestedModel), Cost: cost}
	default:
		return modelActivity{State: "unknown", Description: "Execution unknown."}
	}
}

func describeAuthor(d workDiagnostics, provider authorDiagnostics, progress experimentruntime.Progress, profile experimentruntime.Profile, session agentsignature.Identity) modelActivity {
	if d.WorkStepOutcome == "skipped" || d.Route != nil && *d.Route == "docs-no-change" && len(d.Stages) == 0 && d.AuthorAttempts == nil {
		return modelActivity{State: "skipped", Description: "Skipped.", Cost: new(float64)}
	}
	var served []string
	for _, generation := range provider.Generations {
		if !generation.Conflict {
			served = append(served, generation.ServedModel)
		}
	}
	state, description := "unknown", "Execution unknown."
	switch {
	case d.AuthorAttempts != nil && *d.AuthorAttempts == 0:
		return modelActivity{State: "no-requests", Description: "No OpenRouter requests recorded.", Cost: new(float64)}
	case d.AuthorAttempts != nil:
		state = "attempted"
		description = fmt.Sprintf("OpenRouter request attempts %d.", *d.AuthorAttempts)
	case progress.Stage == string(doccycle.AuthorStage) && progress.PID > 0:
		state = "runtime-started"
		description = "Runtime started. Request count unknown."
	case len(d.Stages) != 0:
		state = "stage-attempted"
		description = "Stage attempted. Runtime launch unconfirmed."
	}
	activity := modelActivity{State: state, Description: description + " " + describeModels(served, session.Model, profile.Model), Cost: d.AuthorCost}
	if activity.Cost == nil {
		activity.Subtotal = validCost(provider.ReportedPortion)
	}
	return activity
}

func describeModels(served []string, session, requested string) string {
	var models []string
	for _, model := range served {
		if safeModel(model) {
			models = append(models, "`"+model+"`")
		}
	}
	if len(models) != 0 {
		slices.Sort(models)
		return "Served by " + strings.Join(slices.Compact(models), ", ") + "."
	}
	if safeModel(session) {
		return "OpenCode session model `" + session + "`. Served model unknown."
	}
	if safeModel(requested) {
		return "Served model unknown. Requested `" + requested + "`."
	}
	return "Served model unknown."
}

func safeModel(model string) bool {
	key := os.Getenv("OPENROUTER_API_KEY")
	return model != "" && (key == "" || !strings.Contains(model, key)) && (agentsignature.Signature{Model: model}).Validate() == nil
}

func validCost(cost *float64) *float64 {
	if cost == nil || *cost < 0 || math.IsNaN(*cost) || math.IsInf(*cost, 0) {
		return nil
	}
	return cost
}

func costText(cost *float64) string {
	if cost == nil {
		return "Cost unknown"
	}
	return "USD " + strconv.FormatFloat(*cost, 'g', 12, 64)
}

func totalCost(jev, author modelActivity) string {
	total := 0.0
	for _, value := range []*float64{jev.Cost, author.Cost} {
		if value != nil {
			total += *value
		}
	}
	if jev.Cost != nil && author.Cost != nil {
		return costText(validCost(&total))
	}
	if author.Cost == nil && author.Subtotal != nil {
		total += *author.Subtotal
	}
	if total > 0 && validCost(&total) != nil {
		return "Unknown (reported subtotal " + costText(&total) + ")"
	}
	return "Unknown"
}

func formatWorkSummary(d workDiagnostics, report workcycle.Report, logURL string) string {
	var body strings.Builder
	status, route := "unknown", "unknown"
	if d.Status != nil {
		status = *d.Status
	}
	if d.Route != nil {
		route = *d.Route
	}
	outcome := d.WorkStepOutcome
	switch outcome {
	case "success", "failure", "cancelled", "skipped":
	default:
		outcome = "unknown"
	}
	fmt.Fprintf(&body, "## Documentation work\n\n- **Result** `%s` (Actions step `%s`).", status, outcome)
	if workStopReason(report) == "rejected-result-format" {
		body.WriteString(" The author returned an invalid result.")
	}
	fmt.Fprintf(&body, "\n- **Route** `%s`.", route)
	if d.RoutingReason != "" {
		fmt.Fprintf(&body, " %s", d.RoutingReason)
	}
	jev, author := d.JevActivity, d.AuthorActivity
	fmt.Fprintf(&body, "\n- **Jev** %s %s.\n- **Author** %s %s.\n- **Total model cost** %s.\n",
		jev.Description, costText(jev.Cost), author.Description, costText(author.Cost), totalCost(jev, author))
	if d.RejectedDiff {
		body.WriteString("\nThe work diagnostics artifact contains the rejected diff.\n")
	}
	if logURL != "" && (d.RoutingReason == "" || outcome != "success" || status == "needs-human") {
		fmt.Fprintf(&body, "\n[View workflow logs](%s)\n", logURL)
	}
	return body.String()
}

func workflowLogURL() string {
	server, repo, run, attempt := os.Getenv("GITHUB_SERVER_URL"), os.Getenv("GITHUB_REPOSITORY"),
		os.Getenv("GITHUB_RUN_ID"), os.Getenv("GITHUB_RUN_ATTEMPT")
	if server == "" || repo == "" || run == "" || attempt == "" {
		return ""
	}
	return fmt.Sprintf("%s/%s/actions/runs/%s/attempts/%s", server, repo, run, attempt)
}
