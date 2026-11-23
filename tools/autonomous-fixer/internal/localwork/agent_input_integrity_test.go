package localwork

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func TestAgentRejectsChangedTaskAttachmentBeforeModelStarts(t *testing.T) {
	for _, stage := range []workcycle.Stage{workcycle.AuthorStage, workcycle.ReviewStage, workcycle.VerifyStage} {
		t.Run(string(stage), func(t *testing.T) {
			ctx := context.Background()
			cfg := agentFixture(t)
			cfg.Workflow = "agent"
			cfg.Context.AttachmentsAsFiles = true
			d, err := Prepare(ctx, cfg)
			if err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(cfg.TaskDirectory, "policy.md"), []byte("A changed requirement."), 0600); err != nil {
				t.Fatal(err)
			}
			if stage == workcycle.AuthorStage {
				_, err = d.Author(ctx, workcycle.AgentAuthorRequest{Request: d.Request})
			} else {
				_, err = d.Assess(ctx, workcycle.AgentAssessmentRequest{Request: d.Request, Stage: stage})
			}
			if err == nil || !strings.Contains(err.Error(), "policy.md") {
				t.Fatalf("changed attachment reached the %s runtime: %v", stage, err)
			}
			command := filepath.Join(cfg.OutputDirectory, "step-0001-"+string(stage), "command.json")
			if _, err := os.Stat(command); !os.IsNotExist(err) {
				t.Fatalf("runtime command was recorded for changed input: %v", err)
			}
		})
	}
}

func TestAgentReportsTaskAttachmentChangedDuringModelTurn(t *testing.T) {
	ctx := context.Background()
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	attachment := filepath.Join(cfg.TaskDirectory, "policy.md")
	cfg.Environment["QIP_TEST_TASK_ATTACHMENT"] = attachment
	wrapper, err := os.ReadFile(cfg.RuntimeBinary)
	if err != nil {
		t.Fatal(err)
	}
	wrapper = []byte(strings.Replace(string(wrapper), "\nexec ", "\nprintf 'Changed during the turn.' > \"$QIP_TEST_TASK_ATTACHMENT\"\nexec ", 1))
	if err := os.WriteFile(cfg.RuntimeBinary, wrapper, 0700); err != nil {
		t.Fatal(err)
	}
	report, err := Execute(ctx, cfg)
	if err == nil || !strings.Contains(err.Error(), "policy.md") || report.Status != "verification-blocked" {
		t.Fatal(report.Status, err)
	}
	steps, err := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if err != nil || len(steps) != 1 || !strings.HasSuffix(steps[0], "-author") {
		t.Fatal("the next stage ran on changed task input", steps, err)
	}
}

func TestReadOnlyReviewCopiesBoundAttachmentThroughInTaskSymlink(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	attachment := filepath.Join(cfg.TaskDirectory, "policy.md")
	target := filepath.Join(cfg.TaskDirectory, "policy-source.md")
	if err := os.Rename(attachment, target); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, attachment); err != nil {
		t.Fatal(err)
	}
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	directory := t.TempDir()
	if err := d.copyReviewAttachments(directory); err != nil {
		t.Fatal(err)
	}
	copied, err := os.ReadFile(filepath.Join(directory, "policy.md"))
	if err != nil || string(copied) != "Preserve literal values in the classic engine." {
		t.Fatal("the isolated review missed a bound attachment", string(copied), err)
	}
}

func TestCandidateSnapshotTracksBothSidesOfRename(t *testing.T) {
	ctx := context.Background()
	d, err := Prepare(ctx, agentFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := git(ctx, d.Config.Workspace, "mv", "engine/code.txt", "engine/renamed.txt"); err != nil {
		t.Fatal(err)
	}
	snapshot, err := d.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(snapshot.ChangedPaths, []string{"engine/code.txt", "engine/renamed.txt"}) ||
		snapshot.FileHashes["engine/code.txt"] != "deleted" {
		t.Fatal("rename lost its original source path", snapshot)
	}
}

func TestReadOnlyReviewCopiesForceAddedFileMatchedByGitignore(t *testing.T) {
	ctx := context.Background()
	cfg := agentFixture(t)
	if err := os.WriteFile(filepath.Join(cfg.Workspace, ".gitignore"), []byte("/engine/generated.txt\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, args := range [][]string{{"add", ".gitignore"}, {"-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "ignore generated files"}} {
		if _, err := git(ctx, cfg.Workspace, args...); err != nil {
			t.Fatal(err)
		}
	}
	d, err := Prepare(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	file := "engine/generated.txt"
	if err := os.WriteFile(filepath.Join(cfg.Workspace, file), []byte("Track this generated source."), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := git(ctx, cfg.Workspace, "add", "--force", file); err != nil {
		t.Fatal(err)
	}
	candidate, err := d.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.copyReviewSource(ctx, t.TempDir(), candidate); err != nil {
		t.Fatal(err)
	}
}

func TestAuthorHelperRecordsSetupFailureWithoutNativeProcess(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	helper := cfg.Profile
	cfg.AuthorHelperProfile = &helper
	d, err := Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	parent, _, err := d.agentStep("author")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := d.runAuthorHelper(ctx, d.Request, parent, 1, "Read the source."); err == nil {
		t.Fatal("canceled helper unexpectedly completed")
	}
	dir := filepath.Join(parent, "helper-0001")
	var receipt struct {
		Stage   string `json:"stage"`
		Started *bool  `json:"native_process_started"`
		Error   string `json:"error"`
	}
	readAgentJSON(t, filepath.Join(dir, "helper-setup-failure.json"), &receipt)
	if receipt.Stage != "author-helper" || receipt.Started == nil || *receipt.Started || receipt.Error == "" {
		t.Fatal("helper setup failure receipt lost the cause or execution state", receipt)
	}
	if _, err := os.Stat(filepath.Join(dir, "process.json")); !os.IsNotExist(err) {
		t.Fatalf("setup failure recorded a native process: %v", err)
	}
}
