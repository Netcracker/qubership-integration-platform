package agentoutcome

import (
	"encoding/json"
	"os"
	"path/filepath"
)

func (report Report) Save(directory string) error {
	if err := os.MkdirAll(directory, 0700); err != nil {
		return err
	}
	for name, snapshot := range map[string]Snapshot{"published": report.Published, "final": report.Final} {
		if snapshot.PatchSHA256 != "" {
			path := filepath.Join(directory, name+".normalized.diff")
			if err := os.WriteFile(path, snapshot.patch, 0600); err != nil {
				return err
			}
		}
	}
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(directory, "outcome.json"), append(data, '\n'), 0600)
}
