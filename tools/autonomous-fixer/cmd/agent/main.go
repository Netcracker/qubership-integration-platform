package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

// Local CLI tests replace the endpoint at link time; workflows use the GitHub API.
var githubAPIURL = "https://api.github.com"

func readCheckpoint(path string) (*fixer.Checkpoint, error) {
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer file.Close()
	const maxCheckpointBytes = 1048576
	data, err := io.ReadAll(io.LimitReader(file, maxCheckpointBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxCheckpointBytes {
		return nil, fmt.Errorf("checkpoint metadata exceeds 1 MiB")
	}
	var checkpoint fixer.Checkpoint
	if err := fixer.DecodeJSON(data, &checkpoint); err != nil {
		return nil, err
	}
	return &checkpoint, nil
}
func writeOutput(path string, outputs map[string]string) error {
	if path == "" {
		return nil
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	for key, value := range outputs {
		if strings.ContainsAny(key+value, "\r\n=") {
			return fmt.Errorf("invalid workflow output")
		}
		if _, err := fmt.Fprintf(file, "%s=%s\n", key, value); err != nil {
			return err
		}
	}
	return nil
}
func run(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return fmt.Errorf("usage: agent demo|authorize|route|control|admit|work|seal|route-publish|publish|confirm-publish")
	}
	if args[0] == "demo" {
		report, err := fixer.RunDemo(ctx)
		if err != nil {
			return err
		}
		encoder := json.NewEncoder(os.Stdout)
		encoder.SetIndent("", "  ")
		return encoder.Encode(report)
	}
	actor, triggeringActor := os.Getenv("GITHUB_ACTOR"), os.Getenv("GITHUB_TRIGGERING_ACTOR")
	if args[0] == "authorize" {
		if !fixer.Allowed(os.Getenv("AGENT_ALLOWED_ACTORS"), actor, triggeringActor) {
			return fmt.Errorf("the workflow operator is outside AGENT_ALLOWED_ACTORS")
		}
		return nil
	}
	checkpointPath := os.Getenv("AGENT_CHECKPOINT_PATH")
	artifactDir := os.Getenv("AGENT_HOSTED_ARTIFACT_DIR")
	if args[0] == "seal" {
		if !filepath.IsAbs(checkpointPath) || !filepath.IsAbs(artifactDir) {
			return fmt.Errorf("seal requires absolute checkpoint and hosted artifact paths")
		}
		checkpoint, err := readCheckpoint(checkpointPath)
		if err != nil {
			return err
		}
		if checkpoint == nil {
			return fmt.Errorf("hosted checkpoint is missing")
		}
		result, err := fixer.SealHostedResult(*checkpoint, artifactDir)
		if err != nil {
			return err
		}
		if usageDir := os.Getenv("AGENT_HOSTED_USAGE_DIR"); usageDir != "" {
			if !filepath.IsAbs(usageDir) {
				return fmt.Errorf("hosted usage path must be absolute")
			}
			signature, err := agentsignature.ReadRun(usageDir)
			if err != nil {
				fmt.Fprintln(os.Stderr, "Some hosted usage could not be read.")
			}
			if signature.Validate() == nil && signature.Line() != "" {
				result.Signature = &signature
			}
		}
		data, err := json.Marshal(result)
		if err != nil {
			return err
		}
		return os.WriteFile(filepath.Join(artifactDir, "hosted-result.json"), append(data, '\n'), 0600)
	}
	mode := os.Getenv("AGENT_MODE")
	if os.Getenv("AGENT_ENABLED") != "true" || (mode != "" && mode != "fake" && mode != "hosted") {
		return fmt.Errorf("AGENT_ENABLED must be true and AGENT_MODE must be fake or hosted")
	}
	if os.Getenv("GITHUB_TOKEN") == "" {
		return fmt.Errorf("GITHUB_TOKEN is required")
	}
	if os.Getenv("AGENT_VARIABLES_READ_TOKEN") == "" {
		return fmt.Errorf("AGENT_VARIABLES_READ_TOKEN is required to read repository variables")
	}
	data, err := os.ReadFile(os.Getenv("GITHUB_EVENT_PATH"))
	if err != nil {
		return err
	}
	var payload fixer.GitHubPayload
	if err := json.Unmarshal(data, &payload); err != nil {
		return err
	}
	repository := os.Getenv("GITHUB_REPOSITORY")
	client, err := fixer.EngineeringClientFromEnv(repository, os.Getenv("GITHUB_TOKEN"), githubAPIURL)
	if err != nil {
		return err
	}
	variables, err := fixer.NewGitHubClient(repository, os.Getenv("AGENT_VARIABLES_READ_TOKEN"), nil, githubAPIURL)
	if err != nil {
		return err
	}
	cfg := fixer.ActionConfig{Client: client, Variables: variables, Repository: repository, EventName: os.Getenv("GITHUB_EVENT_NAME"), Actor: actor, TriggeringActor: triggeringActor, Ref: os.Getenv("GITHUB_REF"), Enabled: true, Mode: mode, ScopePaths: fixer.ParseScopes(os.Getenv("AGENT_SCOPE_PATHS")), Payload: payload, Inputs: make(map[string]string)}
	for _, key := range []string{"issue", "execution", "generation", "contract_version", "trusted_sha", "mode", "candidate_run", "candidate_attempt", "operation", "worker_run", "worker_attempt"} {
		cfg.Inputs[key] = os.Getenv("INPUT_" + strings.ToUpper(key))
		if cfg.Inputs[key] == "" {
			cfg.Inputs[key] = payload.Inputs[key]
		}
	}
	cfg.RunID, _ = strconv.ParseInt(os.Getenv("GITHUB_RUN_ID"), 10, 64)
	cfg.RunAttempt, _ = strconv.Atoi(os.Getenv("GITHUB_RUN_ATTEMPT"))
	if args[0] == "control" && cfg.IsCompletionEvent() && checkpointPath != "" {
		cfg.Checkpoint, err = readCheckpoint(checkpointPath)
		if err != nil {
			return err
		}
	}
	if artifactDir != "" && ((args[0] == "control" && cfg.IsCompletionEvent()) || (args[0] == "publish" && mode == "hosted")) {
		hosted, err := fixer.LoadHostedResult(artifactDir)
		if err == nil {
			cfg.HostedResult = &hosted
		} else if !errors.Is(err, os.ErrNotExist) || args[0] == "publish" {
			fmt.Fprintln(os.Stderr, "Read hosted evidence:", err)
		}
	}
	if args[0] == "confirm-publish" {
		data, err := fixer.ReadHostedFile(os.Getenv("AGENT_HOSTED_PUBLICATION_PATH"), 32*1024)
		if err != nil {
			return err
		}
		var publication fixer.HostedPublication
		if err := fixer.DecodeJSON(data, &publication); err != nil {
			return err
		}
		cfg.HostedPublication = &publication
	}
	result, err := fixer.RunAction(ctx, args[0], cfg)
	if err != nil {
		return err
	}
	if result.Checkpoint != nil {
		if checkpointPath == "" {
			return fmt.Errorf("AGENT_CHECKPOINT_PATH is required")
		}
		if err := os.MkdirAll(filepath.Dir(checkpointPath), 0700); err != nil {
			return err
		}
		body, err := json.MarshalIndent(result.Checkpoint, "", "  ")
		if err != nil {
			return err
		}
		if err := os.WriteFile(checkpointPath, append(body, '\n'), 0600); err != nil {
			return err
		}
	}
	if err := writeOutput(os.Getenv("GITHUB_OUTPUT"), result.Outputs); err != nil {
		return err
	}
	summary := map[string]string{"mode": args[0]}
	if result.Result != nil {
		summary["disposition"] = result.Result.Disposition
		summary["reason"] = result.Result.Reason
		if result.Result.State != nil {
			summary["phase"] = result.Result.State.Phase
		}
	}
	if dispatch := result.Dispatch; dispatch != nil {
		summary["dispatch_receipt"] = dispatch.Receipt
		if dispatch.DispatchHTTPStatus > 0 {
			summary["dispatch_http_status"] = strconv.Itoa(dispatch.DispatchHTTPStatus)
		}
		if dispatch.RunID > 0 {
			summary["dispatch_run_id"] = strconv.FormatInt(dispatch.RunID, 10)
		}
		if dispatch.Readback != "" {
			summary["dispatch_readback"] = dispatch.Readback
		}
		if dispatch.ReadbackHTTPStatus > 0 {
			summary["dispatch_readback_http_status"] = strconv.Itoa(dispatch.ReadbackHTTPStatus)
		}
	}
	if err := json.NewEncoder(os.Stdout).Encode(summary); err != nil {
		return err
	}
	if result.Result != nil && result.Result.Disposition == "recovery-required" {
		return fmt.Errorf("automatic execution stopped: %s", result.Result.Reason)
	}
	return nil
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
