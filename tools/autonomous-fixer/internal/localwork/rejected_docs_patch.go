package localwork

import (
	"bytes"
	"context"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"unicode/utf8"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func RejectedDocumentationPatch(ctx context.Context, workspace, base string, recorded workcycle.Snapshot) ([]byte, error) {
	if len(recorded.ChangedPaths) == 0 {
		return nil, nil
	}
	head, err := git(ctx, workspace, "rev-parse", "HEAD")
	if err != nil || strings.TrimSpace(string(head)) != base {
		return nil, fmt.Errorf("rejected documentation checkout has another base")
	}
	current, err := SnapshotWorkspace(ctx, workspace, base)
	if err != nil || !reflect.DeepEqual(recorded, current) {
		return nil, fmt.Errorf("rejected documentation checkout differs from its recorded snapshot")
	}
	for _, name := range current.ChangedPaths {
		if !strings.HasPrefix(name, "help/docs/") || filepath.Clean(name) != name || strings.ContainsAny(name, "\\\r\n\t\x00") {
			return nil, fmt.Errorf("rejected candidate contains a path outside the supported help scope")
		}
	}
	meta, err := git(ctx, workspace, "diff", "--raw", "-z", "--no-renames", "--no-ext-diff", "--no-textconv", base, "--")
	if err != nil {
		return nil, fmt.Errorf("cannot read rejected documentation diff metadata")
	}
	entries := strings.Split(strings.TrimSuffix(string(meta), "\x00"), "\x00")
	if len(entries) != 2*len(current.ChangedPaths) {
		return nil, fmt.Errorf("rejected documentation diff requires tracked paths")
	}
	regularMode := func(mode string) bool {
		return mode == "000000" || mode == "100644" || mode == "100755"
	}
	tracked := make(map[string]bool, len(current.ChangedPaths))
	for i := 0; i < len(entries); i += 2 {
		record := strings.Fields(strings.TrimPrefix(entries[i], ":"))
		if len(record) != 5 || !regularMode(record[0]) || !regularMode(record[1]) {
			return nil, fmt.Errorf("rejected documentation diff requires regular files")
		}
		tracked[entries[i+1]] = true
	}
	for _, name := range current.ChangedPaths {
		if !tracked[name] {
			return nil, fmt.Errorf("rejected documentation diff requires tracked paths")
		}
	}
	patch, err := git(ctx, workspace, "diff", "--no-color", "--no-renames", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/", base, "--")
	if err != nil || len(patch) == 0 {
		return nil, fmt.Errorf("cannot read rejected documentation diff")
	}
	if !utf8.Valid(patch) || bytes.Contains(patch, []byte("\nBinary files ")) || bytes.ContainsRune(patch, 0) {
		return nil, fmt.Errorf("rejected documentation diff requires text files")
	}
	after, err := SnapshotWorkspace(ctx, workspace, base)
	if err != nil || !reflect.DeepEqual(current, after) {
		return nil, fmt.Errorf("rejected documentation checkout changed during diff capture")
	}
	return patch, nil
}
