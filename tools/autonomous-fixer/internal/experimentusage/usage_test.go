package experimentusage

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func write(t *testing.T, path, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
}
func nativeToken(i, c, o, r int64) map[string]any {
	return map[string]any{"input_tokens": i, "cached_input_tokens": c, "cache_write_input_tokens": 0, "output_tokens": o, "reasoning_output_tokens": r}
}
func line(v any) string { b, _ := json.Marshal(v); return string(b) + "\n" }
func meta(id string) string {
	return line(map[string]any{"type": "session_meta", "payload": map[string]any{"id": id}})
}
func model(m string) string {
	return line(map[string]any{"type": "turn_context", "payload": map[string]any{"model": m}})
}
func event(total, last map[string]any) string {
	return line(map[string]any{"type": "event_msg", "payload": map[string]any{"type": "token_count", "info": map[string]any{"total_token_usage": total, "last_token_usage": last}}})
}
func TestCodexSessionsAndModelChanges(t *testing.T) {
	dir := t.TempDir()
	root := filepath.Join(dir, "home/.codex/sessions")
	first := nativeToken(100, 40, 10, 3)
	second := nativeToken(150, 60, 17, 5)
	body := meta("main") + model("gpt-6.1-sol") + event(first, first) + event(first, first) + model("gpt-6-luna") + event(second, nativeToken(50, 20, 7, 2))
	write(t, filepath.Join(root, "a.jsonl"), body)
	write(t, filepath.Join(root, "duplicate.jsonl"), body)
	write(t, filepath.Join(root, "nested/child.jsonl"), meta("child")+model("gpt-6.1-terra")+event(nativeToken(20, 0, 4, 1), nativeToken(20, 0, 4, 1)))
	write(t, stdoutPath(dir), `{"type":"turn.completed","usage":{"input_tokens":99999}}`)
	r, err := Read("codex", "wrong", dir)
	if err != nil {
		t.Fatal(err)
	}
	if !r.Complete || r.RequestCount != 3 || r.SessionCount != 2 || len(r.Models) != 3 || *r.Tokens.Input != 170 || *r.Tokens.Output != 21 || *r.Tokens.Reasoning != 6 {
		t.Fatalf("report=%+v", r)
	}
}
func TestCodexForkKeepsNativeIdentityAndExcludesInheritedUsage(t *testing.T) {
	dir := t.TempDir()
	root := filepath.Join(dir, "home/.codex/sessions")
	stamp := func(body, timestamp string) string {
		var e map[string]any
		if err := json.Unmarshal([]byte(body), &e); err != nil {
			t.Fatal(err)
		}
		e["timestamp"] = timestamp
		return line(e)
	}
	parentUsage := nativeToken(100, 40, 10, 3)
	childUsage := nativeToken(20, 5, 4, 1)
	nextUsage := nativeToken(50, 15, 10, 3)
	parent := stamp(meta("parent"), "2026-11-09T17:00:00Z") + model("gpt-6.1-sol") + stamp(event(parentUsage, parentUsage), "2026-11-09T17:01:00Z")
	child := stamp(meta("child"), "2026-11-09T17:02:00Z") + parent + model("gpt-6-luna") + stamp(event(childUsage, childUsage), "2026-11-09T17:03:00Z") + stamp(event(childUsage, childUsage), "2026-11-09T17:03:01Z") + model("gpt-6.1-terra") + stamp(event(nextUsage, nativeToken(30, 10, 6, 2)), "2026-11-09T17:04:00Z")
	write(t, filepath.Join(root, "parent.jsonl"), parent)
	write(t, filepath.Join(root, "child.jsonl"), child)
	write(t, filepath.Join(root, "duplicate-child.jsonl"), child)
	r, err := Read("codex", "wrong", dir)
	if err != nil {
		t.Fatal(err)
	}
	if !r.Complete || r.SessionCount != 2 || r.RequestCount != 3 || *r.Tokens.Input != 150 || *r.Tokens.Output != 20 || *r.Tokens.CachedInput != 55 || *r.Tokens.Reasoning != 6 {
		t.Fatalf("report=%+v", r)
	}
	want := map[string]int64{"gpt-6.1-sol": 100, "gpt-6-luna": 20, "gpt-6.1-terra": 30}
	if len(r.Models) != len(want) {
		t.Fatalf("models=%+v", r.Models)
	}
	for _, m := range r.Models {
		input, ok := want[m.Model]
		if !ok || len(m.Requests) != 1 || *m.Tokens.Input != input {
			t.Fatalf("model=%+v", m)
		}
	}
}

