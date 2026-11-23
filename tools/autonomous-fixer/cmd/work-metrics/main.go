package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/tasktriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

type pathsFlag []string

func (p *pathsFlag) String() string { return fmt.Sprint([]string(*p)) }
func (p *pathsFlag) Set(value string) error {
	*p = append(*p, value)
	return nil
}

type counts struct {
	DeclarationCorrections int `json:"declaration_corrections"`
	AuthorHelpers          int `json:"author_helpers"`
	HelperSetupFailures    int `json:"helper_setup_failures"`
	SkippedJevRequests     int `json:"skipped_jev_requests"`
	DuplicateJevReports    int `json:"duplicate_jev_reports"`
	StageAttempts          int `json:"stage_attempts"`
	NativeInvocations      int `json:"native_invocations"`
	Completed              int `json:"completed_invocations"`
	Failed                 int `json:"failed_invocations"`
	Unfinished             int `json:"unfinished_invocations"`
	MissingProcess         int `json:"missing_process_records"`
	ObservedRequests       int `json:"observed_model_requests"`
	UnknownUsage           int `json:"invocations_with_unknown_usage"`
	JevReports             int `json:"jev_reports"`
	FailedJevReports       int `json:"failed_jev_reports"`
	UnknownJevCost         int `json:"jev_reports_with_unknown_cost"`
}

type metrics struct {
	Workflow                  string                     `json:"workflow"`
	StaticChecks              []checkrunner.Record       `json:"static_checks,omitempty"`
	StaticCheckExecutions     int                        `json:"static_check_executions"`
	StaticCheckReuses         int                        `json:"static_check_reuses"`
	StaticCheckSeconds        float64                    `json:"static_check_seconds"`
	ReviewDecisions           []workcycle.ReviewDecision `json:"review_decisions,omitempty"`
	ReviewProfile             *experimentruntime.Profile `json:"review_profile,omitempty"`
	AuthorHelperProfile       *experimentruntime.Profile `json:"author_helper_profile,omitempty"`
	ExecutionRoute            string                     `json:"execution_route"`
	AutomaticNoChangeVerified bool                       `json:"automatic_no_change_verified"`
	TaskID                    string                     `json:"task_id"`
	TaskKind                  string                     `json:"task_kind"`
	Outcome                   string                     `json:"outcome"`
	Profile                   experimentruntime.Profile  `json:"profile"`
	RunDirectory              string                     `json:"run_directory"`
	ResumeFrom                string                     `json:"resume_from,omitempty"`
	ResumeMode                string                     `json:"resume_mode,omitempty"`
	PricingVersion            string                     `json:"pricing_version"`
	APIEquivalentUSD          *float64                   `json:"api_equivalent_usd"`
	KnownCostUSD              float64                    `json:"known_cost_usd"`
	JevReportedUSD            *float64                   `json:"jev_reported_usd"`
	JevKnownUSD               float64                    `json:"jev_known_cost_usd"`
	OpenRouter                *openrouterusage.Report    `json:"openrouter,omitempty"`
	CombinedUSD               *float64                   `json:"combined_mixed_basis_usd"`
	CombinedKnownUSD          float64                    `json:"combined_known_cost_usd"`
	Elapsed                   float64                    `json:"completed_runtime_seconds"`
	Counts                    counts                     `json:"counts"`
	Stages                    []map[string]any           `json:"stages"`
	Jev                       []map[string]any           `json:"jev"`
	SavingsStatus             string                     `json:"savings_status"`
	Notes                     []string                   `json:"notes"`
}

func readJSON(path string, value any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, value); err != nil {
		return fmt.Errorf("read %s: %w", path, err)
	}
	return nil
}

