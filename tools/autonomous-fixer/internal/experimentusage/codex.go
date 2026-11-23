package experimentusage

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
)

type codexTokens struct {
	Input     *int64 `json:"input_tokens"`
	Cached    *int64 `json:"cached_input_tokens"`
	Write     *int64 `json:"cache_write_input_tokens"`
	Output    *int64 `json:"output_tokens"`
	Reasoning *int64 `json:"reasoning_output_tokens"`
}

func (t codexTokens) usage() experimentcost.Usage {
	return experimentcost.Usage{Input: t.Input, CachedInput: t.Cached, CacheWrite: t.Write, Output: t.Output, Reasoning: t.Reasoning}
}

type codexRequest struct {
	Identity string
	Model    string
	Usage    experimentcost.Usage
}
type codexSession struct {
	ID       string
	Requests []codexRequest
	Tokens   experimentcost.Usage
	Warnings []string
}

func readCodex(expected, dir string) (Report, error) {
	paths := []string{}
	root := filepath.Join(dir, "home", ".codex", "sessions")
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && filepath.Ext(path) == ".jsonl" {
			paths = append(paths, path)
		}
		return nil
	})
	if err != nil && !os.IsNotExist(err) {
		return Report{}, err
	}
	if len(paths) == 0 {
		return codexFallback(expected, dir)
	}
	sort.Strings(paths)
	r := report("codex_sessions")
	r.Tokens = zero()
	r.nativeSessions = map[string][]nativeRequest{}
	r.nativeTotals = map[string]experimentcost.Usage{}
	sessions := map[string]codexSession{}
	for _, path := range paths {
		s, err := parseCodexSession(path, expected)
		if err != nil {
			return r, err
		}
		if s.ID == "" {
			s.ID = path
			s.Warnings = append(s.Warnings, "session ID is missing")
		}
		if previous, ok := sessions[s.ID]; ok {
			if !reflect.DeepEqual(previous.Requests, s.Requests) || !equal(previous.Tokens, s.Tokens) {
				r.warn("duplicate session " + s.ID + " has conflicting usage history")
				if len(s.Requests) > len(previous.Requests) {
					sessions[s.ID] = s
				}
			}
			continue
		}
		sessions[s.ID] = s
	}
	ids := make([]string, 0, len(sessions))
	for id := range sessions {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		s := sessions[id]
		r.nativeSessions[id] = []nativeRequest{}
		r.nativeTotals[id] = s.Tokens
		r.SessionCount++
		r.Tokens = sum(r.Tokens, s.Tokens)
		for _, w := range s.Warnings {
			r.warn("session " + id + ": " + w)
		}
		for _, request := range s.Requests {
			r.addRequest(request.Model, request.Usage)
			r.nativeSessions[id] = append(r.nativeSessions[id], nativeRequest{Identity: request.Identity, Model: request.Model, Usage: request.Usage})
		}
	}
	if r.RequestCount == 0 {
		r.warn("native sessions contain no request usage")
	}
	r.finish()
	return r, nil
}
func parseCodexSession(path, expected string) (codexSession, error) {
	s := codexSession{Tokens: zero()}
	model := ""
	previous := zero()
	seen := false
	fallbackWarned := false
	var createdAt time.Time
	metaSeen := false
	err := jsonLines(path, func(e map[string]json.RawMessage) error {
		var p map[string]json.RawMessage
		if raw := e["payload"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &p); err != nil {
				return err
			}
		}
		switch text(e["type"]) {
		case "session_meta":
			if !metaSeen {
				s.ID = text(p["id"])
				createdAt, _ = time.Parse(time.RFC3339Nano, text(e["timestamp"]))
				metaSeen = true
			}
		case "turn_context":
			model = text(p["model"])
		case "event_msg":
			if text(p["type"]) != "token_count" || len(p["info"]) == 0 || string(p["info"]) == "null" {
				return nil
			}
			// Forks copy parent events recorded before the child was created.
			if recordedAt, err := time.Parse(time.RFC3339Nano, text(e["timestamp"])); err == nil && !createdAt.IsZero() && recordedAt.Before(createdAt) {
				return nil
			}
			var info struct {
				Total *codexTokens `json:"total_token_usage"`
				Last  *codexTokens `json:"last_token_usage"`
			}
			if err := json.Unmarshal(p["info"], &info); err != nil {
				return err
			}
			if info.Total == nil || info.Last == nil {
				s.Warnings = append(s.Warnings, "token_count lacks total or last usage")
				return nil
			}
			current, last := info.Total.usage(), info.Last.usage()
			if seen && equal(current, previous) {
				return nil
			}
			delta, valid := difference(current, previous)
			if !valid || !equal(delta, last) {
				s.Warnings = append(s.Warnings, "cumulative usage differs from the last request or regresses")
			}
			actual := model
			if actual == "" {
				actual = expected
				if !fallbackWarned {
					s.Warnings = append(s.Warnings, "request model is missing; using expected model")
					fallbackWarned = true
				}
			}
			identity, err := json.Marshal(e)
			if err != nil {
				return err
			}
			s.Requests = append(s.Requests, codexRequest{Identity: string(identity), Model: actual, Usage: last})
			// Keep observed request counts even if a cumulative counter regresses.
			s.Tokens = sum(s.Tokens, last)
			previous = current
			seen = true
		}
		return nil
	})
	return s, err
}
func codexFallback(expected, dir string) (Report, error) {
	r := report("codex_stdout")
	r.warn("native sessions are missing; stdout totals do not provide request or child-session coverage")
	path := stdoutPath(dir)
	var last *codexTokens
	err := jsonLines(path, func(e map[string]json.RawMessage) error {
		if text(e["type"]) == "turn.completed" && len(e["usage"]) > 0 {
			value, err := decode[codexTokens](e["usage"])
			if err != nil {
				return err
			}
			last = &value
		}
		return nil
	})
	if os.IsNotExist(err) {
		r.warn("stdout usage is missing")
		return r, nil
	}
	if err != nil {
		return r, fmt.Errorf("read stdout usage: %w", err)
	}
	if last != nil {
		r.Tokens = last.usage()
		r.Models = append(r.Models, ModelUsage{Model: expected, Requests: []experimentcost.Usage{}, Tokens: r.Tokens})
		r.SessionCount = 1
	} else {
		r.warn("stdout contains no completed-turn usage")
	}
	return r, nil
}
