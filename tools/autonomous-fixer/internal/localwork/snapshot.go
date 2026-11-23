package localwork

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func (d *Driver) Snapshot(ctx context.Context) (workcycle.Snapshot, error) {
	return SnapshotWorkspace(ctx, d.Config.Workspace, d.base)
}

func SnapshotWorkspace(ctx context.Context, workspace, base string) (workcycle.Snapshot, error) {
	files, err := git(ctx, workspace, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
	if err != nil {
		return workcycle.Snapshot{}, err
	}
	root, err := os.OpenRoot(workspace)
	if err != nil {
		return workcycle.Snapshot{}, err
	}
	defer root.Close()
	paths := strings.Split(strings.TrimSuffix(string(files), "\x00"), "\x00")
	sort.Strings(paths)
	h := sha256.New()
	seen := map[string]bool{}
	hashes := map[string]string{}
	for _, name := range paths {
		if name == "" || seen[name] {
			continue
		}
		seen[name] = true
		info, err := root.Lstat(name)
		if os.IsNotExist(err) {
			hashes[name] = "deleted"
			fmt.Fprintf(h, "%s\x00deleted\x00", name)
			continue
		}
		if err != nil {
			return workcycle.Snapshot{}, err
		}
		var data []byte
		if info.Mode()&os.ModeSymlink != 0 {
			target, err := root.Readlink(name)
			if err != nil {
				return workcycle.Snapshot{}, err
			}
			data = []byte(target)
		} else if info.Mode().IsRegular() {
			data, err = root.ReadFile(name)
			if err != nil {
				return workcycle.Snapshot{}, err
			}
		} else {
			return workcycle.Snapshot{}, fmt.Errorf("unsupported source entry %s", name)
		}
		fmt.Fprintf(h, "%s\x00%d\x00%d\x00", name, info.Mode(), len(data))
		h.Write(data)
		digest := sha256.Sum256(append([]byte(fmt.Sprint(info.Mode())+"\x00"), data...))
		hashes[name] = hex.EncodeToString(digest[:])
	}
	changed, err := git(ctx, workspace, "diff", "--name-only", "-z", "--no-renames", "--no-ext-diff", "--no-textconv", base, "--")
	if err != nil {
		return workcycle.Snapshot{}, err
	}
	untracked, err := git(ctx, workspace, "ls-files", "-z", "--others", "--exclude-standard")
	if err != nil {
		return workcycle.Snapshot{}, err
	}
	var changedPaths []string
	seen = map[string]bool{}
	for _, name := range strings.Split(string(changed)+string(untracked), "\x00") {
		if name != "" && !seen[name] {
			changedPaths = append(changedPaths, name)
			seen[name] = true
		}
	}
	sort.Strings(changedPaths)
	var changedHashes map[string]string
	if len(changedPaths) > 0 {
		changedHashes = map[string]string{}
	}
	for _, name := range changedPaths {
		hash, exists := hashes[name]
		if !exists {
			hash = "deleted"
		}
		changedHashes[name] = hash
	}
	return workcycle.Snapshot{ID: hex.EncodeToString(h.Sum(nil)), ChangedPaths: changedPaths, FileHashes: changedHashes}, nil
}
