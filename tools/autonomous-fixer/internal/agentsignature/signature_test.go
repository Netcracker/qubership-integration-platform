package agentsignature

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/jev"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

func pointer[T any](value T) *T { return &value }

func TestSignatureFormattingAndTrailingReplacement(t *testing.T) {
	s := Signature{Model: "gpt-6.1", Effort: "high", Input: pointer(int64(12000)), Cached: pointer(int64(40500)), Output: pointer(int64(3100)), USD: pointer(.08311)}
	want := "<sub>🤖 gpt-6.1 high in=12k cached=40.5k out=3.1k usd=0.083</sub>"
	if s.Line() != want || (Signature{Model: "provider/free", USD: pointer(0.0)}).Line() != "<sub>🤖 provider/free usd=0</sub>" {
		t.Fatal(s.Line())
	}
	body := "An example.\n\n```markdown\n<sub>🤖 quoted-model</sub>\n```\n\nDescription."
	draft := body + "\n\n<sub>🤖 invented-model</sub>\n<sub>🤖 duplicate</sub>\n"
	if got := s.Append(draft); got != body+"\n\n"+want || s.Append(got) != got {
		t.Fatal(got)
	}
	if (Signature{Model: "unsafe</sub>"}).Line() != "" || (Signature{USD: pointer(0.0)}).Validate() == nil {
		t.Fatal("invalid signature was accepted")
	}
}

func TestHostedUsesObservedModelsAndPartialProviderCounters(t *testing.T) {
	dir := t.TempDir()
	put(t, dir, "config.json", runConfig{Hosted: true, Profile: experimentruntime.Profile{Runtime: "opencode", Model: "openrouter/openai/configured", Effort: "high"}})
	a := openrouterusage.Generation{IDSHA256: "first", RequestedModel: "openai/configured", ServedModel: "openai/observed", InputTokens: pointer(int64(1500)), CachedTokens: pointer(int64(1000)), OutputTokens: pointer(int64(80)), CostUSD: pointer(.00422)}
	b := openrouterusage.Generation{IDSHA256: "second", RequestedModel: "openai/reviewer", ServedModel: "openai/reviewer", InputTokens: pointer(int64(700)), OutputTokens: pointer(int64(20))}
	put(t, dir, openrouterusage.Filename, openrouterusage.Report{Attempts: 3, Generations: []openrouterusage.Generation{a, a, b}})
	value, err := ReadRun(dir)
	if err != nil || value.Line() != "<sub>🤖 openai/observed out=100</sub>" {
		t.Fatal(value.Line(), err)
	}
	b.CachedTokens, b.CostUSD = pointer(int64(0)), pointer(.0000124)
	put(t, dir, openrouterusage.Filename, openrouterusage.Report{Attempts: 3, DuplicateResponses: 1, Generations: []openrouterusage.Generation{a, b}})
	put(t, dir, "step-0001-author/"+IdentityFilename, Identity{Model: "openrouter/openai/configured", Effort: "high"})
	value, err = ReadRun(dir)
	if err != nil || value.Line() != "<sub>🤖 openai/observed high in=1.2k cached=1k out=100 usd=0.0042</sub>" {
		t.Fatal(value.Line(), err)
	}
	put(t, dir, openrouterusage.Filename, openrouterusage.Report{})
	value, err = ReadRun(dir)
	if err != nil || value.Line() != "<sub>🤖 openrouter/openai/configured high</sub>" {
		t.Fatal(value.Line(), err)
	}
}

func TestJevMissingModelDoesNotHidePartialUsage(t *testing.T) {
	response := jev.Response{ID: "generation", Model: "typesafe/jev-1.14-20261014", Usage: &jev.Usage{InputTokens: pointer(int64(1000)), OutputTokens: pointer(int64(4)), Cost: pointer(.001)}}
	if s := FromJev(response, response); s.Line() != "<sub>🤖 typesafe/jev-1.14-20261014 out=4 usd=0.001</sub>" {
		t.Fatal(s)
	}
	missing := jev.Response{ID: "unattributed", Usage: response.Usage}
	if s := FromJev(response, missing); s.Line() != "<sub>🤖 typesafe/jev-1.14-20261014</sub>" {
		t.Fatal(s)
	}
}

