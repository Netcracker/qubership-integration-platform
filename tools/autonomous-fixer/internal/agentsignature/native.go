package agentsignature

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type Identity struct {
	Model  string `json:"model"`
	Effort string `json:"effort,omitempty"`
}

const IdentityFilename = "observed-agent.json"

type nativeSession struct {
	ID        string
	Parent    *string
	Input     *int64
	Output    *int64
	Cached    *int64
	Write     *int64
	Reasoning *int64
	Cost      *float64
	Model     json.RawMessage
	conflict  bool
}

func (s nativeSession) cumulative(prior nativeSession) nativeSession {
	s.conflict = prior.conflict || regresses(prior.Input, s.Input) || regresses(prior.Output, s.Output) ||
		regresses(prior.Cached, s.Cached) || regresses(prior.Write, s.Write) ||
		regresses(prior.Reasoning, s.Reasoning) || regresses(prior.Cost, s.Cost)
	if s.conflict {
		s.Input, s.Output, s.Cached, s.Write, s.Reasoning, s.Cost = nil, nil, nil, nil, nil, nil
	}
	return s
}

func regresses[T int64 | float64](prior, current *T) bool {
	return prior != nil && current != nil && *current < *prior
}

func ReadIdentity(stage, runtime string) (Identity, error) {
	identity, _, err := readStage(stage, runtime)
	return identity, err
}

func readStage(stage, runtime string) (Identity, []nativeSession, error) {
	id, err := rootSessionID(stage)
	if err != nil {
		return Identity{}, nil, err
	}
	switch runtime {
	case "codex":
		identity, err := readCodexIdentity(stage, id)
		return identity, nil, err
	case "opencode":
		return readOpenCodeStage(stage, id)
	default:
		return Identity{}, nil, nil
	}
}

