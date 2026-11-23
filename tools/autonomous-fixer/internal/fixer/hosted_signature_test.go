package fixer

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

func TestNativeCLISignsSealedHostedFromObservedAuthorAndReviewerUsage(t *testing.T) {
	cli := newNativeCLI(t)
	_, checkpoint := actionStartHosted(t, cli.api)
	_, artifact := sealFixture(t, *checkpoint, "blocked", "No product response.\nOutcome: blocked")
	usage := t.TempDir()
	write := func(path string, data []byte) {
		t.Helper()
		if err := os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	checkpointPath := filepath.Join(t.TempDir(), "checkpoint.json")
	encoded, err := json.Marshal(checkpoint)
	if err != nil {
		t.Fatal(err)
	}
	write(checkpointPath, encoded)
	write(filepath.Join(usage, "config.json"), []byte(`{"hosted":true,"profile":{"Runtime":"opencode","Model":"openrouter/openai/gpt-6","Effort":"high"}}`))
	write(filepath.Join(usage, "provider-usage.json"), fmt.Appendf(nil, `{"attempts":2,"generations":[
{"generation_sha256":%q,"requested_model":"openai/gpt-6","served_model":"openai/gpt-6-runtime","input_tokens":12200,"cached_input_tokens":200,"output_tokens":2900,"cost_usd":0.041},
{"generation_sha256":%q,"requested_model":"openai/terra","served_model":"openai/terra-runtime","input_tokens":2000,"cached_input_tokens":1000,"output_tokens":100,"cost_usd":0.001}]}`, strings.Repeat("a", 64), strings.Repeat("b", 64)))
	cmd := exec.Command(cli.binary, "seal")
	cmd.Env = []string{"AGENT_CHECKPOINT_PATH=" + checkpointPath, "AGENT_HOSTED_ARTIFACT_DIR=" + artifact, "AGENT_HOSTED_USAGE_DIR=" + usage}
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("seal: %v\n%s", err, out)
	}
	result, err := LoadHostedResult(artifact)
	const expected = "<sub>🤖 openai/gpt-6-runtime in=13k cached=1.2k out=3k usd=0.042</sub>"
	if err != nil || result.Signature == nil || result.Signature.Line() != expected {
		t.Fatalf("expected %s from the sealed hosted usage, got %+v (%v)", expected, result.Signature, err)
	}
}

