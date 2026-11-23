package experimentusage

import (
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
)

func authorStep(t *testing.T, run string, number string, body string) string {
	t.Helper()
	dir := filepath.Join(run, "step-"+number+"-author")
	write(t, filepath.Join(dir, "home/.codex/sessions/author.jsonl"), body)
	return dir
}

func continueStep(t *testing.T, dir, prior, session string) {
	t.Helper()
	write(t, filepath.Join(dir, "session.json"), line(SessionLink{SessionID: session, Continuation: true, PreviousArchive: prior}))
}

func TestCodexContinuationCountsOnlyNewRequests(t *testing.T) {
	run := t.TempDir()
	first := nativeToken(150000, 20000, 100, 30)
	second := nativeToken(300000, 40000, 200, 60)
	last := nativeToken(280000, 100000, 300, 100)
	last["cache_write_input_tokens"] = int64(10000)
	total := nativeToken(580000, 140000, 500, 160)
	total["cache_write_input_tokens"] = int64(10000)
	prefix := meta("author") + model("gpt-6.1-sol") + event(first, first) + event(second, first)
	before := authorStep(t, run, "0001", prefix)
	after := authorStep(t, run, "0003", prefix+event(second, first)+event(total, last))
	continueStep(t, after, before, "author")
	a, err := Read("codex", "gpt-6.1-sol", before)
	if err != nil {
		t.Fatal(err)
	}
	b, err := Read("codex", "gpt-6.1-sol", after)
	if err != nil {
		t.Fatal(err)
	}
	if !a.Complete || !b.Complete || a.RequestCount != 2 || b.RequestCount != 1 || a.RequestCount+b.RequestCount != 3 || b.SessionCount != 1 || !equal(b.Tokens, codexTokens{Input: ptr(280000), Cached: ptr(100000), Write: ptr(10000), Output: ptr(300), Reasoning: ptr(100)}.usage()) {
		t.Fatalf("before=%+v after=%+v", a, b)
	}
	if b.Session == nil || b.Session.SessionID != "author" || b.Session.PreviousArchive != before {
		t.Fatalf("session provenance=%+v", b.Session)
	}
	costA := experimentcost.Calculate(a.Models[0].Model, a.Models[0].Requests)
	costB := experimentcost.Calculate(b.Models[0].Model, b.Models[0].Requests)
	all := experimentcost.Calculate("gpt-6.1-sol", append(append([]experimentcost.Usage{}, a.Models[0].Requests...), b.Models[0].Requests...))
	if costA.TotalUSD == nil || costB.TotalUSD == nil || all.TotalUSD == nil || costA.LongRequests != 0 || costB.LongRequests != 1 || math.Abs(*costB.TotalUSD-.7545) > 1e-9 || math.Abs(*costA.TotalUSD+*costB.TotalUSD-*all.TotalUSD) > 1e-9 {
		t.Fatalf("first=%+v next=%+v all=%+v", costA, costB, all)
	}
	unchanged, err := Read("codex", "gpt-6.1-sol", before)
	if err != nil || !unchanged.Complete || unchanged.RequestCount != 2 {
		t.Fatalf("previous archive changed: %+v %v", unchanged, err)
	}
}

func TestCodexContinuationRejectsUnknownBoundary(t *testing.T) {
	for _, test := range []string{"missing", "different-prefix", "shorter-history", "different-session", "missing-native", "outside-run", "symlink-outside-run", "future", "cycle"} {
		t.Run(test, func(t *testing.T) {
			run := t.TempDir()
			a := nativeToken(100, 40, 10, 3)
			b := nativeToken(200, 80, 20, 6)
			prefix := meta("author") + model("gpt-6.1-sol") + event(a, a)
			before := authorStep(t, run, "0001", prefix)
			after := authorStep(t, run, "0003", prefix+event(b, a))
			baseline := before
			switch test {
			case "missing":
				baseline = filepath.Join(run, "step-0002-author")
			case "different-prefix":
				write(t, filepath.Join(before, "home/.codex/sessions/author.jsonl"), meta("author")+model("gpt-6.1-sol")+event(nativeToken(99, 40, 10, 3), nativeToken(99, 40, 10, 3)))
			case "shorter-history":
				write(t, filepath.Join(before, "home/.codex/sessions/author.jsonl"), prefix+event(b, a)+event(nativeToken(300, 120, 30, 9), a))
			case "different-session":
				write(t, filepath.Join(before, "home/.codex/sessions/author.jsonl"), meta("other")+model("gpt-6.1-sol")+event(a, a))
			case "missing-native":
				if err := os.RemoveAll(filepath.Join(before, "home")); err != nil {
					t.Fatal(err)
				}
			case "outside-run":
				baseline = authorStep(t, t.TempDir(), "0001", prefix)
			case "symlink-outside-run":
				outside := authorStep(t, t.TempDir(), "0001", prefix)
				baseline = filepath.Join(run, "step-0002-author")
				if err := os.Symlink(outside, baseline); err != nil {
					t.Fatal(err)
				}
			case "future":
				baseline = authorStep(t, run, "0004", prefix)
			case "cycle":
				continueStep(t, before, after, "author")
			}
			continueStep(t, after, baseline, "author")
			r, err := Read("codex", "gpt-6.1-sol", after)
			if err != nil || r.Complete || r.Tokens.Input != nil || r.RequestCount != 0 || len(r.Models) != 0 || len(r.Warnings) == 0 {
				t.Fatalf("report=%+v err=%v", r, err)
			}
		})
	}
}

