package experimentworkspace

import (
	"archive/tar"
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestSnapshots(t *testing.T) {
	ctx := context.Background()
	source := t.TempDir()
	git(ctx, source, "init")
	for n, b := range map[string]string{"AGENTS.md": "instructions", ".gitignore": "AGENTS.md\n.apm/\n", ".apm/skill/SKILL.md": "skill"} {
		p := filepath.Join(source, n)
		os.MkdirAll(filepath.Dir(p), 0755)
		os.WriteFile(p, []byte(b), 0644)
	}
	git(ctx, source, "add", "-f", ".")
	if _, e := git(ctx, source, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-m", "initial"); e != nil {
		t.Fatal(e)
	}
	parent := t.TempDir()
	a, e := Create(ctx, source, "HEAD", filepath.Join(parent, "a"), "experiment/a")
	if e != nil {
		t.Fatal(e)
	}
	b, e := Create(ctx, source, "HEAD", filepath.Join(parent, "b"), "experiment/b")
	if e != nil {
		t.Fatal(e)
	}
	if a.TreeSHA != b.TreeSHA || a.SourceSHA != b.SourceSHA {
		t.Fatal("source trees differ")
	}
	for _, s := range []Snapshot{a, b} {
		for _, p := range []string{"AGENTS.md", ".apm/skill/SKILL.md"} {
			if _, e := os.Stat(filepath.Join(s.Directory, p)); e != nil {
				t.Fatal(e)
			}
		}
		count, _ := git(ctx, s.Directory, "rev-list", "--count", "HEAD")
		if count != "1" {
			t.Fatal(count)
		}
		remotes, _ := git(ctx, s.Directory, "remote")
		if remotes != "" {
			t.Fatal(remotes)
		}
	}
	os.WriteFile(filepath.Join(a.Directory, "solution"), []byte("private"), 0644)
	if _, e := os.Stat(filepath.Join(b.Directory, "solution")); !os.IsNotExist(e) {
		t.Fatal("shared files")
	}
	if _, e := Create(ctx, source, "HEAD", a.Directory, "experiment/c"); e == nil {
		t.Fatal("existing destination accepted")
	}
}
func TestArchiveRejectsEscapes(t *testing.T) {
	for _, h := range []*tar.Header{{Name: "../escape", Typeflag: tar.TypeReg}, {Name: "link", Linkname: "../../escape", Typeflag: tar.TypeSymlink}, {Name: "/absolute", Typeflag: tar.TypeReg}} {
		var b bytes.Buffer
		w := tar.NewWriter(&b)
		w.WriteHeader(h)
		w.Close()
		if e := extract(&b, t.TempDir()); e == nil {
			t.Fatal(h.Name)
		}
	}
}
func TestSnapshotRejectsOmittedInstructions(t *testing.T) {
	ctx := context.Background()
	source := t.TempDir()
	git(ctx, source, "init")
	os.WriteFile(filepath.Join(source, "AGENTS.md"), []byte("instructions"), 0644)
	os.WriteFile(filepath.Join(source, ".gitattributes"), []byte("AGENTS.md export-ignore\n"), 0644)
	git(ctx, source, "add", ".")
	if _, e := git(ctx, source, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-m", "initial"); e != nil {
		t.Fatal(e)
	}
	destination := filepath.Join(t.TempDir(), "snapshot")
	if _, e := Create(ctx, source, "HEAD", destination, "experiment/a"); e == nil {
		t.Fatal("omitted instructions accepted")
	}
	if _, e := os.Stat(destination); !os.IsNotExist(e) {
		t.Fatal("failed snapshot was retained")
	}
}
func TestArchiveRejectsSymlinkParent(t *testing.T) {
	var b bytes.Buffer
	w := tar.NewWriter(&b)
	w.WriteHeader(&tar.Header{Name: "link", Typeflag: tar.TypeSymlink, Linkname: "target"})
	w.WriteHeader(&tar.Header{Name: "link/file", Typeflag: tar.TypeReg, Mode: 0644})
	w.Close()
	if e := extract(&b, t.TempDir()); e == nil {
		t.Fatal("symlink parent accepted")
	}
}
