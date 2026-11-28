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

func singleCommitPRHead(_ context.Context, pr PR) (PRHead, error) {
	return PRHead{SHA: pr.MergeSHA, CommitCount: 1}, nil
}

func TestBuildEvidence(t *testing.T) {
	dir := t.TempDir()
	git := func(args ...string) string {
		t.Helper()
		b, e := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput()
		if e != nil {
			t.Fatalf("git %v: %v %s", args, e, b)
		}
		return strings.TrimSpace(string(b))
	}
	write := func(path, body string) {
		t.Helper()
		p := filepath.Join(dir, path)
		if e := os.MkdirAll(filepath.Dir(p), 0755); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(p, []byte(body), 0644); e != nil {
			t.Fatal(e)
		}
	}
	git("init", "-b", "main")
	git("config", "user.email", "test@example.com")
	git("config", "user.name", "Test")
	write("help/docs/current.md", "Current documented behavior\n")
	write("src/main/run.go", "old\n")
	git("add", ".")
	git("commit", "-m", "initial")
	initial := git("rev-parse", "HEAD")
	git("checkout", "-b", "feature")
	write("src/main/run.go", "production behavior hidden in test PR\n")
	write("src/test/run_test.go", strings.Repeat("test detail\n", 200))
	git("add", ".")
	git("commit", "-m", "tests")
	git("checkout", "main")
	write("unrelated.txt", "parallel main change\n")
	git("add", ".")
	git("commit", "-m", "main change")
	parent := git("rev-parse", "HEAD")
	git("merge", "--no-ff", "feature", "-m", "merge")
	merge := git("rev-parse", "HEAD")
	write("help/docs/current.md", "dirty worktree should not appear")
	c := Config{RepositoryPath: dir, Repository: "owner/repo", DocsRef: merge, Issue: Issue{Number: 170, Title: "test PR", Body: "full issue body", URL: "https://example.test/170", PRs: []PR{{Number: 42, MergeSHA: merge, Target: "main"}}}, DocsPaths: []string{"help/docs/current.md"}, ResolvePRHead: singleCommitPRHead}
	c.ResolvePRHead = func(ctx context.Context, pr PR) (PRHead, error) {
		if pr.MergeSHA == merge {
			return PRHead{SHA: git("rev-parse", "feature"), CommitCount: 1}, nil
		}
		return singleCommitPRHead(ctx, pr)
	}
	p, r, e := Build(context.Background(), c)
	if e != nil {
		t.Fatal(e)
	}
	if !p.Complete || p.BaseSHA != parent || len(p.DocsSHA) != 40 {
		t.Fatalf("wrong revision or completeness: %+v", p)
	}
	if !strings.Contains(p.Diff, "production behavior") || strings.Contains(p.Diff, "test detail") {
		t.Fatal("production evidence lost or test body retained")
	}
	if len(p.ChangedPaths) != 2 || len(r.OmittedBodies) != 1 || r.OmittedBodies[0].SHA256 == "" {
		t.Fatalf("missing audit: %+v %+v", p.ChangedPaths, r)
	}
	if p.Documentation[0].Content != "Current documented behavior\n" {
		t.Fatal("read dirty documentation")
	}
	t.Run("manual help with a truncated supplementary source", func(t *testing.T) {
		manual := c
		manual.SourcePaths = []string{"src/main/run.go"}
		manual.MaxSourceBytes = 1
		packet, _, err := Build(context.Background(), manual)
		if err != nil || packet.Complete || !packet.CanClassify() {
			t.Fatalf("expected verified diff and current help to reach classification: %v, %v", packet.Limitations, err)
		}
	})
	write("tests/only_test.txt", "second PR test body\n")
	git("add", "tests/only_test.txt")
	git("commit", "-m", "test-only follow-up")
	second := git("rev-parse", "HEAD")
	multi := c
	multi.DocsRef = second
	multi.Issue.PRs = append(append([]PR{}, c.Issue.PRs...), PR{Number: 43, MergeSHA: second, Target: "main"})
	mp, mr, me := Build(context.Background(), multi)
	if me != nil || !mp.Complete || len(mp.ChangedPaths) != 3 || len(mr.PRs) != 2 || !strings.Contains(mp.Diff, "production behavior") {
		t.Fatalf("incomplete issue scope: %+v %+v %v", mp, mr, me)
	}
	only := multi
	only.Issue.PRs = []PR{multi.Issue.PRs[1]}
	op, _, oe := Build(context.Background(), only)
	if oe != nil || !op.Complete || !strings.Contains(op.Diff, "No textual patch bodies") {
		t.Fatalf("test-only evidence: %+v %v", op, oe)
	}

	c.MaxDiffBytes = 50
	p, r, e = Build(context.Background(), c)
	if e != nil || p.Complete || len(p.Diff) > 50 || len(r.OmittedBodies) != 2 {
		t.Fatalf("truncation: %+v %+v %v", p, r, e)
	}
	c.MaxDiffBytes = 0
	c.DocsRef = initial
	if _, _, e = Build(context.Background(), c); e == nil {
		t.Fatal("accepted stale documentation")
	}
	c.DocsRef = merge
	c.DocsPaths = []string{"help/docs/missing.md"}
	p, _, e = Build(context.Background(), c)
	if e != nil || p.Complete || len(p.Limitations) == 0 {
		t.Fatal("accepted missing documentation")
	}
	c.DocsPaths = []string{"help/docs/current.md"}
	c.Issue.PRs[0].Target = "release/0.5"
	if _, _, e = Build(context.Background(), c); e == nil {
		t.Fatal("accepted wrong target")
	}
}