func TestContinuationKeepsKnownRequestsWhenCountersRegress(t *testing.T) {
	run := t.TempDir()
	a := nativeToken(100, 40, 10, 3)
	prefix := meta("author") + model("gpt-6.1-sol") + event(a, a)
	before := authorStep(t, run, "0001", prefix)
	after := authorStep(t, run, "0003", prefix+event(nativeToken(50, 20, 5, 1), a))
	continueStep(t, after, before, "author")
	r, err := Read("codex", "gpt-6.1-sol", after)
	if err != nil || r.Complete || r.RequestCount != 1 || *r.Models[0].Requests[0].Input != 100 || !strings.Contains(strings.Join(r.Warnings, " "), "regresses") {
		t.Fatalf("report=%+v err=%v", r, err)
	}
	known := experimentcost.Calculate(r.Models[0].Model, r.Models[0].Requests)
	if known.TotalUSD == nil || *known.TotalUSD <= 0 {
		t.Fatalf("known request cost lost: %+v", known)
	}
}

func TestFreshReviewerIgnoresAuthorLink(t *testing.T) {
	run := t.TempDir()
	a := nativeToken(100, 40, 10, 3)
	dir := filepath.Join(run, "step-0002-review")
	write(t, filepath.Join(dir, "home/.codex/sessions/reviewer.jsonl"), meta("reviewer")+model("gpt-6.1-terra")+event(a, a))
	write(t, filepath.Join(dir, "session.json"), `{"continuation":false,"previous_archive":123,"session_id":[]}`)
	r, err := Read("codex", "gpt-6.1-terra", dir)
	if err != nil || !r.Complete || r.Session != nil || r.RequestCount != 1 || *r.Tokens.Input != 100 {
		t.Fatalf("report=%+v err=%v", r, err)
	}
}

func TestCodexContinuationWithoutNewUsageIsIncomplete(t *testing.T) {
	run := t.TempDir()
	a := nativeToken(100, 40, 10, 3)
	prefix := meta("author") + model("gpt-6.1-sol") + event(a, a)
	before := authorStep(t, run, "0001", prefix)
	after := authorStep(t, run, "0003", prefix+event(a, a))
	continueStep(t, after, before, "author")
	r, err := Read("codex", "gpt-6.1-sol", after)
	if err != nil || r.Complete || r.RequestCount != 0 || len(r.Warnings) == 0 {
		t.Fatalf("report=%+v err=%v", r, err)
	}
}

func TestCodexContinuationChainUsesFullPriorSnapshot(t *testing.T) {
	run := t.TempDir()
	a := nativeToken(100, 40, 10, 3)
	prefix := meta("author") + model("gpt-6.1-sol") + event(a, a)
	first := authorStep(t, run, "0001", prefix)
	secondBody := prefix + event(nativeToken(200, 80, 20, 6), a)
	second := authorStep(t, run, "0003", secondBody)
	third := authorStep(t, run, "0005", secondBody+event(nativeToken(300, 120, 30, 9), a))
	continueStep(t, second, first, "author")
	continueStep(t, third, second, "author")
	requests := 0
	for _, dir := range []string{first, second, third} {
		r, err := Read("codex", "gpt-6.1-sol", dir)
		if err != nil || !r.Complete || r.RequestCount != 1 || *r.Tokens.Input != 100 {
			t.Fatalf("step %s: report=%+v err=%v", dir, r, err)
		}
		requests += r.RequestCount
	}
	if requests != 3 {
		t.Fatalf("request count=%d", requests)
	}
}