func collect(root string, jevPaths []string) (metrics, error) {
	m := metrics{RunDirectory: root, Outcome: "unknown", PricingVersion: experimentcost.PricingVersion,
		Stages: []map[string]any{}, Jev: []map[string]any{}, SavingsStatus: "not_measured",
		Notes: []string{
			"Native cost is an API-equivalent at the recorded pricing snapshot, not a subscription charge or invoice.",
			"Jev cost is the provider-reported amount. The combined amount mixes this charge with native API-equivalent estimates.",
			"Native usage covers this run directory, including declaration-only corrections and author helpers. Earlier native invocations in resume sources or retries, preparation, human review, and infrastructure are excluded.",
			"Completed runtime seconds sum process durations, including overlapping invocations.",
			"Verified routing evidence identifies the consumed classifier. Additional Jev reports are caller-associated costs; their inclusion does not establish consumption.",
			"Unknown usage is not zero. Observed request counts exclude requests whose usage could not be read.",
			"Savings are not measured: no comparable baseline or avoided model work has been established.",
			"A resumed run can reuse a classifier request recorded in its source run. Deduplicate provider request IDs before adding costs across run directories.",
		}}
	var cfg localwork.Config
	if err := readJSON(filepath.Join(root, "config.json"), &cfg); err != nil {
		return m, err
	}
	m.TaskID, m.TaskKind, m.Profile = cfg.TaskID, cfg.TaskKind, cfg.Profile
	m.AuthorHelperProfile = cfg.AuthorHelperProfile
	if cfg.Hosted {
		var provider openrouterusage.Report
		if err := readJSON(filepath.Join(root, openrouterusage.Filename), &provider); err == nil {
			m.OpenRouter = &provider
		} else if !os.IsNotExist(err) {
			return m, err
		}
		m.Notes = append(m.Notes, "OpenRouter usage and reported cost cover requests observed by the hosted relay. They are separate from native API-equivalent estimates.")
	}
	m.Workflow = cfg.Workflow
	if m.Workflow == "" {
		m.Workflow = "staged"
	}
	if cfg.TaskKind == "documentation" {
		m.Workflow = "documentation"
	}
	m.ResumeFrom, m.ResumeMode = cfg.ResumeFrom, cfg.ResumeMode
	m.ReviewProfile = cfg.ReviewProfile
	var outcome workcycle.Report
	if err := readJSON(filepath.Join(root, "report.json"), &outcome); err == nil {
		if outcome.Status != "" {
			m.Outcome = outcome.Status
		}
	} else if !os.IsNotExist(err) {
		return m, err
	}
	m.ReviewDecisions = outcome.ReviewDecisions
	checkPaths, err := filepath.Glob(filepath.Join(root, "evidence", "check-*", "result.json"))
	if err != nil {
		return m, err
	}
	for _, path := range checkPaths {
		var record checkrunner.Record
		if e := readJSON(path, &record); e != nil {
			return m, e
		}
		m.StaticChecks = append(m.StaticChecks, record)
		m.StaticCheckSeconds += record.ElapsedSeconds
		if record.ReusedFrom == "" {
			m.StaticCheckExecutions++
		} else {
			m.StaticCheckReuses++
		}
	}
	if cfg.TaskKind == "documentation" {
		m.ExecutionRoute = "docs-assess"
		if cfg.DocumentationTriage != nil {
			m.ExecutionRoute = "unverified"
		}
	}
	if outcome.Request.DocumentationEntry != nil {
		m.ExecutionRoute = outcome.Request.DocumentationEntry.Route
	}
	paths, err := filepath.Glob(filepath.Join(root, "step-*"))
	if err != nil {
		return m, err
	}
	corrections, err := filepath.Glob(filepath.Join(root, "step-*", "declaration-correction"))
	if err != nil {
		return m, err
	}
	paths = append(paths, corrections...)
	helpers, err := filepath.Glob(filepath.Join(root, "step-*-author", "helper-*"))
	if err != nil {
		return m, err
	}
	paths = append(paths, helpers...)
	sort.Strings(paths)
	complete := true
	for _, dir := range paths {
		info, err := os.Stat(dir)
		if err != nil {
			return m, err
		}
		if !info.IsDir() {
			continue
		}
		stageName, err := filepath.Rel(root, dir)
		if err != nil {
			return m, err
		}
		stage := map[string]any{"stage": stageName}
		authorHelper := strings.HasPrefix(filepath.Base(dir), "helper-")
		switch name := filepath.Base(dir); {
		case name == "declaration-correction":
			m.Counts.DeclarationCorrections++
			stage["kind"] = "declaration-correction"
			stage["parent_stage"] = filepath.Base(filepath.Dir(dir))
		case authorHelper:
			m.Counts.AuthorHelpers++
			stage["kind"] = "author-helper"
			stage["parent_stage"] = filepath.Base(filepath.Dir(dir))
		default:
			m.Counts.StageAttempts++
		}
		m.Stages = append(m.Stages, stage)
		var process experimentruntime.Result
		processErr := readJSON(filepath.Join(dir, "process.json"), &process)
		switch {
		case os.IsNotExist(processErr):
			if authorHelper {
				var setup struct {
					Stage   string
					Started *bool `json:"native_process_started"`
					Error   string
				}
				err := readJSON(filepath.Join(dir, "helper-setup-failure.json"), &setup)
				if err != nil && !os.IsNotExist(err) {
					return m, err
				}
				if setup.Stage == "author-helper" && setup.Started != nil && !*setup.Started && setup.Error != "" {
					m.Counts.HelperSetupFailures++
					stage["status"] = "helper_setup_failed"
					stage["error"] = setup.Error
					continue
				}
			}
			complete = false
			m.Counts.MissingProcess++
			stage["status"] = "missing_process_record"
		case processErr != nil:
			return m, processErr
		case process.FinishedAt.IsZero():
			m.Counts.NativeInvocations++
			complete = false
			m.Counts.Unfinished++
			stage["status"] = "unfinished"
		default:
			m.Counts.NativeInvocations++
			m.Counts.Completed++
			if process.ExitCode != 0 {
				m.Counts.Failed++
			}
			stage["status"], stage["exit_code"] = "finished", process.ExitCode
			seconds := process.Duration.Seconds()
			m.Elapsed += seconds
			stage["elapsed_seconds"] = seconds
		}
		profile := cfg.Profile
		if authorHelper && cfg.AuthorHelperProfile != nil {
			profile = *cfg.AuthorHelperProfile
		}
		if err := readJSON(filepath.Join(dir, "profile.json"), &profile); err != nil && !os.IsNotExist(err) {
			return m, err
		}
		stage["profile"] = profile
		usage, err := experimentusage.Read(profile.Runtime, profile.Model, dir)
		if err != nil {
			complete = false
			m.Counts.UnknownUsage++
			stage["usage_error"] = err.Error()
			continue
		}
		m.Counts.ObservedRequests += usage.RequestCount
		stage["usage"] = usage
		costs := []experimentcost.Estimate{}
		stageComplete := usage.Complete && len(usage.Models) > 0
		if authorHelper && (cfg.AuthorHelperProfile == nil || *cfg.AuthorHelperProfile != profile) {
			stage["profile_mismatch"] = "The archived helper profile differs from the configured profile."
			stageComplete = false
		}
		for _, model := range usage.Models {
			if authorHelper && model.Model != profile.Model {
				stage["profile_mismatch"] = "The observed helper model differs from the configured model."
				stageComplete = false
			}
			cost := experimentcost.Calculate(model.Model, model.Requests)
			costs = append(costs, cost)
			if cost.TotalUSD == nil {
				stageComplete = false
			} else {
				m.KnownCostUSD += *cost.TotalUSD
			}
		}
		stage["api_equivalent"] = costs
		if !stageComplete {
			complete = false
			m.Counts.UnknownUsage++
		}
	}
	if m.Counts.Completed == 0 {
		complete = false
		if m.Counts.StageAttempts == 0 && m.ExecutionRoute == "docs-no-change" {
			if err := verifyAutomaticNoChange(root, cfg, outcome); err == nil {
				complete = true
				m.AutomaticNoChangeVerified = true
			} else {
				m.Notes = append(m.Notes, "Automatic no-change completion could not be verified: "+err.Error())
			}
		}
	}
	if complete {
		m.APIEquivalentUSD = &m.KnownCostUSD
	}
	jevComplete := true
	type source struct {
		path, association string
		report            doctriage.Report
	}
	sources := []source{}
	routingPaths, err := filepath.Glob(filepath.Join(root, "evidence", "engineering-triage-*.json"))
	if err != nil {
		return m, err
	}
	for _, path := range routingPaths {
		var report tasktriage.Report
		if err := readJSON(path, &report); err != nil {
			return m, err
		}
		if err := tasktriage.Validate(report); err != nil {
			return m, err
		}
		if report.Packet.TaskID != cfg.TaskID {
			return m, fmt.Errorf("engineering triage task differs from run")
		}
		mode := "engineering-" + report.Packet.Phase
		if !report.RequestAttempted {
			mode = "engineering-rule"
		}
		sources = append(sources, source{path, "run_recorded", doctriage.Report{Mode: mode, RequestAttempted: report.RequestAttempted, PolicyVersion: report.Version, PacketID: report.Packet.TaskID, PacketSHA256: tasktriage.Digest(report.Packet), InputSHA256: report.InputSHA256, Response: report.Response, Error: report.Error}})
	}
	if cfg.DocumentationTriage != nil {
		proof, err := readRoutingProof(root, cfg, outcome)
		if err != nil {
			jevComplete = false
			m.Counts.UnknownJevCost++
			m.Notes = append(m.Notes, "Consumed classifier cost could not be verified: "+err.Error())
		} else {
			sources = append(sources, source{filepath.Join(root, "evidence/documentation-routing.json"), "controller_verified", proof.Report})
		}
	}
	for _, path := range jevPaths {
		canonical, err := filepath.EvalSymlinks(path)
		if err != nil {
			return m, err
		}
		canonical, err = filepath.Abs(canonical)
		if err != nil {
			return m, err
		}
		var report doctriage.Report
		if err := readJSON(path, &report); err != nil {
			return m, err
		}
		sources = append(sources, source{canonical, "caller_associated", report})
	}
	type seenCost struct {
		report doctriage.Report
		cost   *float64
		path   string
	}
	seen := map[string]seenCost{}
	for _, src := range sources {
		report := src.report
		entry := map[string]any{"mode": report.Mode, "request_attempted": report.RequestAttempted || report.Response != nil, "path": src.path, "association": src.association, "policy_version": report.PolicyVersion, "packet_id": report.PacketID, "packet_sha256": report.PacketSHA256, "error": report.Error, "reported_cost_usd": nil, "counted": true, "reused_from_resume": src.association == "controller_verified" && cfg.ResumeFrom != ""}
		m.Jev = append(m.Jev, entry)
		response := report.Response
		key := "path:" + src.path
		var cost *float64
		skipped := (src.association == "controller_verified" && doctriage.IsPreflightSkip(report)) || (src.association == "run_recorded" && report.Mode == "engineering-rule")
		if doctriage.IsPreflightSkip(report) {
			key = "preflight:" + report.InputSHA256 + ":" + report.PacketSHA256
		}
		if skipped {
			zero := 0.0
			cost = &zero
			entry["skipped"] = true
		}
		if response != nil {
			if response.ID != "" {
				key = "request:" + response.ID
			}
			entry["request_id"], entry["model"], entry["usage"] = response.ID, response.Model, response.Usage
			if response.Usage != nil && response.Usage.Cost != nil && *response.Usage.Cost >= 0 && !math.IsInf(*response.Usage.Cost, 0) && !math.IsNaN(*response.Usage.Cost) {
				cost = response.Usage.Cost
			}
		}
		if prior, exists := seen[key]; exists {
			if doctriage.IsPreflightSkip(report) && reflect.DeepEqual(prior.report, report) {
				cost = prior.cost
			}
			if !reflect.DeepEqual(prior.cost, cost) {
				return m, fmt.Errorf("conflicting costs for Jev %s", key)
			}
			m.Counts.DuplicateJevReports++
			entry["counted"], entry["duplicate_of"] = false, prior.path
			continue
		}
		seen[key] = seenCost{report: report, cost: cost, path: src.path}
		m.Counts.JevReports++
		if skipped {
			m.Counts.SkippedJevRequests++
		} else if report.Mode != "preflight" && (report.Error != "" || response == nil) {
			m.Counts.FailedJevReports++
		}
		if cost == nil {
			jevComplete = false
			m.Counts.UnknownJevCost++
			continue
		}
		entry["reported_cost_usd"] = cost
		m.JevKnownUSD += *cost
	}
	if jevComplete {
		m.JevReportedUSD = &m.JevKnownUSD
	}
	m.CombinedKnownUSD = m.KnownCostUSD + m.JevKnownUSD
	if complete && jevComplete {
		m.CombinedUSD = &m.CombinedKnownUSD
	}
	return m, nil
}