func TestHostedDocumentationIncludesJevOnce(t *testing.T) {
	dir := t.TempDir()
	put(t, dir, "config.json", runConfig{Hosted: true, TaskKind: "documentation", Profile: experimentruntime.Profile{Runtime: "opencode", Model: "openrouter/openai/author", Effort: "high"}})
	put(t, dir, "step-0001-docs-write/"+IdentityFilename, Identity{Model: "openrouter/openai/author", Effort: "high"})
	g := openrouterusage.Generation{
		IDSHA256: "first", RequestedModel: "openai/author", ServedModel: "openai/observed",
		InputTokens: pointer(int64(1500)), CachedTokens: pointer(int64(1000)),
		OutputTokens: pointer(int64(85)), CostUSD: pointer(.004),
	}
	put(t, dir, openrouterusage.Filename, openrouterusage.Report{Attempts: 2, DuplicateResponses: 1, Generations: []openrouterusage.Generation{g}})
	packet := doctriage.Packet{ID: "test", Scope: "test", Repository: "org/repo", Issue: json.RawMessage(`{"title":"Fixture"}`)}
	report, err := doctriage.Evaluate(context.Background(), packet, jev.DefaultModel, archivedJev{})
	if err != nil {
		t.Fatal(err)
	}
	put(t, dir, "evidence/documentation-routing.json", map[string]any{"packet": packet, "report": report})
	s, err := ReadRun(dir)
	if err != nil || s.Line() != "<sub>🤖 openai/observed high out=300 usd=0.0042</sub>" {
		t.Fatal(s.Line(), err)
	}
}

type archivedJev struct{}

func (archivedJev) Classify(_ context.Context, request jev.Request) (jev.Response, error) {
	r := jev.Response{ID: "legacy", Model: "typesafe/jev-1.14-20261014", Answers: make(map[string]jev.Answer),
		Usage: &jev.Usage{InputTokens: pointer(int64(4993)), OutputTokens: pointer(int64(215)), Cost: pointer(.000209706)}}
	for key, question := range request.Questions {
		var choices []string
		p := make(map[string]float64)
		for choice := range question.Criteria {
			choices = append(choices, choice)
			p[choice] = 0
		}
		sort.Strings(choices)
		p[choices[0]] = 1
		r.Answers[key] = jev.Answer{Type: "choice", Choice: choices[0], Probabilities: p}
	}
	return r, nil
}

func TestReadRunHandlesLegacyJevResponseWithoutAttemptFlag(t *testing.T) {
	dir := t.TempDir()
	packet := doctriage.Packet{ID: "test", Scope: "test", Repository: "org/repo", Issue: json.RawMessage(`{"title":"Fixture"}`)}
	report, err := doctriage.Evaluate(context.Background(), packet, jev.DefaultModel, archivedJev{})
	if err != nil {
		t.Fatal(err)
	}
	report.RequestAttempted = false
	put(t, dir, "config.json", runConfig{})
	put(t, dir, "evidence/documentation-routing.json", map[string]any{"packet": packet, "report": report})
	result, err := ReadRun(dir)
	if err != nil || result.Line() != "<sub>🤖 typesafe/jev-1.14-20261014 out=215 usd=0.00021</sub>" {
		t.Fatal(result.Line(), err)
	}
}

