package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docgithub"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func documentationCommand(ctx context.Context, client *docgithub.Client, actor, allowed string) (docgithub.DocumentationCommand, error) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GITHUB_EVENT_NAME") != "issue_comment" ||
		os.Getenv("GITHUB_REPOSITORY") != client.Repository || os.Getenv("GITHUB_ACTOR") != actor {
		return docgithub.DocumentationCommand{}, fmt.Errorf("documentation command requires an Actions issue comment")
	}
	var payload fixer.GitHubPayload
	if err := read(os.Getenv("GITHUB_EVENT_PATH"), &payload); err != nil {
		return docgithub.DocumentationCommand{}, err
	}
	return docgithub.ReadDocumentationCommand(ctx, client, payload, actor, os.Getenv("GITHUB_TRIGGERING_ACTOR"), allowed)
}

func handleDocumentationCommand(ctx context.Context, repository, actor, reason string, reply bool) error {
	client, err := githubRepository(repository)
	if err != nil {
		return err
	}
	command, err := documentationCommand(ctx, client, actor, os.Getenv("AGENT_ALLOWED_ACTORS"))
	if err != nil {
		return err
	}
	if reply {
		if command.Issue == 0 {
			return nil
		}
		run, err := strconv.ParseInt(os.Getenv("GITHUB_RUN_ID"), 10, 64)
		if err != nil || run <= 0 {
			return fmt.Errorf("invalid documentation command run identity")
		}
		return docgithub.ReplyToDocumentationCommand(ctx, client, command, reason, run, os.Getenv("AGENT_USE_ORG_BOT") == "true")
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"authorized": command.Issue > 0, "issue": command.Issue})
}
