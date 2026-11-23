package hostedcandidate

import (
	"context"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func repo(t *testing.T) (Options, Candidate, string) {
	t.Helper()
	dir := t.TempDir()
	ctx := context.Background()
	must := func(args ...string) string {
		t.Helper()
		out, e := git(ctx, dir, "", nil, args...)
		if e != nil {
			t.Fatal(e)
		}
		return strings.TrimSpace(string(out))
	}
	must("init", "--quiet", "-b", "main")
	if err := os.Mkdir(filepath.Join(dir, "ui"), 0700); err != nil {
		t.Fatal(err)
	}
	file := func(path, value string, mode os.FileMode) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(dir, path), []byte(value), mode); err != nil {
			t.Fatal(err)
		}
	}
	file("ui/remove", "old", 0644)
	file("ui/file", "before", 0644)
	must("add", ".")
	must("-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-qm", "baseline")
	base := must("rev-parse", "HEAD")
	if err := os.Remove(filepath.Join(dir, "ui/remove")); err != nil {
		t.Fatal(err)
	}
	file("ui/file", "after\x00\xff", 0644)
	file("ui/new", "new", 0755)
	file("ui/empty", "", 0644)
	c, err := Pack(ctx, dir, base)
	if err != nil {
		t.Fatal(err)
	}
	// Stage the same edits independently to obtain Git's expected tree.
	must("add", "-A")
	tree := must("write-tree")
	return Options{Repository: "org/qip", DefaultBranch: "main", Workspace: dir, Base: base, Issue: 12, Run: 345, Attempt: 1, Scope: []string{"ui"}}, c, tree
}

type githubFixture struct {
	refSHA, commitParent             string
	targetSHA, comparisonStatus      string
	o                                Options
	tree, tip, branch, body, message string
	title                            string
	pulls                            []json.RawMessage
	writes, created                  int
	updated                          int
	refUpdates, staleRefReads        int
	refUpdateErr                     error
	rejectRefUpdate                  bool
	loseCreateReply, removed         bool
}