func TestContinuationPreservesUnknownCountersAndExpectedModelMismatch(t *testing.T) {
	run := t.TempDir()
	a := nativeToken(100, 40, 10, 3)
	prefix := meta("author") + model("gpt-6.1-sol") + event(a, a)
	before := authorStep(t, run, "0001", prefix)
	last := nativeToken(100, 40, 10, 3)
	delete(last, "cache_write_input_tokens")
	after := authorStep(t, run, "0003", prefix+model("gpt-6.1-terra")+event(nativeToken(200, 80, 20, 6), last))
	continueStep(t, after, before, "author")
	r, err := Read("codex", "gpt-6.1-sol", after)
	if err != nil || r.Complete || r.RequestCount != 1 || r.Tokens.CacheWrite != nil || r.Models[0].Model != "gpt-6.1-terra" || *r.Tokens.Input != 100 || *r.Tokens.CachedInput != 40 || *r.Tokens.Reasoning != 3 || !strings.Contains(strings.Join(r.Warnings, " "), "expected model") {
		t.Fatalf("report=%+v err=%v", r, err)
	}
}

func openCodeArchive(t *testing.T, dir string, count int) {
	t.Helper()
	db := filepath.Join(dir, "home/data/opencode/opencode.db")
	if err := os.MkdirAll(filepath.Dir(db), 0700); err != nil {
		t.Fatal(err)
	}
	// editorconfig-checker-disable: preserve Python indentation.
	script := `import sqlite3, json, sys
c=sqlite3.connect(sys.argv[1])
c.executescript('CREATE TABLE session_v2(id TEXT,model TEXT,tokens_input INTEGER,tokens_output INTEGER,tokens_reasoning INTEGER,tokens_cache_read INTEGER,tokens_cache_write INTEGER);CREATE TABLE session_message(id INTEGER PRIMARY KEY,session_id TEXT,type TEXT,data TEXT);')
n=int(sys.argv[2])
c.execute('INSERT INTO session_v2 VALUES (?,?,?,?,?,?,?)',('author','opencode/beam-free',100*n,20*n,10*n,200*n,3*n))
for i in range(n):
 c.execute('INSERT INTO session_message(id,session_id,type,data) VALUES (?,?,?,?)',(i+1,'author','assistant',json.dumps({'model':'opencode/beam-free','tokens':{'input':100,'output':20,'reasoning':10,'cache':{'read':200,'write':3}}})))
c.commit()`
	// editorconfig-checker-enable
	if out, err := exec.Command("python3", "-c", script, db, strconv.Itoa(count)).CombinedOutput(); err != nil {
		t.Fatalf("fixture: %v %s", err, out)
	}
}

func TestOpenCodeContinuationUsesNativeMessagePrefix(t *testing.T) {
	run := t.TempDir()
	before, after := filepath.Join(run, "step-0001-author"), filepath.Join(run, "step-0003-author")
	openCodeArchive(t, before, 2)
	openCodeArchive(t, after, 3)
	continueStep(t, after, before, "author")
	a, err := Read("opencode", "opencode/beam-free", before)
	if err != nil {
		t.Fatal(err)
	}
	b, err := Read("opencode", "opencode/beam-free", after)
	if err != nil || !a.Complete || !b.Complete || a.RequestCount != 2 || b.RequestCount != 1 || *b.Tokens.Input != 303 || *b.Tokens.CachedInput != 200 || *b.Tokens.CacheWrite != 3 || *b.Tokens.Output != 30 || *b.Tokens.Reasoning != 10 {
		t.Fatalf("before=%+v after=%+v err=%v", a, b, err)
	}
	db := filepath.Join(before, "home/data/opencode/opencode.db")
	if out, err := exec.Command("python3", "-c", "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('UPDATE session_message SET id=99 WHERE id=1'); c.commit()", db).CombinedOutput(); err != nil {
		t.Fatalf("tamper: %v %s", err, out)
	}
	b, err = Read("opencode", "opencode/beam-free", after)
	if err != nil || b.Complete || b.RequestCount != 0 || b.Tokens.Input != nil {
		t.Fatalf("tampered prefix report=%+v err=%v", b, err)
	}
}
