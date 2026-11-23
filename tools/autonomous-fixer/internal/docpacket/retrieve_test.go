package docpacket

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestAutomaticRetrievalAtDocsRevision(t *testing.T) {
	dir := t.TempDir()
	git := func(args ...string) string {
		t.Helper()
		b, e := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput()
		if e != nil {
			t.Fatalf("git %v: %v: %s", args, e, b)
		}
		return strings.TrimSpace(string(b))
	}
	write := func(p, s string) {
		t.Helper()
		p = filepath.Join(dir, p)
		if e := os.MkdirAll(filepath.Dir(p), 0755); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(p, []byte(s), 0644); e != nil {
			t.Fatal(e)
		}
	}
	git("init", "-b", "main")
	git("config", "user.name", "Test")
	git("config", "user.email", "test@example.com")
	write("ui/src/WidgetPanel.tsx", "export const WidgetPanel = 'old'\n")
	write("ui/src/Consumer.tsx", "WidgetPanel consumer\n")
	write("help/docs/widget.md", "WidgetPanel historical\n")
	write("help/docs/linked.md", "linked detail\n")
	git("add", ".")
	git("commit", "-m", "initial")
	write("ui/src/WidgetPanel.tsx", "export const WidgetPanel = 'new'\n")
	write("help/docs/widget.md", "WidgetPanel intermediate\n")
	git("add", ".")
	git("commit", "-m", "change")
	merge := git("rev-parse", "HEAD")
	write("help/docs/widget.md", "WidgetPanel current [details](linked.md)\n")
	write("runtime-catalog/src/main/WidgetConsumer.java", "WidgetPanel consumer\n")
	git("add", ".")
	git("commit", "-m", "current")
	ref := git("rev-parse", "HEAD")
	write("help/docs/widget.md", "dirty documentation")
	c := Config{RepositoryPath: dir, Repository: "owner/repo", DocsRef: ref, AutoRetrieve: true, Issue: Issue{Number: 1, Title: "WidgetPanel linked behavior", PRs: []PR{{Number: 2, MergeSHA: merge, Target: "main"}}}, ResolvePRHead: singleCommitPRHead}
	p, r, e := Build(context.Background(), c)
	if e != nil {
		t.Fatal(e)
	}
	if p.Complete {
		t.Fatal("automatic retrieval claimed complete coverage")
	}
	joined := func(v any) string { b, _ := json.Marshal(v); return string(b) }
	docs := joined(p.Documentation)
	sources := joined(p.Sources)
	evidence := joined(r.SearchEvidence)
	for _, want := range []string{"WidgetPanel current", "linked detail"} {
		if !strings.Contains(docs, want) {
			t.Fatalf("missing %q: %s", want, docs)
		}
	}
	if strings.Contains(docs, "intermediate") || strings.Contains(docs, "dirty documentation") {
		t.Fatal("used historical or dirty documentation")
	}
	for _, want := range []string{"ui/src/Consumer.tsx", "runtime-catalog/src/main/WidgetConsumer.java"} {
		if !strings.Contains(sources, want) {
			t.Fatalf("missing consumer %s: %s", want, sources)
		}
	}
	if !strings.Contains(evidence, "search at") || !strings.Contains(evidence, "Selected local link") {
		t.Fatalf("missing search evidence: %s", evidence)
	}
	p2, r2, e := Build(context.Background(), c)
	if e != nil || joined(p) != joined(p2) || joined(r) != joined(r2) {
		t.Fatal("retrieval is not deterministic")
	}
	// A documentation-only change with a resolved link can complete the plan.
	write("help/docs/widget.md", "WidgetPanel final [details](linked.md)\n")
	git("add", "help/docs/widget.md")
	git("commit", "-m", "documentation only")
	docCommit := git("rev-parse", "HEAD")
	docOnly := Config{RepositoryPath: dir, Repository: "owner/repo", DocsRef: docCommit, AutoRetrieve: true, Issue: Issue{Number: 3, Title: "WidgetPanel linked", PRs: []PR{{Number: 4, MergeSHA: docCommit, Target: "main"}}}, ResolvePRHead: singleCommitPRHead}
	complete, _, err := Build(context.Background(), docOnly)
	if err != nil || !complete.Complete {
		t.Fatalf("complete documentation plan: %+v %v", complete.Limitations, err)
	}
	c.MaxDocBytes = 4
	p, _, e = Build(context.Background(), c)
	if e != nil || p.Complete || !strings.Contains(joined(p.Limitations), "budget truncated") {
		t.Fatal("documentation truncation not reported")
	}
}

func TestRetrievalMappingsAndBuildOnly(t *testing.T) {
	paths := []string{
		"ui/src/components/UserMenu.tsx",
		"ui/src/pages/ChainGraphView.tsx",
		"ui/src/components/admin_tools/design-templates/DesignTemplates.tsx",
		"ui/src/components/services/context/ContextServiceList.tsx",
		"ui/src/components/services/mcp/McpServiceList.tsx",
		"schemas/src/main/resources/conf-model/element/service-call.schema.yaml",
	}
	want := []string{"2__General_Functions/readme.md", "1__Graph/readme.md", "7__Design_Templates/readme.md", "4__Context/readme.md", "5__MCP/readme.md", "6__Service_Call/readme.md"}
	got := autoDocumentationCandidates(paths)
	if len(got) != len(want) {
		t.Fatalf("mappings: %+v", got)
	}
	for i := range want {
		if !strings.HasSuffix(got[i].CurrentPath, want[i]) {
			t.Errorf("mapping %s: %s", paths[i], got[i].CurrentPath)
		}
	}
	if !buildOnly([]string{"engine/Dockerfile", "infrastructure/docker/docker-compose.yml"}) || buildOnly([]string{"engine/Dockerfile", "ui/src/pages/Chains.tsx"}) {
		t.Fatal("build-only classification")
	}
}