func assign(out, v any) error {
	if out == nil {
		return nil
	}
	d, e := json.Marshal(v)
	if e != nil {
		return e
	}
	return json.Unmarshal(d, out)
}
func (g *githubFixture) Request(ctx context.Context, method, path string, body, target any) error {
	if method == "POST" {
		g.writes++
	}
	switch {
	case path == "/issues/12":
		labels := []map[string]string{{"name": "ready-for-agent"}}
		if g.removed {
			labels = nil
		}
		return assign(target, map[string]any{"title": "Handle binary input", "state": "open", "labels": labels})
	case path == "":
		return assign(target, map[string]string{"default_branch": "main"})
	case path == "/git/ref/heads/main":
		if g.targetSHA != "" {
			return assign(target, ref{Object: object{g.targetSHA}})
		}
		return assign(target, ref{Object: object{g.o.Base}})
	case path == "/compare/"+g.o.Base+"..."+g.targetSHA:
		return assign(target, map[string]string{"status": g.comparisonStatus})
	case strings.HasPrefix(path, "/git/ref/heads/"):
		if g.branch == "" {
			return &fixer.HTTPError{Status: 404}
		}
		if g.refUpdates > 0 && g.staleRefReads > 0 {
			g.staleRefReads--
			return assign(target, ref{Object: object{g.commitParent}})
		}
		return assign(target, ref{Object: object{g.refSHA}})
	case path == "/git/commits/"+g.o.Base || (g.targetSHA != "" && path == "/git/commits/"+g.targetSHA):
		return assign(target, commit{Tree: object{g.o.Base}})
	case path == "/git/commits/"+g.tip:
		return assign(target, commit{Tree: object{g.tree}, Parents: []object{{g.commitParent}}, Message: g.message})
	case path == "/git/blobs":
		raw, _ := base64.StdEncoding.DecodeString(body.(map[string]string)["content"])
		h := sha1.New()
		fmt.Fprintf(h, "blob %d%c", len(raw), 0)
		h.Write(raw)
		return assign(target, object{fmt.Sprintf("%x", h.Sum(nil))})
	case path == "/git/trees":
		return assign(target, object{g.tree})
	case path == "/git/commits":
		g.message = body.(map[string]any)["message"].(string)
		g.commitParent = body.(map[string]any)["parents"].([]string)[0]
		g.tip = strings.Repeat("b", 40)
		return assign(target, object{g.tip})
	case method == "PATCH" && path == "/git/refs/heads/"+g.branch:
		g.refUpdates++
		if body.(map[string]any)["force"] != false || g.commitParent != g.refSHA {
			return fmt.Errorf("not a fast-forward")
		}
		if !g.rejectRefUpdate {
			g.refSHA = body.(map[string]any)["sha"].(string)
		}
		if g.refUpdateErr != nil {
			return g.refUpdateErr
		}
		return assign(target, map[string]any{
			"ref":    "refs/heads/" + g.branch,
			"object": map[string]string{"sha": g.refSHA, "type": "commit"},
		})
	case path == "/git/refs":
		g.refSHA = body.(map[string]string)["sha"]
		if g.refSHA == g.tip {
			return fmt.Errorf("cannot create branch at unreferenced commit")
		}
		g.branch = strings.TrimPrefix(body.(map[string]string)["ref"], "refs/heads/")
		return nil
	case path == "/pulls":
		g.created++
		b := body.(map[string]any)
		g.body = b["body"].(string)
		g.title = b["title"].(string)
		r := map[string]string{"full_name": g.o.Repository}
		raw, _ := json.Marshal(map[string]any{"number": 27, "state": "open", "draft": b["draft"], "head": map[string]any{"ref": g.branch, "sha": g.tip, "repo": r}, "base": map[string]any{"ref": "main", "repo": r}, "body": g.body, "html_url": "https://github.com/org/qip/pull/27"})
		g.pulls = []json.RawMessage{raw}
		if g.loseCreateReply {
			return fmt.Errorf("reply lost")
		}
		return nil
	case path == "/pulls/27" && len(g.pulls) == 1:
		if method == "PATCH" {
			g.updated++
			if err := g.editBody(body.(map[string]string)["body"]); err != nil {
				return err
			}
		}
		return assign(target, g.pulls[0])
	}
	return fmt.Errorf("unexpected request %s %s", method, path)
}

