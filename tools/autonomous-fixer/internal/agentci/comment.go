package agentci

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/url"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentpr"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func publish(ctx context.Context, client *fixer.GitHubClient, repo fixer.Repository, pr agentpr.PullRequest, report *Report) error {
	authors, err := client.EngineeringAuthors(ctx)
	if err != nil {
		return err
	}
	marker := fmt.Sprintf("<!-- qip-agent-ci:v1 %d %d -->", repo.ID, pr.Number)
	path := fmt.Sprintf("/issues/%d/comments", pr.Number)
	comment, err := findComment(ctx, client, path, marker, authors)
	if err != nil {
		return err
	}
	body := commentBody(report.CI, repo.FullName, marker)
	live, state, err := agentpr.Read(ctx, client, repo, pr.Number)
	if errors.Is(err, agentpr.ErrNotPublished) || errors.Is(err, agentpr.ErrPublicationPending) {
		report.Disposition = "stale"
		return nil
	}
	if err != nil {
		return err
	}
	execution, ok := agentpr.PublicationExecution(state)
	if live.State != "open" || live.Merged || live.Head.SHA != pr.Head.SHA || live.MergeSHA != pr.MergeSHA ||
		live.Base.Ref != pr.Base.Ref || state.HostedPublication.SHA != report.PublicationSHA || !ok || *execution.Run != report.WorkerRun {
		report.Disposition = "stale"
		return nil
	}
	if comment != nil && comment.Body == body {
		report.CommentID = comment.ID
		report.Disposition = "unchanged"
		return nil
	}
	var owner fixer.GitHubUser
	method, target := "POST", path
	if comment != nil {
		owner = comment.User
		method, target = "PATCH", fmt.Sprintf("/issues/comments/%d", comment.ID)
	}
	requestErr := client.WriteIssue(ctx, owner, method, target, map[string]string{"body": body}, nil)
	confirmed, err := findComment(ctx, client, path, marker, authors)
	if err != nil {
		return errors.Join(requestErr, err)
	}
	if confirmed == nil || confirmed.Body != body || (comment != nil && comment.ID != confirmed.ID) {
		return errors.Join(requestErr, fmt.Errorf("could not confirm the CI observation comment"))
	}
	report.CommentID = confirmed.ID
	report.Disposition = "published"
	return nil
}

func findComment(ctx context.Context, client *fixer.GitHubClient, path, marker string, authors fixer.EngineeringAuthors) (*fixer.Comment, error) {
	items, err := client.List(ctx, path, "")
	if err != nil {
		return nil, err
	}
	var found *fixer.Comment
	for _, item := range items {
		var comment fixer.Comment
		if json.Unmarshal(item, &comment) != nil {
			return nil, fmt.Errorf("invalid pull request comment")
		}
		if !authors.Contains(comment.User) || !strings.Contains("\n"+comment.Body+"\n", "\n"+marker+"\n") {
			continue
		}
		if found != nil || comment.ID <= 0 {
			return nil, fmt.Errorf("ambiguous CI observation comment")
		}
		found = &comment
	}
	return found, nil
}

func commentBody(snapshot Snapshot, repository, marker string) string {
	checks := snapshot.SelectedChecks()
	var body strings.Builder
	fmt.Fprintf(&body, "## CI observation\n\nPR head `%s`\n\n", snapshot.HeadSHA)
	if snapshot.TestMergeSHA != "" {
		fmt.Fprintf(&body, "GitHub test merge `%s`\n\n", snapshot.TestMergeSHA)
	}
	fmt.Fprintf(&body, "Observed CI status **%s**.\n\n", snapshot.Status)
	if len(checks) == 0 {
		body.WriteString("No check results are available for this head.\n\n")
	}
	if slices.ContainsFunc(checks, func(check Check) bool { return check.Status == "missing" }) {
		body.WriteString("GitHub has yet to report results for the required checks marked missing. Check whether their workflows ran for this PR.\n\n")
	}
	for _, diagnostic := range snapshot.Diagnostics {
		fmt.Fprintf(&body, "- %s\n", diagnostic)
	}
	if len(snapshot.Diagnostics) > 0 {
		body.WriteByte('\n')
	}
	body.WriteString("This observation refreshes when PR workflows complete and during the daily check.\n\n")
	if len(checks) > 0 {
		body.WriteString("| Check | Status | Commit | Required |\n| --- | --- | --- | --- |\n")
		slices.SortStableFunc(checks, func(a, b Check) int { return checkPriority(a) - checkPriority(b) })
		for i, check := range checks {
			label := tableText(check.Name)
			if check.Workflow != "" && check.Workflow != check.Name {
				label = tableText(check.Workflow) + " / " + label
			}
			if link := checkLink(check.URL, repository); link != "" {
				label = "[" + label + "](" + link + ")"
			}
			status := check.Status
			if check.Conclusion != "" && check.Conclusion != check.Status && check.Status != "passed" {
				status += " (" + tableText(check.Conclusion) + ")"
			}
			commit := "Head"
			if check.SHA == snapshot.TestMergeSHA {
				commit = "Test merge"
			}
			required := ""
			if check.Required {
				required = "Yes"
				if check.AppID > 0 && check.Status == "missing" {
					required = fmt.Sprintf("App %d", check.AppID)
				}
			}
			row := fmt.Sprintf("| %s | %s | %s | %s |\n", label, status, commit, required)
			if body.Len()+len(row) > 60000 {
				fmt.Fprintf(&body, "\n%d more checks are listed in the workflow's JSON artifact.\n", len(checks)-i)
				break
			}
			body.WriteString(row)
		}
		body.WriteByte('\n')
	}
	fmt.Fprintf(&body, "%s\n\n<sub>Observed by QIP agent CI in GitHub Actions.</sub>", marker)
	return body.String()
}

func checkPriority(check Check) int {
	if check.Required || (check.Status != "passed" && check.Status != "skipped" && check.Status != "neutral") {
		return 0
	}
	return 1
}

var tableEscapes = strings.NewReplacer(
	"|", "&#124;", "@", "&#64;", "#", "&#35;", "[", "&#91;", "]", "&#93;", "`", "&#96;",
	"*", "&#42;", "_", "&#95;", "~", "&#126;", "\\", "&#92;", ":", "&#58;",
)

func tableText(value string) string {
	runes := []rune(strings.Join(strings.Fields(value), " "))
	if len(runes) > 300 {
		value = string(runes[:300]) + "…"
	} else {
		value = string(runes)
	}
	return tableEscapes.Replace(html.EscapeString(value))
}

func checkLink(value, repository string) string {
	link, err := url.Parse(value)
	if err != nil || link.Scheme != "https" || link.Host != "github.com" || link.User != nil || link.RawQuery != "" || link.Fragment != "" ||
		!strings.HasPrefix(strings.ToLower(link.Path), "/"+strings.ToLower(repository)+"/") || !strings.Contains(link.Path, "/actions/runs/") ||
		strings.ContainsAny(link.EscapedPath(), "()[]|<>") {
		return ""
	}
	return link.String()
}
