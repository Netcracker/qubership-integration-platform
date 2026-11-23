package localwork

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAuthorHelperSocketRunsOneRequestAtATime(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	socket, stop, err := startAuthorHelper(context.Background(), func(ctx context.Context, number int, question string) (string, error) {
		if number == 1 {
			close(started)
			select {
			case <-release:
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		return fmt.Sprintf("%d %s", number, question), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var dialer net.Dialer
		return dialer.DialContext(ctx, "unix", socket)
	}}}
	t.Cleanup(client.CloseIdleConnections)
	ask := func(question string) (int, string, error) {
		response, err := client.Post("http://localhost/", "text/plain", strings.NewReader(question))
		if err != nil {
			return 0, "", err
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		return response.StatusCode, string(body), err
	}
	first := make(chan error, 1)
	go func() {
		status, reply, err := ask("inspect handler")
		if err != nil || status != http.StatusOK || reply != "1 inspect handler" {
			first <- fmt.Errorf("first response: status=%d body=%q err=%v", status, reply, err)
			return
		}
		first <- nil
	}()
	<-started
	if status, _, err := ask("inspect caller"); err != nil || status != http.StatusConflict {
		t.Fatalf("concurrent response: status=%d err=%v", status, err)
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
	if status, reply, err := ask("inspect caller"); err != nil || status != http.StatusOK || reply != "2 inspect caller" {
		t.Fatalf("second response: status=%d body=%q err=%v", status, reply, err)
	}
}

func TestAuthorHelperAvailableInBothTurnsWithoutStartingAModel(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	helper := cfg.Profile
	helper.Model = "helper-fixture"
	cfg.AuthorHelperProfile = &helper
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	for _, turn := range []string{"step-0001-author", "step-0002-author"} {
		if _, err := d.Author(context.Background(), workcycle.AgentAuthorRequest{Request: d.Request}); err != nil {
			t.Fatal(err)
		}
		step := filepath.Join(d.Config.OutputDirectory, turn)
		prompt, err := os.ReadFile(filepath.Join(step, "prompt.txt"))
		if err != nil || !strings.Contains(string(prompt), "--unix-socket") || strings.Contains(string(prompt), "Do not launch another model or delegate work.") {
			t.Fatalf("helper interface missing from %s: %v", turn, err)
		}
		var command experimentruntime.Command
		readAgentJSON(t, filepath.Join(step, "command.json"), &command)
		if !strings.Contains(strings.Join(command.Args, " "), "--disable multi_agent_v2") {
			t.Fatalf("native delegation was enabled in %s", turn)
		}
		if _, err := os.Stat(filepath.Join(step, "helper-0001")); !os.IsNotExist(err) {
			t.Fatalf("unsolicited helper invocation in %s", turn)
		}
	}
}

func TestAuthorHelperRunsOnAReadOnlyCopyWithItsOwnProfile(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	helper := experimentruntime.Profile{Runtime: "codex", Model: "helper-fixture", Effort: "low"}
	cfg.AuthorHelperProfile = &helper
	cfg.Environment["KUBECONFIG"] = "/test/cluster-config"
	if err := os.WriteFile(cfg.ModelCatalog, []byte(`{"models":[{"slug":"helper-fixture","context_window":200000,"supported_reasoning_levels":[{"effort":"low"}]}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/sh
set -eu
case "$1" in
	--version) echo 'fixture runtime'; exit ;;
	sandbox) test -z "${KUBECONFIG+x}"; exec git rev-parse HEAD ;;
esac
test -z "${KUBECONFIG+x}"
test "$(cat engine/code.txt)" = candidate
cat >/dev/null
printf '%s\n' '{"type":"thread.started","thread_id":"helper-session"}'
printf '{"type":"item.completed","item":{"type":"agent_message","text":"Candidate path: %s/engine/code.txt:1"}}\n' "$PWD"
printf '%s\n' '{"type":"turn.completed"}'
`
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg.Workspace, "engine/code.txt"), []byte("candidate"), 0600); err != nil {
		t.Fatal(err)
	}
	parent, _, err := d.agentStep("author")
	if err != nil {
		t.Fatal(err)
	}
	text, err := d.runAuthorHelper(context.Background(), d.Request, parent, 1, "What value is in engine/code.txt?")
	if err != nil || text != "Candidate path: "+filepath.Join(d.Config.Workspace, "engine/code.txt")+":1" {
		t.Fatalf("helper response %q: %v", text, err)
	}
	dir := filepath.Join(parent, "helper-0001")
	var recorded experimentruntime.Profile
	readAgentJSON(t, filepath.Join(dir, "profile.json"), &recorded)
	if recorded != helper {
		t.Fatalf("helper used %+v", recorded)
	}
	var command experimentruntime.Command
	readAgentJSON(t, filepath.Join(dir, "command.json"), &command)
	args := strings.Join(command.Args, " ")
	if command.Workspace == cfg.Workspace || !strings.Contains(args, "default_permissions=") || !strings.Contains(args, ".network.enabled=false") || strings.Contains(args, "danger-full-access") {
		t.Fatalf("helper ran without its source and permission scope: %+v", command.Args)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "response-raw.txt"))
	if err != nil || !strings.Contains(string(raw), command.Workspace+"/engine/code.txt:1") {
		t.Fatalf("helper raw response lost its source snapshot reference: %v", err)
	}
	if _, err := os.Stat(command.Workspace); !os.IsNotExist(err) {
		t.Fatalf("source copy remains after helper completion: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "home", ".codex", "auth.json")); !os.IsNotExist(err) {
		t.Fatalf("helper home retains credentials: %v", err)
	}
}
