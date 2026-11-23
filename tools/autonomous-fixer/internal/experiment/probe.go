package experiment

import (
	"fmt"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"os"
	"path/filepath"
)

func PreparedCommand(root, id, role string) (experimentruntime.Command, error) {
	dir, e := RunDirectory(root, id, role)
	if e != nil {
		return experimentruntime.Command{}, e
	}
	var p Prepared
	if e = ReadJSON(filepath.Join(dir, "prepared.json"), &p); e != nil {
		return experimentruntime.Command{}, e
	}
	b, e := os.ReadFile(filepath.Join(dir, "prompt.txt"))
	if e != nil {
		return experimentruntime.Command{}, e
	}
	if p.PromptHash == "" || Hash(b) != p.PromptHash {
		return experimentruntime.Command{}, fmt.Errorf("prompt differs from the prepared context")
	}
	return experimentruntime.BuildCommand(p.Profile.RuntimeProfile(), p.Snapshot.Directory, string(b))
}