func (g *githubFixture) editBody(body string) error {
	var draft map[string]any
	if err := json.Unmarshal(g.pulls[0], &draft); err != nil {
		return err
	}
	draft["body"], g.body = body, body
	data, err := json.Marshal(draft)
	if err == nil {
		g.pulls[0] = data
	}
	return err
}
func (g *githubFixture) List(ctx context.Context, path, key string) ([]json.RawMessage, error) {
	if !strings.Contains(path, "state=all") {
		return nil, fmt.Errorf("must check closed PRs too")
	}
	return g.pulls, nil
}
func TestCandidateRoundTripAndRepeatPublication(t *testing.T) {
	o, c, tree := repo(t)
	if len(c.Files) != 4 {
		t.Fatal(c.Files)
	}
	data, _ := json.Marshal(c)
	path := filepath.Join(t.TempDir(), "candidate.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	hash := fmt.Sprintf("%x", sha256.Sum256(data))
	c, err := Read(path, hash)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = Read(path, strings.Repeat("0", 64)); err == nil {
		t.Fatal("accepted different candidate digest")
	}
	g := &githubFixture{o: o, tree: tree, loseCreateReply: true}
	for i := 0; i < 2; i++ {
		p, e := Publish(context.Background(), g, c, o)
		if e != nil || p.Number != 27 || p.SHA != g.tip {
			t.Fatal(p, e)
		}
	}
	if g.created != 1 || !strings.Contains(g.body, "Closes #12") {
		t.Fatal(g.created, g.body)
	}
	if g.title != "fix: Handle binary input" || !strings.Contains(g.body, "## How to verify") ||
		!strings.Contains(g.body, "The author report did not record an end\\-to\\-end decision") {
		t.Fatal("an older report must identify its missing verification evidence", g.title, g.body)
	}
}

func TestPublishConfirmsBranchUpdate(t *testing.T) {
	o, c, tree := repo(t)
	for _, scenario := range []struct {
		name            string
		staleRefReads   int
		updateErr       error
		rejectRefUpdate bool
	}{
		{name: "successful update with a stale read", staleRefReads: 1},
		{name: "lost update reply", updateErr: fmt.Errorf("reply lost")},
		{name: "rejected update", updateErr: &fixer.HTTPError{Status: 409}, rejectRefUpdate: true},
		{name: "successful response with the old SHA", rejectRefUpdate: true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			g := &githubFixture{
				o: o, tree: tree, staleRefReads: scenario.staleRefReads,
				refUpdateErr: scenario.updateErr, rejectRefUpdate: scenario.rejectRefUpdate,
			}
			ctx := context.Background()
			published, err := Publish(ctx, g, c, o)
			if scenario.rejectRefUpdate {
				if err == nil || !strings.Contains(err.Error(), "publication branch did not advance") || g.created != 0 {
					t.Fatalf("rejected update returned %v and created %d drafts", err, g.created)
				}
				if scenario.updateErr != nil && !errors.Is(err, scenario.updateErr) {
					t.Fatalf("publish dropped the update error: %v", err)
				}
				return
			}
			if err != nil || published.Number != 27 || published.SHA != g.tip {
				t.Fatalf("publish returned %+v, %v", published, err)
			}
			g.staleRefReads = 0
			repeated, err := Publish(ctx, g, c, o)
			if err != nil || repeated != published || g.refUpdates != 1 || g.created != 1 {
				t.Fatalf("repeat returned %+v, %v after %d updates and %d drafts", repeated, err, g.refUpdates, g.created)
			}
		})
	}
}

