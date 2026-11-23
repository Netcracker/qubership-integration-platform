package docgithub

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/localwork"
)

func prepareSnapshotFixture(t *testing.T) (*localwork.Driver, string, string) {
	t.Helper()
	plan, run, source := publicationFixture(t, false)
	cfg := localwork.Config{
		Workspace:       source,
		TaskDirectory:   filepath.Join(filepath.Dir(run), "task"),
		OutputDirectory: filepath.Join(filepath.Dir(run), "snapshot-run"),
		TaskKind:        "documentation",
		RuntimeBinary:   "/unused/fixture",
		RuntimeVersion:  "fixture runtime",
		Profile:         experimentruntime.Profile{Runtime: "codex", Model: "fixture", Effort: "low"},
	}
	driver, err := localwork.Prepare(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	return driver, source, plan.BaseSHA
}

func TestPublicationSnapshotMatchesWorkSnapshot(t *testing.T) {
	ctx := context.Background()
	driver, source, base := prepareSnapshotFixture(t)
	old, added := "help/docs/readme.md", "help/docs/renamed.md"
	if _, err := gitRead(ctx, source, "mv", old, added); err != nil {
		t.Fatal(err)
	}
	deleted := ".agents/skills/docs-authoring/SKILL.md"
	if err := os.Remove(filepath.Join(source, deleted)); err != nil {
		t.Fatal(err)
	}
	untracked := "help/docs/new.md"
	putText(t, filepath.Join(source, untracked), "New help.\n")
	target := filepath.Join(t.TempDir(), "destination")
	putText(t, target, "Original target.\n")
	link := "help/docs/link.md"
	if err := os.Symlink(target, filepath.Join(source, link)); err != nil {
		t.Fatal(err)
	}
	expected, err := driver.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	putText(t, target, "Changed target.\n")
	actual, err := localwork.SnapshotWorkspace(ctx, source, base)
	if err != nil || !reflect.DeepEqual(actual, expected) {
		t.Fatalf("publisher snapshot = %+v, work snapshot = %+v, error = %v", actual, expected, err)
	}
	if !slices.Equal(actual.ChangedPaths, []string{deleted, link, untracked, old, added}) ||
		actual.FileHashes[deleted] != "deleted" || actual.FileHashes[old] != "deleted" {
		t.Fatalf("snapshot omitted rename or deletion paths: %+v", actual)
	}
	info, err := os.Lstat(filepath.Join(source, link))
	if err != nil || actual.FileHashes[link] != bytesHash([]byte(info.Mode().String()+"\x00"+target)) {
		t.Fatal("snapshot did not hash the link itself", info, err)
	}
}

func TestSnapshotRejectsTrackedFileUnderEscapingParentLink(t *testing.T) {
	ctx := context.Background()
	driver, source, base := prepareSnapshotFixture(t)
	parent := filepath.Join(source, "help", "docs")
	if err := os.Remove(filepath.Join(parent, "readme.md")); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(parent); err != nil {
		t.Fatal(err)
	}
	target := t.TempDir()
	putText(t, filepath.Join(target, "readme.md"), "Outside the checkout.\n")
	if err := os.Symlink(target, parent); err != nil {
		t.Fatal(err)
	}
	if _, err := driver.Snapshot(ctx); err == nil {
		t.Fatal("work snapshot followed a link outside the checkout")
	}
	if _, err := localwork.SnapshotWorkspace(ctx, source, base); err == nil {
		t.Fatal("publisher snapshot followed a link outside the checkout")
	}
}
