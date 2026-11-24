package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
)

func handleDocumentationMerge(ctx context.Context, operation, repository, publisherLogin string, issue int, planID string, apply bool) error {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GITHUB_EVENT_NAME") != "pull_request_target" ||
		repository == "" || os.Getenv("GITHUB_REPOSITORY") != repository {
		return fmt.Errorf("documentation merge reconciliation requires an Actions pull request target event")
	}
	var event docgithub.DocumentationMergeEvent
	if err := read(os.Getenv("GITHUB_EVENT_PATH"), &event); err != nil {
		return err
	}
	branch := "refs/heads/" + event.Repository.DefaultBranch
	workflow := repository + "/.github/workflows/agent-docs-reconcile-merge.yaml@" + branch
	if os.Getenv("GITHUB_WORKFLOW_REF") != workflow || os.Getenv("GITHUB_REF") != branch {
		return fmt.Errorf("documentation merge reconciliation requires the default-branch workflow")
	}
	if operation == "route-merge" {
		client, err := githubRepository(repository)
		if err != nil {
			return err
		}
		route, err := docgithub.RouteDocumentationMerge(ctx, client, event, publisherLogin)
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(route)
	}
	if !apply || issue <= 0 || planID == "" {
		return fmt.Errorf("reconcile-merge requires the admitted issue, plan ID, and --apply")
	}
	client, err := github(repository)
	if err != nil {
		return err
	}
	result, err := docgithub.ReconcileDocumentationMerge(ctx, client, event, docgithub.DocumentationMergeRoute{Issue: issue, PlanID: planID})
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(result)
}
