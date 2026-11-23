package autofollow

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func (c *controller) plan(ctx context.Context, book ledger, seen map[string]bool) (agentci.RepairPlan, bool, error) {
	required, diagnostics := agentci.Required(ctx, c.client, c.pr.Base.Ref, c.options.RequiredChecks)
	snapshot, err := agentci.Read(ctx, c.client, c.pr.Head.SHA, c.pr.MergeSHA, required)
	if err != nil {
		return agentci.RepairPlan{}, false, err
	}
	snapshot.Diagnostics = append(snapshot.Diagnostics, diagnostics...)
	plan, err := agentci.PlanRepair(ctx, c.client, snapshot)
	if err != nil {
		return plan, false, err
	}
	var priorRequests []int64
	if !plan.Ready {
		checks := snapshot.SelectedChecks()
		waiting := slices.ContainsFunc(checks, func(check agentci.Check) bool {
			return check.Status == "pending" || check.Status == "missing"
		})
		if len(snapshot.Diagnostics) > 0 || slices.ContainsFunc(checks, func(check agentci.Check) bool {
			return !waiting && (check.Status == "unknown" || check.Status == "cancelled")
		}) {
			plan.Human = append(plan.Human, "GitHub did not provide enough CI evidence for an automatic repair. Check the current CI observation if the failure persists.")
		}
	} else {
		var seenInputs []autorequest.Failure
		for key := range seen {
			seenInputs = append(seenInputs, autorequest.Failure{Key: key})
		}
		for _, item := range book.requests {
			if item.request.Head == c.pr.Head.SHA {
				for _, prior := range item.request.Failures {
					if seen[prior.Key] {
						seenInputs = append(seenInputs, prior)
					}
				}
			}
		}
		filtered := plan.Failures[:0]
		for _, failure := range plan.Failures {
			if slices.ContainsFunc(seenInputs, func(prior autorequest.Failure) bool {
				return plan.MatchesFailure(failure, prior)
			}) {
				continue
			}
			previous, err := c.previousRepair(ctx, book, plan, failure)
			if err != nil {
				if ctx.Err() != nil {
					return plan, false, ctx.Err()
				}
				plan.Human = append(plan.Human, fmt.Sprintf("GitHub could not verify the repair history for %s. Check prior revisions before retrying.", failure.Name))
				continue
			}
			if previous > 0 {
				plan.Human = append(plan.Human, fmt.Sprintf("%s failed again after an automatic repair. %s Review the previous revision before retrying.", failure.Name, failure.Excerpt))
				priorRequests = append(priorRequests, previous)
				continue
			}
			filtered = append(filtered, failure)
		}
		plan.Failures = filtered
	}
	if len(plan.Human) == 0 {
		return plan, !plan.Ready && repairStillRunning(snapshot), nil
	}
	slices.Sort(plan.Human)
	plan.Human = slices.Compact(plan.Human)
	var note strings.Builder
	fmt.Fprintf(&note, "### CI needs a maintainer check\n\nPR head `%s`\n", c.pr.Head.SHA)
	for _, line := range plan.Human {
		fmt.Fprintf(&note, "\n- %s", markdownText(line))
	}
	slices.Sort(priorRequests)
	for _, id := range slices.Compact(priorRequests) {
		fmt.Fprintf(&note, "\n\n[Previous revision request %d](#issuecomment-%d)", id, id)
	}
	key := "ci:" + c.pr.Head.SHA + ":" + digest(plan.Human)
	return plan, false, c.note(ctx, book, key, note.String())
}

func repairStillRunning(snapshot agentci.Snapshot) bool {
	if len(snapshot.Diagnostics) != 0 {
		return false
	}
	checks := snapshot.SelectedChecks()
	required := slices.ContainsFunc(checks, func(check agentci.Check) bool { return check.Required })
	var failed, running bool
	for _, check := range checks {
		if check.Status == "failed" {
			failed = true
		}
		if required && !check.Required {
			continue
		}
		switch check.Status {
		case "missing", "unknown":
			return false
		case "pending":
			running = true
		}
	}
	return failed && running
}

func (c *controller) previousRepair(ctx context.Context, book ledger, plan agentci.RepairPlan, failure autorequest.Failure) (int64, error) {
	for i := len(book.requests) - 1; i >= 0; i-- {
		request := book.requests[i]
		assignment, found := book.assignments[request.id]
		if !found || book.results[request.id] == "" || !assignment.Automatic {
			continue
		}
		if !slices.Contains(assignment.FailureKeys, failure.Key) && !slices.ContainsFunc(request.request.Failures, func(prior autorequest.Failure) bool {
			return slices.Contains(assignment.FailureKeys, prior.Key) && plan.MatchesFailure(failure, prior)
		}) {
			continue
		}
		for sha, requestID := range book.published {
			if requestID != request.id {
				continue
			}
			ancestor, err := c.ancestor(ctx, sha)
			if err != nil {
				return 0, err
			}
			if ancestor {
				return requestID, nil
			}
		}
	}
	return 0, nil
}

func (c *controller) retryInfrastructure(ctx context.Context, book ledger, report *Report, id int64) error {
	key := fmt.Sprintf("retry:%d", id)
	link := fmt.Sprintf("https://github.com/%s/actions/runs/%d", c.repo.FullName, id)
	if requested := book.markers[c.marker(key)]; !requested.IsZero() {
		if time.Since(requested) < deliveryGrace {
			return nil
		}
		message := fmt.Sprintf("GitHub still reports the first attempt of [this CI run](%s) after the automatic retry request. Check the run and rerun its failed jobs if needed.", link)
		report.Human = append(report.Human, "GitHub has yet to show the requested CI retry.")
		return c.note(ctx, book, fmt.Sprintf("retry-unconfirmed:%d", id), message)
	}
	if !c.options.Apply {
		report.RetryRuns = append(report.RetryRuns, id)
		return nil
	}
	run, err := c.infrastructureRun(ctx, id)
	if err != nil || run.RunAttempt != 1 || run.Status != "completed" || run.Conclusion != "failure" {
		return err
	}
	message := fmt.Sprintf("The workflow allows one automatic retry for runner or service failures in [this CI run](%s).", link)
	if err := c.note(ctx, book, key, message); err != nil {
		return err
	}
	if err := c.current(ctx); err != nil {
		return err
	}
	if err := c.client.RerunFailedActionsJobs(ctx, id); err != nil {
		observed, readErr := c.infrastructureRun(ctx, id)
		if readErr != nil || observed.RunAttempt <= 1 {
			diagnostic := fmt.Sprintf("GitHub did not confirm the retry for [this CI run](%s). Check the run before rerunning its failed jobs.", link)
			return errors.Join(err, readErr, c.note(ctx, book, fmt.Sprintf("retry-unconfirmed:%d", id), diagnostic))
		}
	}
	report.RetryRuns = append(report.RetryRuns, id)
	return nil
}

func (c *controller) infrastructureRun(ctx context.Context, id int64) (fixer.WorkflowRun, error) {
	var run fixer.WorkflowRun
	err := c.client.Request(ctx, "GET", fmt.Sprintf("/actions/runs/%d", id), nil, &run)
	if err == nil && (run.ID != id || run.HeadSHA != c.pr.Head.SHA && run.HeadSHA != c.pr.MergeSHA) {
		err = fmt.Errorf("CI retry run does not match the pull request")
	}
	return run, err
}
