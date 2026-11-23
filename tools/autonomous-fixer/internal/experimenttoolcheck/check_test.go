package experimenttoolcheck

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experiment"
)

func TestRequiresExplicitPaths(t *testing.T) {
	if _, e := Run(context.Background(), Config{}); e == nil {
		t.Fatal("implicit tool paths accepted")
	}
}

func TestDirectHostToolOrchestration(t *testing.T) {
	root := t.TempDir()
	workspace := t.TempDir()
	node := t.TempDir()
	maven := t.TempDir()
	baseline := t.TempDir()
	for _, dir := range []string{filepath.Join(node, "bin"), filepath.Join(maven, "bin")} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"java", "mvn", "fc-match"} {
		if err := os.WriteFile(filepath.Join(node, "bin", name), []byte("#!/bin/sh\nprintf 'fake tool\\n'\n"), 0700); err != nil {
			t.Fatal(err)
		}
	}
	nodeScript := `#!/bin/sh
test -d "$HOME"
test -z "$TOOLCHECK_INHERITED_SECRET"
/bin/mkdir -p "$HOME/evidence"
printf 'fake image' > "$HOME/evidence/chains.png"
printf '%s' "$PWD" > "$HOME/evidence/cwd.txt"
`
	if err := os.WriteFile(filepath.Join(node, "bin", "node"), []byte(nodeScript), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(baseline, "unchanged"), []byte("baseline"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TOOLCHECK_INHERITED_SECRET", "must not inherit")
	r, err := Run(context.Background(), Config{Root: root, Workspace: workspace, Tools: experiment.ToolPaths{Node: node, Maven: maven, E2ENodeModules: baseline, Browsers: t.TempDir()}})
	if err != nil || !r.Success || !strings.HasPrefix(r.IsolationClaim, "none;") || r.DependencySource != baseline {
		t.Fatalf("%+v %v", r, err)
	}
	b, err := os.ReadFile(filepath.Join(r.Directory, "home/evidence/cwd.txt"))
	if err != nil || string(b) != workspace {
		t.Fatalf("cwd=%q %v", b, err)
	}
	b, err = os.ReadFile(filepath.Join(baseline, "unchanged"))
	if err != nil || string(b) != "baseline" {
		t.Fatal("baseline modified")
	}
}
func TestActualTools(t *testing.T) {
	root := os.Getenv("QIP_TOOLCHECK_ROOT")
	workspace := os.Getenv("QIP_TOOLCHECK_WORKSPACE")
	if root == "" || workspace == "" {
		t.Skip("set QIP_TOOLCHECK_ROOT and QIP_TOOLCHECK_WORKSPACE for the allocated read-only stack smoke")
	}
	data, e := os.ReadFile(filepath.Join(root, "tools.json"))
	if e != nil {
		t.Fatal(e)
	}
	var tools experiment.ToolPaths
	if e = json.Unmarshal(data, &tools); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	report, e := Run(ctx, Config{Root: root, Workspace: workspace, Tools: tools})
	t.Logf("report=%s screenshot=%s", report.ReportPath, report.Screenshot)
	if e != nil {
		t.Fatal(e)
	}
}
