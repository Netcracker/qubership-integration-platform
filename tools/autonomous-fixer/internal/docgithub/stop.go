package docgithub

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

const retryPublication = " Check the publication job log, resolve the error, and use **Re-run failed jobs** in this workflow run to reuse the prepared input and documentation result."

type ScheduleStop struct {
	Actor              string
	Basis              string
	Current            ScheduledAttempt
	Reason             string
	PublicationFailure PublicationFailure
	PublicationAttempt int
	Signature          agentsignature.Signature
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
	message, err := stopMessage(stop.Reason, stop.PublicationFailure)
	if err != nil {
		return false, err
	}
	attempt := stop.Current
	if stop.PublicationAttempt != 0 {
		if stop.Reason != "publish-stopped" || stop.PublicationAttempt < claimAttempt.Attempt || stop.PublicationAttempt > stop.Current.Attempt {
			return false, fmt.Errorf("documentation stop does not match the publication attempt")
		}
		attempt.Attempt = stop.PublicationAttempt
	}
	if err = Authorize(ctx, c, stop.Actor); err != nil {
		return false, err
	}

	p := &publisher{client: c, plan: plan, attempt: attempt}
	found, err := p.readState(ctx)
	if err != nil {
		return false, err
	}
	comment := p.claim
	var body string
	var supersedeErr error
	if found {
		if stop.Reason != "publish-stopped" || (p.state.Status != "publishing" && p.state.Status != "completing") || attempt.Attempt < p.state.WorkflowAttempt {
			return false, nil
		}
		changed, err := p.supersedeStale(ctx, message)
		if changed {
			return err == nil, err
		}
		if err != nil {
			supersedeErr = fmt.Errorf("check whether the documentation publication can be superseded: %w", err)
		}
		comment = p.stateComment
		stateStart := strings.LastIndex(comment.Body, "\n\n<!-- qip-docs-state:")
		if stateStart < 0 {
			return false, fmt.Errorf("documentation state marker has no paragraph boundary")
		}
		recovery := retryPublication
		if supersedeErr == nil && p.state.Decision == "needed" {
			if notice := p.stalePublicationNotice(ctx); notice != "" {
				recovery = " " + notice
			}
		}
		message += recovery
		if p.state.Decision == "no_change" && p.state.Status == "completing" {
			message += " If an issue or project update is unresolved, verify the issue state and project status before retrying. You can also run **QIP documentation agent** for this issue with " +
				"`operation` set to `reconcile` and `apply` enabled."
		}
		body = p.publicationBody(p.state, attempt) + "\n\n" + message + comment.Body[stateStart:]
	} else if comment.ID > 0 {
		if stop.Reason == "publish-stopped" {
			head, err := c.head(ctx, plan.DefaultBranch)
			if err == nil && head != plan.BaseSHA {
				message += " " + staleAssessment
			} else {
				message += retryPublication
			}
		}
		body = stop.Signature.Append(fmt.Sprintf("%s\n\n%s\n\n[View the workflow run](%s)",
			scheduleReceipt(stop.Basis, claimAttempt), message, attempt.url(plan.Repository)))
	}
	if comment.ID <= 0 {
		return false, fmt.Errorf("owned documentation claim is unavailable")
	}
	if body == "" || body == comment.Body {
		return false, supersedeErr
	}
	if len(body) > 65536 {
		return false, fmt.Errorf("documentation stop exceeds the GitHub comment size limit")
	}

	path := fmt.Sprintf("/issues/comments/%d", comment.ID)
	requestErr := c.Request(ctx, "PATCH", path, map[string]string{"body": body}, nil)
	_, err = p.readState(ctx)
	if err != nil {
		return false, errors.Join(err, supersedeErr)
	}
	observed := p.claim
	if found {
		observed = p.stateComment
	}
	if observed.ID != comment.ID || observed.Body != body {
		if requestErr != nil {
			return false, errors.Join(fmt.Errorf("documentation stop was not confirmed: %w", requestErr), supersedeErr)
		}
		return false, errors.Join(fmt.Errorf("documentation stop readback differs from the update"), supersedeErr)
	}
	return true, supersedeErr
}

func stopMessage(reason string, failure PublicationFailure) (string, error) {
	const retryWork = " To retry, run **QIP documentation agent** for this issue with `operation` set to `run` and `apply` enabled."
	if reason != "publish-stopped" && failure != "" {
		return "", fmt.Errorf("publication failure requires a publication stop")
	}
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
		message := "Documentation publication stopped"
		switch failure {
		case "":
		case PublicationFailedCandidate:
			message += " while validating the documentation candidate"
		case PublicationFailedAccess:
			message += " while checking publication permissions"
		case PublicationFailedReadState:
			message += " while reading the publication state"
		case PublicationFailedSaveState:
			message += " while saving the publication state"
		case PublicationFailedDraftPR:
			message += " while publishing the draft documentation pull request"
		case PublicationFailedIssueClose:
			message += " while closing the issue"
		case PublicationFailedProjectDone:
			message += " while moving the project item to **Done**"
		default:
			return "", fmt.Errorf("unknown documentation publication failure")
		}
		return message + ".", nil
	default:
		return "", fmt.Errorf("unknown documentation stop reason")
	}
}