func TestCompressionPaths(t *testing.T) {
	for _, path := range []string{"e2e/specs/a.ts", "engine/src/test/a.java", "tools/tests/a.go", ".apm/skills/x.md", ".agents/skills/x.md", ".claude/skills/x.md", "micro-engine/TESTING.md", "SNAPSHOT-COVERAGE.md"} {
		if !compressed(path) {
			t.Errorf("did not compress %s", path)
		}
	}
	for _, path := range []string{"engine/src/main/a.java", "engine/src/main/tests.java", "help/docs/readme.md", "testing-service/internal/a.go"} {
		if compressed(path) {
			t.Errorf("compressed production %s", path)
		}
	}
}

func TestInvalidConfig(t *testing.T) {
	if _, _, err := Build(context.Background(), Config{}); err == nil {
		t.Fatal("accepted empty config")
	}
	c := Config{RepositoryPath: "/unused", Repository: "owner/repo", DocsRef: "main", Issue: Issue{Number: 1, Title: "issue", PRs: []PR{{Number: 2, MergeSHA: "main", Target: "main"}, {Number: 2, MergeSHA: "main", Target: "main"}}}}
	if _, _, err := Build(context.Background(), c); err == nil || !strings.Contains(err.Error(), "unique positive") {
		t.Fatalf("duplicate validation: %v", err)
	}
}