func signedHostedFixture(t *testing.T, checkpoint Checkpoint, status, summary string, signature agentsignature.Signature) HostedResult {
	t.Helper()
	result, dir := sealFixture(t, checkpoint, status, summary)
	result.Signature = &signature
	data, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(dir, "hosted-result.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	result, err = LoadHostedResult(dir)
	if err != nil {
		t.Fatal(err)
	}
	return result
}

func TestHostedIssueSignaturesPersistAcrossDecisionsAndCallbackRetries(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	input := int64(1100)
	firstSignature := agentsignature.Signature{Model: "openai/gpt-6", Effort: "high", Input: &input}
	first := signedHostedFixture(t, *checkpoint, "needs-human", "Should the input be rejected?\nOutcome: needs-human", firstSignature)
	cfg = actionHostedComplete(api, cfg, first)
	api.Comments = append(api.Comments, Comment{ID: 99, User: GitHubUser{ID: 22, Login: "other-user", Type: "User"}, Body: "<!-- qip-agent-result:v1 1 500 1 -->"})
	api.HostedReportReadMisses = 1
	if result := actionCall(t, "control", cfg); result.Result.Disposition != "recovery-required" {
		t.Fatal("the controller claimed confirmation before GitHub returned the result comment", result)
	}
	actionCall(t, "control", cfg)
	actionCall(t, "control", cfg)
	idx := -1
	for i, comment := range api.Comments {
		if comment.User.ID == api.Bot.ID && strings.Contains(comment.Body, "qip-agent-result:v1") {
			if idx >= 0 || !strings.HasSuffix(comment.Body, "<!-- qip-agent-result:v1 1 500 1 -->\n\n"+firstSignature.Line()) || strings.Count(comment.Body, firstSignature.Line()) != 1 {
				t.Fatal("wrong signature or duplicate issue result", comment)
			}
			idx = i
		}
	}
	if idx < 0 {
		t.Fatal("signed issue result is missing")
	}
	edited := agentsignature.Strip(api.Comments[idx].Body) + "\n\nMaintainer note."
	api.Comments[idx].Body = edited
	actionCall(t, "control", cfg)
	if api.Comments[idx].Body != edited+"\n\n"+firstSignature.Line() {
		t.Fatal("retry did not restore the signature on the existing issue result")
	}
	saved := api.Comments[idx]
	state := actionReadState(t, api)
	q := state.Decisions[0]
	cfg = actionComment(api, cfg, 11, "bob", fmt.Sprintf("/agent answer %s %d %d Reject it.", q.ID, q.Version, q.ContractVersion))
	actionCall(t, "control", cfg)
	cfg, checkpoint = actionHostedWorker(t, api, cfg)
	secondSignature := agentsignature.Signature{Model: "openai/gpt-6", Input: &input}
	second := signedHostedFixture(t, *checkpoint, "blocked", "The preview no longer serves requests.\nOutcome: blocked", secondSignature)
	cfg = actionHostedComplete(api, cfg, second)
	actionCall(t, "control", cfg)
	if api.Comments[idx] != saved {
		t.Fatal("a later result rewrote the previous run's signature")
	}
	count := 0
	for _, comment := range api.Comments {
		if comment.User.ID != api.Bot.ID {
			continue
		}
		if strings.Contains(comment.Body, stateMarker) && strings.Contains(comment.Body, "<sub>🤖 ") {
			t.Fatal("the editable state duplicated the result signature")
		}
		if strings.Contains(comment.Body, "qip-agent-result:v1") {
			count++
		}
	}
	if count != 2 {
		t.Fatalf("expected one issue result per stopped run, got %d", count)
	}
}

func TestHostedFixedSignatureBindsArtifactAuthorizationAndDraftReceipt(t *testing.T) {
	api := testGitHubServer(t)
	cfg, checkpoint := actionStartHosted(t, api)
	signature := agentsignature.Signature{Model: "openai/gpt-6", Effort: "high"}
	result := signedHostedFixture(t, *checkpoint, "author-reported-fixed", "Verified the change.\nOutcome: fixed", signature)
	cfg = actionHostedComplete(api, cfg, result)
	actionCall(t, "control", cfg)
	for _, comment := range api.Comments {
		if strings.Contains(comment.Body, "<sub>🤖 ") {
			t.Fatal("a fixed run must sign only its draft PR")
		}
	}
	cfg.EventName, cfg.Actor, cfg.TriggeringActor = "workflow_dispatch", "bob", "bob"
	cfg.Inputs = map[string]string{"issue": "1", "candidate_run": "500", "candidate_attempt": "1"}
	wrong := result
	wrong.Signature = &agentsignature.Signature{Model: "other/model"}
	cfg.HostedResult = &wrong
	if _, err := RunAction(context.Background(), "publish", cfg); err == nil {
		t.Fatal("publication authorized an edited signature")
	}
	cfg.HostedResult = &result
	var authorized agentsignature.Signature
	issued := actionCall(t, "publish", cfg)
	if err := json.Unmarshal([]byte(issued.Outputs["result_signature"]), &authorized); err != nil || authorized.Line() != signature.Line() {
		t.Fatal("publication lost the authorized signature", err, issued)
	}
	if issued.Outputs["report_sha256"] != result.ReportSHA256 {
		t.Fatal("publication lost the authorized author report hash", issued)
	}
	sha, branch := strings.Repeat("b", 40), hostedBranch(1, checkpoint.Run)
	cfg.HostedPublication = &HostedPublication{Number: 10, SHA: sha, Branch: branch}
	body := "Closes #1\n\n<!-- qip-fix:1:500:1 -->"
	api.DraftPullRequest = map[string]any{"number": 10, "state": "open", "draft": true, "body": body,
		"head": map[string]any{"ref": branch, "sha": sha, "repo": map[string]any{"full_name": api.Repository}},
		"base": map[string]any{"ref": "main", "sha": checkpoint.Contract.BaseSHA, "repo": map[string]any{"full_name": api.Repository}}}
	if _, err := RunAction(context.Background(), "confirm-publish", cfg); err == nil {
		t.Fatal("confirmed a draft without the worker signature")
	}
	api.DraftPullRequest["body"] = signature.Append(body)
	if result := actionCall(t, "confirm-publish", cfg); result.Result.State.Phase != "waiting-ci" {
		t.Fatal("could not confirm the signed draft", result)
	}
}
