package experimentusage

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
)

// The subprocess emits counters and model identifiers, never message text.
// editorconfig-checker-disable: preserve Python indentation.
const sqliteUsageScript = `import json, sqlite3, sys
from pathlib import Path
conn = sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
conn.execute('BEGIN')
sessions = []
for row in conn.execute('SELECT id, model, tokens_input, tokens_output, tokens_reasoning, tokens_cache_read, tokens_cache_write FROM session_v2 ORDER BY id'):
    sessions.append(dict(zip(('id','model','input','output','reasoning','cache_read','cache_write'), row)))
messages = []
for message_id, session_id, kind, raw in conn.execute('SELECT id, session_id, type, data FROM session_message ORDER BY id'):
    if kind not in ('assistant', 'compaction'):
        continue
    data = json.loads(raw)
    messages.append({'id': message_id, 'session_id': session_id, 'type': kind, 'tokens': data.get('tokens'), 'model': data.get('model')})
print(json.dumps({'sessions': sessions, 'messages': messages}))
conn.rollback()
conn.close()
`

// editorconfig-checker-enable

type openCodeTokens struct {
	Input     *int64 `json:"input"`
	Output    *int64 `json:"output"`
	Reasoning *int64 `json:"reasoning"`
	Cache     struct {
		Read  *int64 `json:"read"`
		Write *int64 `json:"write"`
	} `json:"cache"`
}