func rootSessionID(dir string) (string, error) {
	var metadata struct {
		ID string `json:"session_id"`
	}
	if err := readFile(dir, "session.json", &metadata); err != nil && !os.IsNotExist(err) {
		return "", err
	}
	path, err := checkedPath(dir, "stdout.jsonl")
	if os.IsNotExist(err) {
		return metadata.ID, nil
	}
	if err != nil || metadata.ID != "" {
		return metadata.ID, err
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	lines := scanner(f)
	for lines.Scan() {
		var evt struct {
			Type      string `json:"type"`
			ThreadID  string `json:"thread_id"`
			SessionID string `json:"sessionID"`
		}
		if json.Unmarshal(lines.Bytes(), &evt) != nil {
			continue
		}
		if evt.Type == "thread.started" && evt.ThreadID != "" {
			return evt.ThreadID, nil
		}
		if evt.SessionID != "" {
			return evt.SessionID, nil
		}
	}
	return "", lines.Err()
}

func readCodexIdentity(dir, id string) (Identity, error) {
	path, err := checkedPath(dir, "home/.codex/sessions")
	if os.IsNotExist(err) {
		return Identity{}, nil
	}
	if err != nil {
		return Identity{}, err
	}
	var ids []Identity
	err = filepath.WalkDir(path, func(file string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.Type()&os.ModeSymlink != 0 || !entry.IsDir() && !entry.Type().IsRegular() {
			return fmt.Errorf("unsafe native session entry")
		}
		if !entry.IsDir() && filepath.Ext(file) == ".jsonl" {
			var model Identity
			model, err = codexSessionIdentity(file, id)
			if model.Model != "" {
				ids = append(ids, model)
			}
		}
		return err
	})
	if err != nil {
		return Identity{}, err
	}
	if len(ids) == 1 {
		return ids[0], nil
	}
	return Identity{}, nil
}

func codexSessionIdentity(path, id string) (Identity, error) {
	f, err := os.Open(path)
	if err != nil {
		return Identity{}, err
	}
	defer f.Close()
	lines := scanner(f)
	var current Identity
	var session string
	for lines.Scan() {
		var event struct {
			Type    string `json:"type"`
			Payload struct {
				Identity
				ID string `json:"id"`
			} `json:"payload"`
		}
		if json.Unmarshal(lines.Bytes(), &event) != nil {
			continue
		}
		switch event.Type {
		case "session_meta":
			if session == "" {
				session = event.Payload.ID
				if id != "" && session != id {
					return Identity{}, nil
				}
			}
		case "turn_context":
			current = cleanIdentity(event.Payload.Identity)
		}
	}
	return current, lines.Err()
}

func scanner(file *os.File) *bufio.Scanner {
	s := bufio.NewScanner(file)
	s.Buffer(make([]byte, 64<<10), 16<<20)
	return s
}

func cleanIdentity(value Identity) Identity {
	if !identifier.MatchString(value.Model) {
		return Identity{}
	}
	if !effort.MatchString(value.Effort) || value.Effort == "default" {
		value.Effort = ""
	}
	return value
}

// editorconfig-checker-disable: preserve Python indentation.
const readOpenCodeEvidence = `import json, sqlite3, sys
from pathlib import Path
db = sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
db.execute('BEGIN')
columns = {field[1] for field in db.execute('PRAGMA table_info(session_v2)')}
fields = {
    "ID":"id", "Parent":"parent_id", "Input":"tokens_input", "Output":"tokens_output",
    "Cached":"tokens_cache_read", "Write":"tokens_cache_write", "Reasoning":"tokens_reasoning", "Cost":"cost"
}
projection = ', '.join(field if field in columns else 'NULL' for field in fields.values())
data = []
for row in db.execute('SELECT ' + projection + ' FROM session_v2 ORDER BY id'):
    item = dict(zip(fields.keys(), row))
    message = db.execute("SELECT json_extract(data, '$.model'), json_extract(data, '$.cost') FROM session_message WHERE session_id=? AND type='assistant' ORDER BY id DESC LIMIT 1", (item["ID"],)).fetchone()
    item["Model"] = json.loads(message[0]) if message is not None and message[0] is not None else None
    if item["Cost"] == 0 and (message is None or message[1] is None):
        item["Cost"] = None
    data.append(item)
print(json.dumps({"parent_known":"parent_id" in columns,"sessions":data}, allow_nan=False))
db.close()
`

// editorconfig-checker-enable

func readOpenCodeStage(dir, id string) (Identity, []nativeSession, error) {
	const db = "home/data/opencode/opencode.db"
	path, err := checkedPath(dir, db)
	if os.IsNotExist(err) {
		return Identity{}, nil, nil
	}
	if err != nil {
		return Identity{}, nil, err
	}
	for _, suffix := range []string{"-wal", "-shm"} {
		if _, err := checkedPath(dir, db+suffix); err != nil && !os.IsNotExist(err) {
			return Identity{}, nil, err
		}
	}
	out, err := exec.Command("python3", "-I", "-c", readOpenCodeEvidence, path).Output()
	if err != nil {
		return Identity{}, nil, fmt.Errorf("read observed OpenCode metadata: %w", err)
	}
	var data struct {
		ParentKnown bool `json:"parent_known"`
		Sessions    []nativeSession
	}
	if err := json.Unmarshal(out, &data); err != nil {
		return Identity{}, nil, err
	}
	var match []Identity
	for _, session := range data.Sessions {
		if session.ID == "" || id != "" && session.ID != id || id == "" && session.Parent != nil {
			continue
		}
		if id == "" && !data.ParentKnown && len(data.Sessions) != 1 {
			continue
		}
		var model struct {
			Provider string `json:"providerID"`
			ID       string `json:"id"`
			Model    string `json:"modelID"`
			Variant  string `json:"variant"`
		}
		identity := Identity{}
		if json.Unmarshal(session.Model, &model) == nil {
			if model.ID == "" {
				model.ID = model.Model
			}
			if strings.TrimSpace(model.Provider) != "" && model.ID != "" {
				model.ID = model.Provider + "/" + model.ID
			}
			identity = cleanIdentity(Identity{Model: model.ID, Effort: model.Variant})
		}
		match = append(match, identity)
	}
	if len(match) == 1 {
		return match[0], data.Sessions, nil
	}
	return Identity{}, data.Sessions, nil
}
