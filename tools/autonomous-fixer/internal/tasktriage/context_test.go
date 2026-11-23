package tasktriage

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestReviewDiffIncludesBothResolversBeforeLargeTests(t *testing.T) {
	files := map[string]string{
		"integration-build-pipeline/src/main/Resolver.java":     "diff --git a/Resolver.java b/Resolver.java\n@@ -1 +1 @@\n" + strings.Repeat("+java resolver body\n", 5000) + "\n@@ -8000 +8000 @@\n+java final hunk\n",
		"integration-build-pipeline/src/test/ResolverTest.java": strings.Repeat("+test fixture\n", 10000),
		"vscode-extension/src/web/Resolver.ts":                  "diff --git a/Resolver.ts b/Resolver.ts\n@@ -1 +1 @@\n+typescript resolver body\n",
	}
	diff, coverage := ReviewDiff(files, 48000)
	if len(diff) > 48000 || !strings.Contains(diff, "typescript resolver body") || !strings.Contains(diff, "java final hunk") {
		t.Fatal("production module or late hunk omitted", len(diff))
	}
	if len(coverage) != 3 {
		t.Fatal(coverage)
	}
	for _, c := range coverage {
		if c.Path == "integration-build-pipeline/src/test/ResolverTest.java" && c.IncludedBytes != 0 {
			t.Fatal("test displaced production", coverage)
		}
		if c.IncludedBytes > c.TotalBytes {
			t.Fatal(c)
		}
	}
}
func TestReviewDiffPreservesSmallFullDiffsAndRecordsOmissions(t *testing.T) {
	files := map[string]string{"ui/src/A.ts": "@@ -1 +1 @@\n+small\n", "ui/tests/A.test.ts": "test"}
	diff, coverage := ReviewDiff(files, 1000)
	for _, c := range coverage {
		if c.IncludedBytes != c.TotalBytes {
			t.Fatal(coverage)
		}
	}
	if !strings.Contains(diff, "+small") || !strings.Contains(diff, "test") {
		t.Fatal(diff)
	}
	diff, coverage = ReviewDiff(files, 1)
	if diff != "" || coverage[0].IncludedBytes != 0 {
		t.Fatal(diff, coverage)
	}
}
func TestKnownUIConsumerNeedsEvidenceAndAliasesAvoidDuplicateCoverage(t *testing.T) {
	hints := KnownConsumers([]string{"ui/src/pages/ChainPage.tsx"})
	if len(hints) != 1 || hints[0].Consumer != "vscode-webview" {
		t.Fatal(hints)
	}
	if len(KnownConsumers([]string{"help/docs/ui.md", "ui/tests/A.test.ts"})) != 0 {
		t.Fatal("unrelated paths inferred shared consumer")
	}
	p := Packet{TaskID: "878", Phase: "contract", Task: "Add a shared navigation header", ConsumerHints: hints, Contract: json.RawMessage(`{"scenarios":[{"consumer":"browser"}]}`)}
	q, e := Build(p)
	if e != nil || len(q.Questions) != 1 {
		t.Fatal(q, e)
	}
	f := &fake{}
	p.Contract = json.RawMessage(`{"scenarios":[{"consumer":"extension"}]}`)
	r, e := Evaluate(context.Background(), p, f)
	if e != nil || f.calls != 0 || r.RequestAttempted || Validate(r) != nil {
		t.Fatal(r, e)
	}
}
func TestMandatoryReviewQuestionsAndSelectionsAreExcluded(t *testing.T) {
	p := Packet{TaskID: "t", Phase: "review", Task: "Fix shared behavior", CandidateID: "c", Diff: "diff", MandatoryReviews: []string{"correctness", "compatibility", "data", "security", "concurrency"}}
	f := &fake{}
	r, e := Evaluate(context.Background(), p, f)
	if e != nil || f.calls != 0 || len(r.Request.Questions) != 0 || Validate(r) != nil {
		t.Fatal(r, e)
	}
}
func TestQuestionActionIsIndependentOfFailureCause(t *testing.T) {
	p := Packet{TaskID: "t", Phase: "failure", Task: "Fix schema import", Failure: &Failure{Stage: "contract", Message: "Two local revisions differ?", Question: &Question{Text: "Which revision is the baseline?", Needed: "Compare the local source trees."}}}
	q, e := Build(p)
	if e != nil {
		t.Fatal(e)
	}
	r := response(q)
	r.Answers["failure"] = answer(q.Questions["failure"], "missing_information", .95)
	r.Answers["question_action"] = answer(q.Questions["question_action"], "observable_probe", .91)
	s := Select(p, r)
	if s.FailureKind != "missing_information" || s.QuestionAction != "observable_probe" {
		t.Fatal(s)
	}
	r.Answers["question_action"] = answer(q.Questions["question_action"], "observable_probe", .65)
	s = Select(p, r)
	if s.QuestionAction != "uncertain" || s.QuestionActionCandidate != "observable_probe" {
		t.Fatal(s)
	}
}
func TestKnownProtocolErrorDoesNotRequestQuestionInference(t *testing.T) {
	p := Packet{TaskID: "t", Phase: "failure", Task: "Fix", Failure: &Failure{Kind: "agent_protocol", Message: "Invalid evidence path", Question: &Question{Text: "Retry?", Needed: "correct JSON"}}}
	f := &fake{}
	r, e := Evaluate(context.Background(), p, f)
	if e != nil || f.calls != 0 || r.Selection.QuestionAction != "" || Validate(r) != nil {
		t.Fatal(r, e)
	}
}

func TestTestingProductPagesRemainProductionConsumers(t *testing.T) {
	path := "ui/src/pages/testing/TestCases.tsx"
	if !productionPath(path) || len(KnownConsumers([]string{path})) != 1 {
		t.Fatal("testing product page treated as test support")
	}
}
