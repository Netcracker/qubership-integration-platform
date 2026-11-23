package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

func (a *GitHubAdapter) hostedReportBody(effect Effect) (body, marker string, err error) {
	for _, decision := range a.state.Decisions {
		if decision.ID != effect.Payload.DecisionID || decision.HostedResult == nil {
			continue
		}
		report := decision.HostedResult
		marker = fmt.Sprintf("<!-- qip-agent-result:v1 %d %d %d -->", a.Task.IssueNumber, report.Run.RunID, report.Run.RunAttempt)
		lines := []string{
			"## Agent needs input", "", escapeComment(decision.Question), "",
			fmt.Sprintf("[Worker run %d, attempt %d](https://github.com/%s/actions/runs/%d/attempts/%d)", report.Run.RunID, report.Run.RunAttempt, a.Task.Repository, report.Run.RunID, report.Run.RunAttempt),
			"", fmt.Sprintf("Answer with `/agent answer %s %d %d <answer>`.", decision.ID, decision.Version, decision.ContractVersion), "", marker,
		}
		body = report.Signature.Append(strings.Join(lines, "\n"))
		if utf8.RuneCountInString(body) > 65536 {
			return "", "", fmt.Errorf("hosted result exceeds the GitHub comment limit")
		}
		return body, marker, nil
	}
	return "", "", fmt.Errorf("the hosted report has no recorded decision")
}

func (a *GitHubAdapter) findHostedReport(ctx context.Context, effect Effect, marker string) (*Comment, error) {
	items, err := a.Client.List(ctx, fmt.Sprintf("/issues/%d/comments", a.Task.IssueNumber), "")
	if err != nil {
		return nil, err
	}
	var found *Comment
	for _, item := range items {
		var comment Comment
		if err := json.Unmarshal(item, &comment); err != nil {
			return nil, err
		}
		if comment.User.ID != a.Store.botID || comment.User.Type != "Bot" || !strings.Contains("\n"+comment.Body+"\n", "\n"+marker+"\n") {
			continue
		}
		if found != nil || comment.ID <= 0 || (effect.Result != nil && effect.Result.CommentID != comment.ID) {
			return nil, fmt.Errorf("hosted result comment identity is ambiguous")
		}
		found = &comment
	}
	return found, nil
}

func (a *GitHubAdapter) postHostedReport(ctx context.Context, effect Effect) error {
	body, marker, err := a.hostedReportBody(effect)
	if err != nil {
		return err
	}
	comment, err := a.findHostedReport(ctx, effect, marker)
	if err != nil || comment != nil {
		return err
	}
	return a.Client.Request(ctx, "POST", fmt.Sprintf("/issues/%d/comments", a.Task.IssueNumber), map[string]string{"body": body}, nil)
}

func (a *GitHubAdapter) observeHostedReport(ctx context.Context, effect Effect) (Observation, error) {
	unknown := Observation{Status: "unknown"}
	body, marker, err := a.hostedReportBody(effect)
	if err != nil {
		return unknown, err
	}
	comment, err := a.findHostedReport(ctx, effect, marker)
	if err != nil || comment == nil {
		return unknown, err
	}
	footer := body[strings.LastIndexByte(body, '\n')+1:]
	signed := agentsignature.Strip(comment.Body) + "\n\n" + footer
	if comment.Body != signed {
		path := fmt.Sprintf("/issues/comments/%d", comment.ID)
		updateErr := a.Client.Request(ctx, "PATCH", path, map[string]string{"body": signed}, nil)
		receipt := effect
		receipt.Result = &EffectResult{CommentID: comment.ID}
		comment, err = a.findHostedReport(ctx, receipt, marker)
		if err != nil {
			return unknown, err
		}
		if comment == nil || comment.Body != signed {
			return unknown, updateErr
		}
	}
	return Observation{Status: "found", Result: &EffectResult{CommentID: comment.ID}}, nil
}

func (a *GitHubAdapter) repairHostedReport(ctx context.Context, run RunIdentity) error {
	for _, effect := range a.state.Effects {
		if effect.Kind != "report-hosted" || effect.Status != "confirmed" {
			continue
		}
		execution, err := a.execution(effect.ExecutionID)
		if err != nil {
			return err
		}
		if execution.Run != nil && *execution.Run == run {
			observed, err := a.observeHostedReport(ctx, effect)
			if err != nil {
				return err
			}
			if observed.Status != "found" {
				return fmt.Errorf("could not confirm the recorded hosted result comment")
			}
			return nil
		}
	}
	return nil
}
