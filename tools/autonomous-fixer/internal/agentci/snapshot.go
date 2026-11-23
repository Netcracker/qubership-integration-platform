package agentci

import (
	"cmp"
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"slices"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type Snapshot struct {
	HeadSHA      string   `json:"head_sha"`
	TestMergeSHA string   `json:"test_merge_sha,omitempty"`
	Status       string   `json:"status"`
	Checks       []Check  `json:"checks"`
	Diagnostics  []string `json:"diagnostics,omitempty"`
}

type Check struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	SHA        string `json:"sha"`
	Workflow   string `json:"workflow,omitempty"`
	AppID      int64  `json:"app_id,omitempty"`
	Status     string `json:"status"`
	Conclusion string `json:"conclusion,omitempty"`
	Required   bool   `json:"required,omitempty"`
	URL        string `json:"url,omitempty"`
	ID         int64  `json:"id,omitempty"`
	action     actionRun
}

type checkIdentity struct {
	name, kind, workflow string
	appID, workflowID    int64
}

func (check Check) identity() checkIdentity {
	identity := checkIdentity{name: check.Name, kind: check.Kind, appID: check.AppID, workflowID: check.action.WorkflowID}
	if identity.workflowID == 0 {
		identity.workflow = check.Workflow
	}
	return identity
}

func (snapshot Snapshot) SelectedChecks() []Check {
	indexes := snapshot.selectedCheckIndexes()
	checks := make([]Check, 0, len(indexes))
	for _, index := range indexes {
		checks = append(checks, snapshot.Checks[index])
	}
	return checks
}

func (snapshot Snapshot) selectedCheckIndexes() []int {
	mergeChecks := make(map[checkIdentity]bool)
	if snapshot.TestMergeSHA != "" {
		for _, check := range snapshot.Checks {
			if check.SHA == snapshot.TestMergeSHA {
				mergeChecks[check.identity()] = true
			}
		}
	}
	var indexes []int
	for i, check := range snapshot.Checks {
		if (snapshot.TestMergeSHA != "" && check.SHA == snapshot.TestMergeSHA) ||
			(check.SHA == snapshot.HeadSHA && !mergeChecks[check.identity()]) {
			indexes = append(indexes, i)
		}
	}
	return indexes
}

const ObserverWorkflow = "QIP agent CI observation"

var commitSHA = regexp.MustCompile(`^[0-9a-f]{40}$`)

func Read(ctx context.Context, client *fixer.GitHubClient, headSHA, mergeSHA string, required []Requirement) (Snapshot, error) {
	snapshot := Snapshot{HeadSHA: headSHA, Checks: []Check{}}
	if !commitSHA.MatchString(headSHA) {
		return snapshot, fmt.Errorf("invalid pull request head SHA")
	}
	head, diagnostics := readCommit(ctx, client, headSHA)
	snapshot.Checks = append(snapshot.Checks, head...)
	snapshot.Diagnostics = diagnostics
	missingSHA := headSHA
	if mergeSHA != "" && mergeSHA != headSHA {
		if err := verifyTestMerge(ctx, client, headSHA, mergeSHA); err != nil {
			snapshot.Diagnostics = append(snapshot.Diagnostics, fmt.Sprintf("The test merge commit could not be verified (%v).", err))
		} else {
			snapshot.TestMergeSHA = mergeSHA
			merge, diagnostics := readCommit(ctx, client, mergeSHA)
			snapshot.Diagnostics = append(snapshot.Diagnostics, diagnostics...)
			snapshot.Checks = append(snapshot.Checks, merge...)
			if len(merge) > 0 {
				missingSHA = mergeSHA
			}
		}
	}
	selected := snapshot.selectedCheckIndexes()
	for _, requirement := range uniqueRequirements(required) {
		found := false
		for _, i := range selected {
			check := &snapshot.Checks[i]
			if check.Name == requirement.Name && (requirement.AppID == 0 || check.AppID == requirement.AppID) {
				check.Required = true
				found = true
			}
		}
		if !found && len(snapshot.Diagnostics) == 0 {
			snapshot.Checks = append(snapshot.Checks, Check{Name: requirement.Name, Kind: "required", SHA: missingSHA, AppID: requirement.AppID, Status: "missing", Required: true})
		}
	}
	slices.SortFunc(snapshot.Checks, func(a, b Check) int {
		return cmp.Or(cmp.Compare(a.Name, b.Name), cmp.Compare(a.Workflow, b.Workflow), cmp.Compare(a.SHA, b.SHA),
			cmp.Compare(a.Kind, b.Kind), cmp.Compare(a.AppID, b.AppID), cmp.Compare(a.URL, b.URL))
	})
	snapshot.Status = summarize(snapshot)
	return snapshot, nil
}

func verifyTestMerge(ctx context.Context, client *fixer.GitHubClient, headSHA, mergeSHA string) error {
	if !commitSHA.MatchString(mergeSHA) {
		return fmt.Errorf("invalid test merge SHA")
	}
	var commit struct {
		SHA     string `json:"sha"`
		Parents []struct {
			SHA string `json:"sha"`
		} `json:"parents"`
	}
	if err := client.Request(ctx, "GET", "/commits/"+mergeSHA, nil, &commit); err != nil {
		return err
	}
	if commit.SHA != mergeSHA || len(commit.Parents) != 2 || commit.Parents[1].SHA != headSHA {
		return fmt.Errorf("the test merge commit does not contain the current head")
	}
	return nil
}

