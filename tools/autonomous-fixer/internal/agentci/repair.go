package agentci

import (
	"context"
	"crypto/sha256"
	"fmt"
	"regexp"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type RepairPlan struct {
	Ready     bool
	Failures  []autorequest.Failure
	RetryRuns []int64
	Human     []string
	jobs      map[string]string
}

func PlanRepair(ctx context.Context, client *fixer.GitHubClient, snapshot Snapshot) (RepairPlan, error) {
	return planRepair(ctx, client, snapshot, true)
}

// RecheckFailures validates the diagnostics in a dispatched request against current CI.
// An unrelated optional check may have restarted since the coordinator formed the batch.
func RecheckFailures(ctx context.Context, client *fixer.GitHubClient, snapshot Snapshot, requested, consumed []autorequest.Failure) ([]autorequest.Failure, error) {
	plan, err := planRepair(ctx, client, snapshot, false)
	if err != nil || !plan.Ready {
		return nil, err
	}
	plan.Failures = slices.DeleteFunc(plan.Failures, func(current autorequest.Failure) bool {
		return slices.ContainsFunc(consumed, func(prior autorequest.Failure) bool {
			return plan.MatchesFailure(current, prior)
		})
	})
	var failures []autorequest.Failure
	for _, want := range requested {
		if failure, found := plan.ResolveFailure(want); found {
			failures = append(failures, failure)
		}
	}
	return failures, nil
}

func planRepair(ctx context.Context, client *fixer.GitHubClient, snapshot Snapshot, newBatch bool) (RepairPlan, error) {
	var plan RepairPlan
	if len(snapshot.Diagnostics) != 0 {
		return plan, nil
	}
	checks := snapshot.SelectedChecks()
	waitForAll := newBatch && !slices.ContainsFunc(checks, func(check Check) bool { return check.Required })
	var completed []Check
	for _, check := range checks {
		if !terminalCheck(check.Status) || check.Workflow != "" && check.action.Status != "completed" {
			if check.Required || waitForAll {
				return plan, nil
			}
			continue
		}
		completed = append(completed, check)
	}
	if len(completed) == 0 {
		return plan, nil
	}
	plan.Ready = true

	plan.jobs = make(map[string]string)
	retry := make(map[int64]string)
	blocked := make(map[int64]bool)
	for _, check := range completed {
		if check.Status != "failed" && check.Status != "cancelled" {
			continue
		}
		if err := ctx.Err(); err != nil {
			return RepairPlan{}, err
		}
		name := repairName(client.RedactGitHubToken(check.Name))
		if check.Workflow != "" && check.Workflow != check.Name {
			name = repairName(client.RedactGitHubToken(check.Workflow)) + " / " + name
		}
		evidence := inspectFailure(ctx, client, check)
		if err := ctx.Err(); err != nil {
			return RepairPlan{}, err
		}
		switch {
		case evidence.infrastructure && retryableRun(check.action):
			retry[check.action.ID] = name
		case evidence.infrastructure:
			plan.Human = append(plan.Human, name+" needs a runner or service investigation.")
			blocked[check.action.ID] = true
		case len(evidence.diagnostics) > 0 && check.Conclusion == "failure":
			job := failureJob(check)
			for _, excerpt := range evidence.diagnostics {
				key := failureKey(job, excerpt)
				if _, seen := plan.jobs[key]; seen {
					continue
				}
				plan.jobs[key] = job
				plan.Failures = append(plan.Failures, autorequest.Failure{Key: key, Name: name,
					URL: client.ActionsJobURL(check.action.ID, check.ID), Excerpt: excerpt})
			}
			blocked[check.action.ID] = true
		case evidence.unavailable:
			plan.Human = append(plan.Human, "The failure evidence for "+name+" could not be read.")
			blocked[check.action.ID] = true
		default:
			plan.Human = append(plan.Human, name+" needs a manual diagnosis.")
			blocked[check.action.ID] = true
		}
	}
	for run, name := range retry {
		if blocked[run] {
			plan.Human = append(plan.Human, name+" shares a run with other jobs that need a diagnosis or fix.")
		} else {
			plan.RetryRuns = append(plan.RetryRuns, run)
		}
	}
	slices.Sort(plan.RetryRuns)
	slices.Sort(plan.Human)
	plan.Human = slices.Compact(plan.Human)
	return plan, nil
}

func terminalCheck(status string) bool {
	switch status {
	case "passed", "skipped", "neutral", "failed", "cancelled":
		return true
	default:
		return false
	}
}

var diagnosticLocation = regexp.MustCompile(`(?::\d+(?::\d+)?| at line \d+, column \d+)\.$`)
var testFailureCount = regexp.MustCompile(`Maven reported \d+ failed tests\.`)

func failureJob(check Check) string {
	return fmt.Sprintf("%d\x00%d\x00%s", check.AppID, check.action.WorkflowID, check.Name)
}

func failureKey(job, excerpt string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(job+"\x00"+excerpt)))
}

func legacyFailureKey(job, excerpt string) string {
	signatures := strings.Split(excerpt, "\n")
	for i, signature := range signatures {
		signature = diagnosticLocation.ReplaceAllString(signature, ".")
		signatures[i] = testFailureCount.ReplaceAllString(signature, "Maven reported failed tests.")
	}
	slices.Sort(signatures)
	return failureKey(job, strings.Join(slices.Compact(signatures), "\n"))
}

func (plan RepairPlan) MatchesFailure(current, prior autorequest.Failure) bool {
	if current.Key == prior.Key {
		return true
	}
	job, found := plan.jobs[current.Key]
	if !found {
		return false
	}
	if suite := jestSuiteForCase(current.Excerpt); suite != "" && failureKey(job, suite) == prior.Key {
		return true
	}
	for excerpt := range strings.SplitSeq(prior.Excerpt, "\n") {
		if matchesDiagnostic(current.Excerpt, excerpt) {
			return legacyFailureKey(job, prior.Excerpt) == prior.Key
		}
	}
	return false
}

func matchesDiagnostic(current, prior string) bool {
	return current == prior || prior != "" && jestSuiteForCase(current) == prior
}

func (plan RepairPlan) ResolveFailure(requested autorequest.Failure) (autorequest.Failure, bool) {
	if i := slices.IndexFunc(plan.Failures, func(current autorequest.Failure) bool { return current.Key == requested.Key }); i >= 0 {
		return plan.Failures[i], true
	}
	var resolved autorequest.Failure
	for excerpt := range strings.SplitSeq(requested.Excerpt, "\n") {
		i := slices.IndexFunc(plan.Failures, func(current autorequest.Failure) bool {
			return matchesDiagnostic(current.Excerpt, excerpt) && plan.MatchesFailure(current, requested)
		})
		if i < 0 {
			return autorequest.Failure{}, false
		}
		resolved = plan.Failures[i]
	}
	resolved.Key, resolved.Excerpt = requested.Key, requested.Excerpt
	return resolved, true
}

func retryableRun(run actionRun) bool {
	return run.Attempt == 1 && (run.Event == "push" || run.Event == "pull_request") && run.Conclusion == "failure"
}

func repairName(name string) string {
	name = strings.Join(strings.Fields(redactProviderTokens(name)), " ")
	if name == "" {
		return "Unnamed check"
	}
	runes := []rune(name)
	if len(runes) > 160 {
		return string(runes[:160]) + "…"
	}
	return name
}