func (t openCodeTokens) usage() experimentcost.Usage {
	u := experimentcost.Usage{Input: t.Input, Output: t.Output, Reasoning: t.Reasoning, CachedInput: t.Cache.Read, CacheWrite: t.Cache.Write}
	// OpenCode keeps visible output and noncached input in separate buckets.
	input := sum(experimentcost.Usage{Input: t.Input}, experimentcost.Usage{Input: t.Cache.Read}).Input
	u.Input = sum(experimentcost.Usage{Input: input}, experimentcost.Usage{Input: t.Cache.Write}).Input
	u.Output = sum(experimentcost.Usage{Output: t.Output}, experimentcost.Usage{Output: t.Reasoning}).Output
	return u
}
func openCodeModel(raw json.RawMessage) string {
	if len(raw) == 0 || string(raw) == "null" {
		return ""
	}
	if string(raw)[0] == '"' {
		value := text(raw)
		if strings.HasPrefix(value, "{") {
			return openCodeModel(json.RawMessage(value))
		}
		return value
	}
	var model struct {
		ProviderID string `json:"providerID"`
		ID         string `json:"id"`
		ModelID    string `json:"modelID"`
	}
	if json.Unmarshal(raw, &model) != nil {
		return ""
	}
	if model.ID != "" {
		model.ModelID = model.ID
	}
	if model.ProviderID != "" && model.ModelID != "" {
		return model.ProviderID + "/" + model.ModelID
	}
	return model.ModelID
}
func readOpenCode(expected, dir string) (Report, error) {
	path := filepath.Join(dir, "home", "data", "opencode", "opencode.db")
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return openCodeFallback(expected, dir)
	} else if err != nil {
		return Report{}, err
	}
	r := report("opencode_sqlite")
	raw, err := exec.Command("python3", "-c", sqliteUsageScript, path).Output()
	if err != nil {
		return r, fmt.Errorf("read OpenCode usage database: %w", err)
	}
	var data struct {
		Sessions []struct {
			ID         string          `json:"id"`
			Model      json.RawMessage `json:"model"`
			Input      *int64          `json:"input"`
			Output     *int64          `json:"output"`
			Reasoning  *int64          `json:"reasoning"`
			CacheRead  *int64          `json:"cache_read"`
			CacheWrite *int64          `json:"cache_write"`
		} `json:"sessions"`
		Messages []struct {
			ID        json.RawMessage `json:"id"`
			Type      string          `json:"type"`
			SessionID string          `json:"session_id"`
			Tokens    *openCodeTokens `json:"tokens"`
			Model     json.RawMessage `json:"model"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return r, fmt.Errorf("decode OpenCode usage counters: %w", err)
	}
	if len(data.Sessions) == 0 {
		r.warn("native database contains no sessions")
		return r, nil
	}
	r.Tokens = zero()
	r.nativeSessions = map[string][]nativeRequest{}
	r.nativeTotals = map[string]experimentcost.Usage{}
	observed := zero()
	r.SessionCount = len(data.Sessions)
	sessionModels := map[string]string{}
	for _, s := range data.Sessions {
		r.nativeSessions[s.ID] = []nativeRequest{}
		sessionModels[s.ID] = openCodeModel(s.Model)
		t := openCodeTokens{Input: s.Input, Output: s.Output, Reasoning: s.Reasoning}
		t.Cache.Read, t.Cache.Write = s.CacheRead, s.CacheWrite
		r.nativeTotals[s.ID] = t.usage()
		r.Tokens = sum(r.Tokens, t.usage())
	}
	for _, m := range data.Messages {
		model := openCodeModel(m.Model)
		if model == "" {
			model = sessionModels[m.SessionID]
			if model == "" {
				model = expected
				r.warn("message and session models are missing; using expected model")
			} else {
				r.warn("message model is missing; using session model")
			}
		}
		var u experimentcost.Usage
		if m.Tokens != nil {
			u = m.Tokens.usage()
		} else {
			r.warn("message token usage is missing")
		}
		r.addRequest(model, u)
		identity, err := json.Marshal(m)
		if err != nil {
			return r, err
		}
		r.nativeSessions[m.SessionID] = append(r.nativeSessions[m.SessionID], nativeRequest{Identity: string(identity), Model: model, Usage: u})
		observed = sum(observed, u)
	}
	if !validUsage(r.Tokens) {
		r.warn("session totals have unknown or invalid token counts")
	}
	remainder, valid := difference(r.Tokens, observed)
	if !valid {
		r.UnattributedUsage = &remainder
		r.warn("session totals cannot be reconciled with observed message usage")
	} else if nonzero(remainder) {
		r.UnattributedUsage = &remainder
		r.warn("session totals include auxiliary usage without request or model attribution")
	}
	if r.RequestCount == 0 {
		r.warn("native database contains no assistant or compaction usage")
	}
	r.finish()
	return r, nil
}
func openCodeFallback(expected, dir string) (Report, error) {
	r := report("opencode_stdout")
	r.warn("native database is missing; stdout steps omit compaction and auxiliary usage")
	seen := map[string]bool{}
	sessions := map[string]bool{}
	observed := zero()
	found := false
	err := jsonLines(stdoutPath(dir), func(e map[string]json.RawMessage) error {
		if text(e["type"]) != "step_finish" {
			return nil
		}
		var part struct {
			ID        string          `json:"id"`
			MessageID string          `json:"messageID"`
			SessionID string          `json:"sessionID"`
			Tokens    *openCodeTokens `json:"tokens"`
		}
		if err := json.Unmarshal(e["part"], &part); err != nil {
			return err
		}
		if part.Tokens == nil {
			r.warn("stdout step token usage is missing")
			return nil
		}
		session := text(e["sessionID"])
		if session == "" {
			session = part.SessionID
		}
		if session != "" {
			sessions[session] = true
		}
		if part.ID != "" || part.MessageID != "" {
			key := strings.Join([]string{session, part.MessageID, part.ID}, "\x00")
			if seen[key] {
				return nil
			}
			seen[key] = true
		}
		u := part.Tokens.usage()
		observed = sum(observed, u)
		r.addRequest(expected, u)
		found = true
		return nil
	})
	if os.IsNotExist(err) {
		r.warn("stdout usage is missing")
		return r, nil
	}
	if err != nil {
		return r, err
	}
	if found {
		r.Tokens = observed
	} else {
		r.warn("stdout contains no step usage")
	}
	r.SessionCount = len(sessions)
	r.finish()
	return r, nil
}