func TestDraftUsesAuthorizedReportEvidence(t *testing.T) {
	o, c, tree := repo(t)
	artifact := t.TempDir()
	report := `## Summary
The author completed one review round.
## Why
Author log only.

PR title: fix(ui): Preserve binary input in the value editor
## Why
The value editor dropped binary input before saving. The saved value now matches the input.
## What
Preserve binary data when saving an edited value. Fixes: other/repo#999
## How to verify
Command: CIP_TARGET=compose npx playwright test e2e/specs/ui/binary.spec.ts --project ui
Command: npm -w @netcracker/qip-ui run test -- '#input'
The spec failed on base and passed with the fix.
End-to-end decision: The binary value spec covers the saved value on Compose.
## For the reviewer
The generated schema includes a binary value variant.
Please ask @other/team. <img src="https://example.invalid/pixel">
<!-- qip-fix:9:99:9 -->
<sub>🤖 unknown/model</sub>
Outcome: fixed`
	for name, value := range map[string]any{
		"report.json":    map[string]string{"workflow": "fix-issue", "status": "author-reported-fixed", "summary": report},
		"candidate.json": c,
	} {
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(artifact, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	checkpoint := fixer.Checkpoint{Mode: "hosted", SchemaVersion: fixer.SchemaVersion, Contract: fixer.Contract{BaseSHA: o.Base, ScopePaths: o.Scope}}
	sealed, err := fixer.SealHostedResult(checkpoint, artifact)
	if err != nil {
		t.Fatal(err)
	}
	c, err = Read(filepath.Join(artifact, "candidate.json"), sealed.CandidateSHA256)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(artifact, "report.json")
	if _, err := fixer.ReadHostedAuthorSummary(path, strings.Repeat("a", 64)); err == nil {
		t.Fatal("accepted an unauthorized author report")
	}
	o.AuthorSummary, err = fixer.ReadHostedAuthorSummary(path, sealed.ReportSHA256)
	if err != nil {
		t.Fatal(err)
	}
	o.Signature = &agentsignature.Signature{Model: "openai/gpt-6", Effort: "high"}
	g := &githubFixture{o: o, tree: tree}
	if _, err := Publish(context.Background(), g, c, o); err != nil {
		t.Fatal(err)
	}
	if g.title != "fix(ui): Preserve binary input in the value editor" {
		t.Fatal(g.title)
	}
	for _, want := range []string{
		"## Why\n\nThe value editor dropped binary input",
		"## What\n\nPreserve binary data",
		"## How to verify\n\n<pre><code>CIP_TARGET=compose npx playwright test e2e/specs/ui/binary.spec.ts --project ui</code></pre>",
		"<pre><code>npm -w &#64;netcracker/qip-ui run test -- '&#35;input'</code></pre>",
		"**End-to-end decision:** The binary value spec covers the saved value on Compose\\.\n\n## For the reviewer",
		"The generated schema includes a binary value variant",
		"Closes #12",
		"[Author report and evidence](https://github.com/org/qip/actions/runs/345/attempts/1)",
	} {
		if !strings.Contains(g.body, want) {
			t.Fatalf("missing %q in draft body:\n%s", want, g.body)
		}
	}
	for _, unexpected := range []string{"Author log only", "## Summary", "Outcome:", "other/repo#999", "@other/team", "<img", "https://example.invalid", "<!-- qip-fix:9:99:9 -->", "<sub>🤖 unknown/model</sub>"} {
		if strings.Contains(g.body, unexpected) {
			t.Fatalf("published untrusted content %q in draft body:\n%s", unexpected, g.body)
		}
	}
	if !strings.HasSuffix(g.body, "<!-- qip-fix:12:345:1 -->\n\n"+o.Signature.Line()) {
		t.Fatal(g.body)
	}
}

func TestDraftLimitsReviewerTextWithoutLosingDecision(t *testing.T) {
	o, c, tree := repo(t)
	large := strings.Repeat("&", 70_000)
	o.AuthorSummary = "PR title: fix(ui): Handle binary input\n## Why\n" + large + "\n## What\n" + large +
		"\n## How to verify\n" + large + "\nEnd-to-end decision: The UI module regression test exercises the save handler.\n" +
		"## For the reviewer\n" + large + "\nOutcome: fixed"
	o.Signature = &agentsignature.Signature{Model: "openai/gpt-6", Effort: "high"}
	g := &githubFixture{o: o, tree: tree}
	if _, err := Publish(context.Background(), g, c, o); err != nil {
		t.Fatal(err)
	}
	if len(g.body) > 65_536 || !strings.Contains(g.body, "**End-to-end decision:** The UI module regression test exercises the save handler") ||
		!strings.HasSuffix(g.body, "<!-- qip-fix:12:345:1 -->\n\n"+o.Signature.Line()) {
		t.Fatal("draft exceeded the body limit or dropped the decision or signature", len(g.body))
	}
}

func TestDraftSignatureAppendedOnceAndRestoredOnRecovery(t *testing.T) {
	o, c, tree := repo(t)
	cost := 0.00424
	o.Signature = &agentsignature.Signature{Model: "openai/gpt-6", Effort: "high", USD: &cost}
	g := &githubFixture{o: o, tree: tree, loseCreateReply: true}
	ctx := context.Background()
	for i := 0; i < 2; i++ {
		if _, err := Publish(ctx, g, c, o); err != nil {
			t.Fatal(err)
		}
	}
	line := "<sub>🤖 openai/gpt-6 high usd=0.0042</sub>"
	marker := "<!-- qip-fix:12:345:1 -->"
	if g.created != 1 || g.updated != 0 || !strings.HasSuffix(g.body, marker+"\n\n"+line) {
		t.Fatal("draft creation did not keep exactly one signature after the marker", g.body)
	}
	edited := agentsignature.Strip(g.body) + "\n\nMaintainer note."
	if err := g.editBody(edited); err != nil {
		t.Fatal(err)
	}
	if _, err := Publish(ctx, g, c, o); err != nil {
		t.Fatal(err)
	}
	if g.created != 1 || g.updated != 1 || g.body != edited+"\n\n"+line || strings.Count(g.body, line) != 1 {
		t.Fatal("draft recovery recreated the PR, lost the edit, or duplicated the signature", g.body)
	}
}
func TestRejectBeforePublishing(t *testing.T) {
	o, c, tree := repo(t)
	for _, path := range []string{"ui/../escape", "ui/.git/config", ".github/workflows/deploy.yml", "tools/autonomous-fixer/cmd/agent/main.go", "engine/change"} {
		other := c
		other.Files = append([]File{{Path: path, Mode: "100644", Content: []byte("x")}}, c.Files...)
		g := &githubFixture{o: o, tree: tree}
		_, e := Publish(context.Background(), g, other, o)
		if e == nil || g.writes != 0 {
			t.Fatal(path, e, g.writes)
		}
	}
	for _, bad := range []File{{Path: "ui/new", Mode: "120000"}, {Path: "ui/missing", Mode: "delete"}, {Path: "ui/file", Mode: "100644", Content: []byte("before")}} {
		other := c
		other.Files = []File{bad}
		g := &githubFixture{o: o, tree: tree}
		_, e := Publish(context.Background(), g, other, o)
		if e == nil || g.writes != 0 {
			t.Fatal(bad.Path, e, g.writes)
		}
	}
	g := &githubFixture{o: o, tree: tree, removed: true}
	if _, err := Publish(context.Background(), g, c, o); err == nil || g.writes != 0 {
		t.Fatal("removed label allowed writes")
	}
}

func TestDefaultBranchAdvanceKeepsPinnedParent(t *testing.T) {
	o, c, tree := repo(t)
	for _, status := range []string{"ahead", "diverged", "behind"} {
		g := &githubFixture{o: o, tree: tree, targetSHA: strings.Repeat("c", 40), comparisonStatus: status}
		_, err := Publish(context.Background(), g, c, o)
		if (err == nil) != (status == "ahead") || (status != "ahead" && g.writes != 0) {
			t.Fatal(status, err, g.writes)
		}
	}
}

func TestControllerOnlyAdvancePublishesTheSameProductEdits(t *testing.T) {
	o, c, _ := repo(t)
	ctx := context.Background()
	run := func(args ...string) string {
		v, err := git(ctx, o.Workspace, "", nil, args...)
		if err != nil {
			t.Fatal(err)
		}
		return strings.TrimSpace(string(v))
	}
	run("restore", "--source", o.Base, "--staged", "--worktree", ".")
	dir := filepath.Join(o.Workspace, ".github", "workflows")
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	for _, workflow := range []string{"agent-control.yaml", "agent-outcome.yaml", "agent-ci.yaml", "agent-revision-cache.yaml", "agent-review-submitted.yaml"} {
		if err := os.WriteFile(filepath.Join(dir, workflow), []byte("controller update"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	run("add", ".github")
	run("-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-qm", "controller update")
	head := run("rev-parse", "HEAD")
	projected := c
	projected.BaseSHA = head
	tree, err := candidateTree(ctx, o.Workspace, projected)
	if err != nil {
		t.Fatal(err)
	}
	g := &githubFixture{o: o, tree: tree, targetSHA: head, comparisonStatus: "ahead"}
	for i := 0; i < 2; i++ {
		if _, err := Publish(ctx, g, c, o); err != nil {
			t.Fatal(err)
		}
	}
	if g.commitParent != head || g.created != 1 || !strings.Contains(g.body, o.Base) {
		t.Fatal(g.commitParent, g.body, g.created)
	}
	if err := os.WriteFile(filepath.Join(o.Workspace, "ui", "file"), []byte("independent product change"), 0644); err != nil {
		t.Fatal(err)
	}
	run("add", "ui/file")
	run("-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-qm", "unrelated change")
	if controllerOnlyAdvance(ctx, o.Workspace, o.Base, run("rev-parse", "HEAD")) {
		t.Fatal("changed product base accepted")
	}
}