type actionRun struct {
	Name       string `json:"name"`
	SHA        string `json:"head_sha"`
	Event      string `json:"event"`
	Suite      int64  `json:"check_suite_id"`
	ID         int64  `json:"id"`
	WorkflowID int64  `json:"workflow_id"`
	Attempt    int    `json:"run_attempt"`
	Status     string `json:"status"`
	Conclusion string `json:"conclusion"`
}

func observationWorkflow(name string) bool {
	switch name {
	case ObserverWorkflow, "QIP agent PR outcome", "QIP agent review submitted":
		return true
	default:
		return false
	}
}

func actionRuns(ctx context.Context, client *fixer.GitHubClient, sha string) (map[int64]actionRun, error) {
	items, err := client.List(ctx, "/actions/runs?head_sha="+sha, "workflow_runs")
	if err != nil {
		return nil, err
	}
	runs := make(map[int64]actionRun, len(items))
	for _, item := range items {
		var run actionRun
		if err := json.Unmarshal(item, &run); err != nil {
			return nil, fmt.Errorf("invalid Actions run")
		}
		if run.SHA == sha && run.Suite > 0 {
			runs[run.Suite] = run
		}
	}
	return runs, nil
}

func eligibleActionEvent(event string) bool {
	switch event {
	case "push", "pull_request", "pull_request_review", "pull_request_target", "deployment", "deployment_status":
		return true
	default:
		return false
	}
}

func readCommit(ctx context.Context, client *fixer.GitHubClient, sha string) ([]Check, []string) {
	var diagnostics []string
	checks, err := readCheckRuns(ctx, client, sha)
	if err != nil {
		diagnostics = append(diagnostics, fmt.Sprintf("Check runs for %s could not be read (%v).", sha, err))
	}
	statuses, err := readStatuses(ctx, client, sha)
	if err != nil {
		diagnostics = append(diagnostics, fmt.Sprintf("Commit statuses for %s could not be read (%v).", sha, err))
	}
	return append(checks, statuses...), diagnostics
}

func readCheckRuns(ctx context.Context, client *fixer.GitHubClient, sha string) ([]Check, error) {
	items, err := client.List(ctx, "/commits/"+sha+"/check-runs?filter=latest", "check_runs")
	if err != nil {
		return nil, err
	}
	var checks []Check
	var runs map[int64]actionRun
	var runsErr error
	unmatched := false
	for _, item := range items {
		var run struct {
			ID         int64  `json:"id"`
			Name       string `json:"name"`
			SHA        string `json:"head_sha"`
			Status     string `json:"status"`
			Conclusion string `json:"conclusion"`
			URL        string `json:"html_url"`
			Suite      struct {
				ID int64 `json:"id"`
			} `json:"check_suite"`
			App struct {
				ID   int64  `json:"id"`
				Slug string `json:"slug"`
			} `json:"app"`
		}
		if json.Unmarshal(item, &run) != nil || run.SHA != sha || run.Name == "" || run.Status == "" {
			return checks, fmt.Errorf("check run does not identify the requested commit")
		}
		var action actionRun
		if run.App.Slug == "github-actions" {
			if runs == nil && runsErr == nil {
				runs, runsErr = actionRuns(ctx, client, sha)
			}
			var found bool
			action, found = runs[run.Suite.ID]
			if !found {
				unmatched = true
				continue
			}
			if !eligibleActionEvent(action.Event) || observationWorkflow(action.Name) {
				continue
			}
		}
		checks = append(checks, Check{Name: run.Name, Kind: "check-run", SHA: sha, Workflow: action.Name,
			AppID: run.App.ID, Status: checkStatus(run.Status, run.Conclusion), Conclusion: run.Conclusion, URL: run.URL,
			ID: run.ID, action: action})
	}
	if runsErr == nil && unmatched {
		runsErr = fmt.Errorf("some GitHub Actions checks could not be matched to a workflow run")
	}
	return checks, runsErr
}

func readStatuses(ctx context.Context, client *fixer.GitHubClient, sha string) ([]Check, error) {
	items, err := client.List(ctx, "/commits/"+sha+"/statuses", "")
	if err != nil {
		return nil, err
	}
	var checks []Check
	seen := make(map[string]bool, len(items))
	for _, item := range items {
		var status struct {
			Context string `json:"context"`
			State   string `json:"state"`
			URL     string `json:"target_url"`
		}
		if json.Unmarshal(item, &status) != nil || status.Context == "" || status.State == "" {
			return checks, fmt.Errorf("invalid commit status")
		}
		if seen[status.Context] {
			continue
		}
		seen[status.Context] = true
		checks = append(checks, Check{Name: status.Context, Kind: "commit-status", SHA: sha,
			Status: checkStatus("completed", status.State), Conclusion: status.State, URL: status.URL})
	}
	return checks, nil
}

func checkStatus(status, conclusion string) string {
	if status != "completed" {
		return "pending"
	}
	switch conclusion {
	case "success":
		return "passed"
	case "neutral", "skipped", "cancelled", "pending":
		return conclusion
	case "failure", "error", "timed_out", "action_required", "stale", "startup_failure":
		return "failed"
	default:
		return "unknown"
	}
}

func summarize(snapshot Snapshot) string {
	checks := snapshot.SelectedChecks()
	for _, status := range []string{"failed", "cancelled", "missing", "pending", "unknown"} {
		if slices.ContainsFunc(checks, func(check Check) bool { return check.Status == status }) {
			return status
		}
	}
	if len(snapshot.Diagnostics) > 0 || len(checks) == 0 {
		return "unknown"
	}
	return "passed"
}
