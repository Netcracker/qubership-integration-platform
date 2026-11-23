package docgithub

import (
	"context"
	"fmt"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

type ScheduleStop struct {
	Actor     string
	Basis     string
	Current   ScheduledAttempt
	Reason    string
	Signature agentsignature.Signature
}

func RecordScheduleStop(ctx context.Context, c *Client, plan Plan, stop ScheduleStop) (bool, error) {
	if err := validatePublicationPlan(plan); err != nil {
		return false, err
	}
	claimAttempt, ok := plan.workflowAttempt()
	if !ok || stop.Current.RunID != claimAttempt.RunID || stop.Current.Attempt < claimAttempt.Attempt ||
		plan.Repository != c.Repository || stop.Basis != scheduleBasis(plan) {
		return false, fmt.Errorf("documentation stop does not match the claimed run and basis")
	}
	if err := stop.Signature.Validate(); err != nil {
		return false, err
	}
	message, err := stopMessage(stop.Reason)
	if err != nil {
		return false, err
	}
	if err = Authorize(ctx, c, stop.Actor); err != nil {
		return false, err
	}

	p := &publisher{client: c, plan: plan}
	found, err := p.readState(ctx)
	if err != nil {
		return false, err
	}
	comment := p.claim
	var body string
	if found {
		if stop.Reason != "publish-stopped" || (p.state.Status != "publishing" && p.state.Status != "completing") {
			return false, nil
		}
		comment = p.stateComment
		stateStart := strings.LastIndex(comment.Body, "\n\n<!-- qip-docs-state:")
		if stateStart < 0 {
			return false, fmt.Errorf("documentation state marker has no paragraph boundary")
		}
		notice := "\n\n" + message
		if p.state.Decision == "no_change" && p.state.Status == "completing" {
			notice += " If an issue or project update is unresolved, verify the issue state and project status before retrying. You can also run **QIP documentation agent** for this issue with " +
				"`operation` set to `reconcile` and `apply` enabled."
		}
		if !strings.HasSuffix(comment.Body[:stateStart], notice) {
			body = comment.Body[:stateStart] + notice + comment.Body[stateStart:]
		}
	} else if comment.ID > 0 {
		body = stop.Signature.Append(fmt.Sprintf("%s\n\n%s\n\n[View the workflow run](%s)",
			scheduleReceipt(stop.Basis, claimAttempt), message, stop.Current.url(plan.Repository)))
	}
	if comment.ID <= 0 {
		return false, fmt.Errorf("owned documentation claim is unavailable")
	}
	if body == "" || body == comment.Body {
		return false, nil
	}
	if len(body) > 65536 {
		return false, fmt.Errorf("documentation stop exceeds the GitHub comment size limit")
	}

	path := fmt.Sprintf("/issues/comments/%d", comment.ID)
	requestErr := c.Request(ctx, "PATCH", path, map[string]string{"body": body}, nil)
	_, err = p.readState(ctx)
	if err != nil {
		return false, err
	}
	observed := p.claim
	if found {
		observed = p.stateComment
	}
	if observed.ID != comment.ID || observed.Body != body {
		if requestErr != nil {
			return false, fmt.Errorf("documentation stop was not confirmed: %w", requestErr)
		}
		return false, fmt.Errorf("documentation stop readback differs from the update")
	}
	return true, nil
}

func stopMessage(reason string) (string, error) {
	const retryWork = " To retry, run **QIP documentation agent** for this issue with `operation` set to `run` and `apply` enabled."
	switch reason {
	case "needs-human":
		return "Documentation review needs input. See the workflow summary for the question." + retryWork, nil
	case "rejected":
		return "The documentation candidate was rejected. See the work diagnostics for details." + retryWork, nil
	case "rejected-result-format":
		return "Documentation work was rejected because the agent returned an invalid result format. See the work diagnostics for details." + retryWork, nil
	case "verification-blocked":
		return "Documentation verification is blocked. See the workflow summary and work diagnostics for details." + retryWork, nil
	case "work-stopped":
		return "Documentation work stopped before publication. Check the workflow log for the cause." + retryWork, nil
	case "publish-stopped":
		return "Documentation publication stopped. Check the publication job log, resolve the error, and use **Re-run failed jobs** in this workflow run to reuse the prepared input and documentation result.", nil
	default:
		return "", fmt.Errorf("unknown documentation stop reason")
	}
}