func run(args []string) error {
	flags := flag.NewFlagSet("work-metrics", flag.ContinueOnError)
	var jevPaths pathsFlag
	flags.Var(&jevPaths, "jev-report", "Jev report to include; repeat for separate requests")
	output := flags.String("output", "", "new metrics file; defaults to metrics.json in the run directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 1 {
		return fmt.Errorf("usage: work-metrics [--jev-report path] [--output new-file] /absolute/path/to/work-output")
	}
	root, err := filepath.Abs(flags.Arg(0))
	if err != nil {
		return err
	}
	result, err := collect(root, jevPaths)
	if err != nil {
		return err
	}
	b, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return err
	}
	path := *output
	mode := os.O_WRONLY | os.O_CREATE | os.O_EXCL
	if path == "" {
		path = filepath.Join(root, "metrics.json")
		mode = os.O_WRONLY | os.O_CREATE | os.O_TRUNC
	}
	file, err := os.OpenFile(path, mode, 0600)
	if err != nil {
		return err
	}
	_, writeErr := file.Write(append(b, '\n'))
	closeErr := file.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	summary := map[string]any{"counts": result.Counts, "outcome": result.Outcome, "execution_route": result.ExecutionRoute, "automatic_no_change_verified": result.AutomaticNoChangeVerified, "combined_mixed_basis_usd": result.CombinedUSD, "known_cost_usd": result.CombinedKnownUSD, "metrics": path}
	if result.OpenRouter != nil {
		summary["openrouter_reported_usd"] = result.OpenRouter.ReportedCostUSD
	}
	return json.NewEncoder(os.Stdout).Encode(summary)
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