func TestCodexGapRegressionAndDuplicateConflict(t *testing.T) {
	dir := t.TempDir()
	root := filepath.Join(dir, "home/.codex/sessions")
	a := nativeToken(10, 0, 2, 0)
	write(t, filepath.Join(root, "a.jsonl"), meta("same")+model("gpt-6.1-sol")+event(a, a))
	write(t, filepath.Join(root, "b.jsonl"), meta("same")+model("gpt-6.1-sol")+event(a, a)+event(nativeToken(30, 0, 4, 0), a)+event(a, a))
	r, err := Read("codex", "fallback", dir)
	if err != nil {
		t.Fatal(err)
	}
	if r.Complete || r.SessionCount != 1 || r.RequestCount != 3 || *r.Tokens.Input != 30 || len(r.Warnings) < 2 {
		t.Fatalf("report=%+v", r)
	}
}
func TestMissingAndCodexFallback(t *testing.T) {
	dir := t.TempDir()
	r, err := Read("codex", "gpt-6.1-sol", dir)
	if err != nil || r.Complete || r.Tokens.Input != nil {
		t.Fatalf("report=%+v err=%v", r, err)
	}
	write(t, stdoutPath(dir), `{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":80,"output_tokens":12,"reasoning_output_tokens":5}}`)
	r, err = Read("codex", "gpt-6.1-sol", dir)
	if err != nil || r.Complete || *r.Tokens.Input != 100 || r.Tokens.CacheWrite != nil || len(r.Models[0].Requests) != 0 {
		t.Fatalf("report=%+v err=%v", r, err)
	}
}
func TestOpenCodeNativeTotals(t *testing.T) {
	dir := t.TempDir()
	db := filepath.Join(dir, "home/data/opencode/opencode.db")
	if err := os.MkdirAll(filepath.Dir(db), 0700); err != nil {
		t.Fatal(err)
	}
	// editorconfig-checker-disable: preserve Python indentation.
	script := `import sqlite3, json, sys
c=sqlite3.connect(sys.argv[1])
c.executescript('CREATE TABLE session_v2(id TEXT,model TEXT,tokens_input INTEGER,tokens_output INTEGER,tokens_reasoning INTEGER,tokens_cache_read INTEGER,tokens_cache_write INTEGER);CREATE TABLE session_message(id INTEGER PRIMARY KEY,session_id TEXT,type TEXT,data TEXT);')
c.execute('INSERT INTO session_v2 VALUES (?,?,?,?,?,?,?)',('session',json.dumps({'id':'beam-free','providerID':'opencode'}),100,20,10,200,3))
for kind,model,tokens in [('assistant',{'providerID':'opencode','id':'beam-free'},{'input':70,'output':12,'reasoning':7,'cache':{'read':150,'write':2}}),('compaction',{'providerID':'opencode','modelID':'other'},{'input':20,'output':5,'reasoning':2,'cache':{'read':40,'write':1}})]:
 c.execute('INSERT INTO session_message(session_id,type,data) VALUES (?,?,?)',('session',kind,json.dumps({'model':model,'tokens':tokens,'text':'must never be emitted'})))
c.commit()`
	// editorconfig-checker-enable
	if out, err := exec.Command("python3", "-c", script, db).CombinedOutput(); err != nil {
		t.Fatalf("fixture: %v %s", err, out)
	}
	r, err := Read("opencode", "wrong", dir)
	if err != nil {
		t.Fatal(err)
	}
	if r.Complete || r.RequestCount != 2 || r.SessionCount != 1 || len(r.Models) != 2 || *r.Tokens.Input != 303 || *r.Tokens.Output != 30 || *r.Tokens.CachedInput != 200 || r.UnattributedUsage == nil || *r.UnattributedUsage.Input != 20 || *r.UnattributedUsage.Output != 4 {
		t.Fatalf("report=%+v", r)
	}
}
func TestOpenCodeFallbackDeduplicatesIdentifiersOnly(t *testing.T) {
	dir := t.TempDir()
	tokens := map[string]any{"input": 10, "output": 2, "reasoning": 3, "cache": map[string]any{"read": 20, "write": 1}}
	step := func(id string) string {
		return line(map[string]any{"type": "step_finish", "sessionID": "s", "part": map[string]any{"id": id, "messageID": id, "tokens": tokens}})
	}
	write(t, stdoutPath(dir), step("a")+step("a")+step("b")+step("")+step(""))
	r, err := Read("opencode", "opencode/beam-free", dir)
	if err != nil {
		t.Fatal(err)
	}
	if r.Complete || r.RequestCount != 4 || *r.Tokens.Input != 124 || *r.Tokens.Output != 20 || *r.Tokens.Reasoning != 12 {
		t.Fatalf("report=%+v", r)
	}
}

func TestOpenCodeModelShapes(t *testing.T) {
	for _, raw := range []string{`{"providerID":"opencode","id":"beam-free"}`, `{"providerID":"opencode","modelID":"beam-free"}`, `"{\"providerID\":\"opencode\",\"id\":\"beam-free\"}"`, `"opencode/beam-free"`} {
		if got := openCodeModel(json.RawMessage(raw)); got != "opencode/beam-free" {
			t.Fatalf("model %s = %q", raw, got)
		}
	}
}
func TestCodexMissingFieldsRemainUnknown(t *testing.T) {
	dir := t.TempDir()
	tokens := nativeToken(10, 0, 2, 0)
	delete(tokens, "cache_write_input_tokens")
	write(t, filepath.Join(dir, "home/.codex/sessions/a.jsonl"), meta("s")+event(tokens, tokens))
	r, err := Read("codex", "expected", dir)
	if err != nil {
		t.Fatal(err)
	}
	if r.Complete || r.Tokens.CacheWrite != nil || r.Models[0].Model != "expected" || *r.Tokens.Input != 10 {
		t.Fatalf("report=%+v", r)
	}
}