func TestCodexContinuationCountsAuthorAndChildOnceWithoutEstimates(t *testing.T) {
	dir := t.TempDir()
	profile := experimentruntime.Profile{Runtime: "codex", Model: "declared-default", Effort: "low"}
	put(t, dir, "config.json", runConfig{Profile: profile})
	first, second := "step-0001-author", "step-0002-author"
	one := codexRecords("main", "runtime-model", "high", "1000", "400", "300")
	child := codexRecords("child", "runtime-reviewer", "medium", "600", "100", "200")
	for _, step := range []string{first, second} {
		put(t, dir, step+"/profile.json", profile)
		write(t, dir, step+"/home/.codex/sessions/child.jsonl", child)
	}
	write(t, dir, first+"/stdout.jsonl", `{"type":"thread.started","thread_id":"main"}`+"\n")
	put(t, dir, second+"/session.json", map[string]any{"session_id": "main", "continuation": true, "previous_archive": filepath.Join(dir, first)})
	write(t, dir, first+"/home/.codex/sessions/main.jsonl", one)
	last := codexToken("500", "200", "20")
	total := codexToken("1500", "600", "320")
	write(t, dir, second+"/home/.codex/sessions/main.jsonl", one+codexEvent(last, total))
	s, err := ReadRun(dir)
	if err != nil || s.Line() != "<sub>🤖 runtime-model high in=1.4k cached=700 out=520</sub>" {
		t.Fatal(s.Line(), err)
	}
}

func TestOpenCodeReadsMainObservedVariantAndCountsAllSessions(t *testing.T) {
	dir := t.TempDir()
	put(t, dir, "config.json", runConfig{Profile: experimentruntime.Profile{Runtime: "opencode"}})
	path := filepath.Join(dir, "step-0001-author", "home", "data", "opencode", "opencode.db")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	script := `import sqlite3, sys, json
db=sqlite3.connect(sys.argv[1])
db.executescript("CREATE TABLE session_v2(id TEXT,parent_id TEXT,tokens_input INTEGER,tokens_output INTEGER,tokens_cache_read INTEGER,tokens_cache_write INTEGER,tokens_reasoning INTEGER,cost REAL); CREATE TABLE session_message(id INTEGER,session_id TEXT,type TEXT,data TEXT);")
db.executemany("INSERT INTO session_v2 VALUES (?,?,?,?,?,?,?,?)",[("author",None,100,14,30,50,8,0.005),("child","author",40,11,12,10,3,0.001)])
db.executemany("INSERT INTO session_message VALUES (?,?,?,?)",[(1,"author","assistant",json.dumps({"model":{"providerID":"provider","id":"observed","variant":"high"}})),(2,"child","assistant",json.dumps({"model":{"providerID":"provider","id":"review","variant":"medium"}}))])
db.commit()`
	if out, err := exec.Command("python3", "-I", "-c", script, path).CombinedOutput(); err != nil {
		t.Fatal(string(out), err)
	}
	s, err := ReadRun(dir)
	if err != nil || s.Line() != "<sub>🤖 provider/observed high in=200 cached=42 out=36 usd=0.006</sub>" {
		t.Fatal(s.Line(), err)
	}
}

func TestHostedRejectsSymlinkedNativeDatabase(t *testing.T) {
	dir := t.TempDir()
	put(t, dir, "config.json", runConfig{Hosted: true, Profile: experimentruntime.Profile{Runtime: "opencode", Model: "openrouter/openai/configured"}})
	path := filepath.Join(dir, "step-0001-author", "home", "data", "opencode", "opencode.db")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(t.TempDir(), "credential-file"), path); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRun(dir); err == nil {
		t.Fatal("linked database was accepted")
	}
}

func codexRecords(id, model, effort, input, cached, output string) string {
	start := `{"type":"session_meta","payload":{"id":"` + id + `"}}` + "\n"
	turn := `{"type":"turn_context","payload":{"model":"` + model + `","effort":"` + effort + `"}}` + "\n"
	usage := codexToken(input, cached, output)
	return start + turn + codexEvent(usage, usage)
}

func codexToken(input, cached, output string) string {
	return `{"input_tokens":` + input + `,"cached_input_tokens":` + cached + `,"output_tokens":` + output + `,"cache_write_input_tokens":0,"reasoning_output_tokens":0}`
}

func codexEvent(last, total string) string {
	return `{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":` + last + `,"total_token_usage":` + total + `}}}` + "\n"
}

func put(t *testing.T, dir, name string, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	write(t, dir, name, string(data))
}

func write(t *testing.T, dir, name, text string) {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
}