func TestCurrentDocumentationAndAssets(t *testing.T) {
	dir := t.TempDir()
	git := func(args ...string) string {
		t.Helper()
		b, e := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput()
		if e != nil {
			t.Fatalf("git %v: %v %s", args, e, b)
		}
		return strings.TrimSpace(string(b))
	}
	write := func(p, body string) {
		t.Helper()
		p = filepath.Join(dir, p)
		if e := os.MkdirAll(filepath.Dir(p), 0755); e != nil {
			t.Fatal(e)
		}
		if e := os.WriteFile(p, []byte(body), 0644); e != nil {
			t.Fatal(e)
		}
	}
	git("init", "-b", "main")
	git("config", "user.email", "test@example.com")
	git("config", "user.name", "Test")
	write("help/docs/service/old.md", "initial\n")
	write("help/docs/retained.md", "initial\n")
	write("src/main/run.go", "old\n")
	git("add", ".")
	git("commit", "-m", "initial")
	write("help/docs/service/old.md", "historical intermediate\n")
	write("help/docs/retained.md", "historical intermediate\n")
	write("help/docs/image.svg", "<svg>"+strings.Repeat("base64-noise", 10000)+"</svg>")
	write("src/main/run.go", "actual production behavior\n")
	git("add", ".")
	git("commit", "-m", "change")
	merge := git("rev-parse", "HEAD")
	git("mv", "help/docs/service/old.md", "help/docs/service/readme.md")
	write("help/docs/service/readme.md", "current renamed page\n")
	write("help/docs/retained.md", "current retained page\n")
	write("src/main/consumer.go", "current consumer\n")
	git("add", ".")
	git("commit", "-m", "current documentation")
	docs := git("rev-parse", "HEAD")
	write("src/main/consumer.go", "dirty source")
	c := Config{RepositoryPath: dir, Repository: "owner/repo", DocsRef: docs, Issue: Issue{Number: 852, Title: "service change", PRs: []PR{{Number: 1, MergeSHA: merge, Target: "main"}}}, SourcePaths: []string{"src/main/consumer.go"}, MaxDiffBytes: 1000, ResolvePRHead: singleCommitPRHead}
	p, r, e := Build(context.Background(), c)
	if e != nil {
		t.Fatal(e)
	}
	if !p.Complete || len(p.Documentation) != 2 || len(r.DocumentationMappings) != 2 || len(p.Sources) != 1 || p.Sources[0].Content != "current consumer\n" {
		t.Fatalf("current evidence: %+v %+v", p, r)
	}
	if strings.Contains(p.Diff, "base64-noise") || strings.Contains(p.Diff, "historical intermediate") || !strings.Contains(p.Diff, "actual production behavior") {
		t.Fatal("historical or asset noise entered production patch")
	}
	if len(p.Limitations) == 0 {
		t.Fatal("missing visual-content limitation")
	}
	c.MaxSourceBytes = 3
	p, _, e = Build(context.Background(), c)
	if e != nil || p.Complete {
		t.Fatal("source truncation accepted")
	}
	c.MaxSourceBytes = 0
	write("ui/src/pages/Snapshots.tsx", "changed screen behavior\n")
	git("add", "ui/src/pages/Snapshots.tsx")
	git("commit", "-m", "screen change")
	screenMerge := git("rev-parse", "HEAD")
	screen := c
	screen.DocsRef = screenMerge
	screen.Issue = Issue{Number: 99, Title: "generic screen change", PRs: []PR{{Number: 9, MergeSHA: screenMerge, Target: "main"}}}
	screen.DocsPaths = []string{"help/docs/retained.md"}
	sp, _, se := Build(context.Background(), screen)
	if se != nil || sp.Complete {
		t.Fatal("missing mapped candidate accepted")
	}
	write("help/docs/01__Chains/2__Snapshots/readme.md", "current screen documentation\n")
	git("add", "help/docs/01__Chains/2__Snapshots/readme.md")
	git("commit", "-m", "screen documentation")
	screen.DocsRef = git("rev-parse", "HEAD")
	sp, _, se = Build(context.Background(), screen)
	if se != nil || !sp.Complete || len(sp.Documentation) != 2 {
		t.Fatalf("mapped candidate missing: %+v %v", sp, se)
	}
	screen.MaxDocBytes = 4
	sp, _, se = Build(context.Background(), screen)
	if se != nil || sp.Complete {
		t.Fatal("truncated mapped candidate accepted")
	}

	git("rm", "help/docs/retained.md")
	git("commit", "-m", "remove current page")
	c.DocsRef = git("rev-parse", "HEAD")
	p, _, e = Build(context.Background(), c)
	if e != nil || p.Complete {
		t.Fatal("missing current documentation accepted")
	}
}

func TestOmittedBodyJSONPath(t *testing.T) {
	b, err := json.Marshal(OmittedBody{Path: "micro-engine/TESTING.md"})
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err = json.Unmarshal(b, &fields); err != nil {
		t.Fatal(err)
	}
	if fields["path"] != "micro-engine/TESTING.md" {
		t.Fatalf("missing lowercase path field: %s", b)
	}
	if _, ok := fields["Path"]; ok {
		t.Fatalf("unexpected default field name: %s", b)
	}
}

func TestDocumentationCandidates(t *testing.T) {
	paths := []string{"ui/src/pages/Snapshots.tsx", "ui/src/pages/Deployments.tsx", "ui/src/components/admin_tools/domains/hooks/useEngines.ts", "ui/src/components/admin_tools/variables/VariablesTable.tsx", "ui/src/pages/SnapshotsOther.tsx", "other/Snapshots.tsx"}
	got := documentationCandidates(paths)
	want := []string{"help/docs/01__Chains/2__Snapshots/readme.md", "help/docs/01__Chains/3__Deployments/readme.md", "help/docs/03__Admin_Tools/1__Domains/readme.md", "help/docs/03__Admin_Tools/2__Variables/readme.md"}
	if len(got) != len(want) {
		t.Fatalf("candidates: %+v", got)
	}
	for i, page := range want {
		if got[i].CurrentPath != page || got[i].ChangedPath != paths[i] {
			t.Fatalf("candidate %d: %+v", i, got[i])
		}
	}
}
