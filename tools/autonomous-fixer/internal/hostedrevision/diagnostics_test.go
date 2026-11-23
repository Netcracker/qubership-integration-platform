package hostedrevision

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"slices"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentci"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
)

func TestAutomaticRevisionResolvesLegacyFailureOnce(t *testing.T) {
	f, request := automaticFixture(t)
	f.change(func() { f.ciConclusion = "failure" })
	ctx := t.Context()
	legacyKey := fmt.Sprintf("%x", sha256.Sum256([]byte("1234\x0083\x00Compile\x00TypeScript reported TS1234 at ui/a.ts.")))
	request.Reviews = nil
	request.Failures = []autorequest.Failure{{Key: legacyKey, Name: "UI build / Compile", Excerpt: "TypeScript reported TS1234 at ui/a.ts:20:30."}}
	first := openFixture(t, f.dispatch(t, request, 123, 13))
	a := accepted(t, first)
	verified, _, err := first.VerifyWork(ctx, a)
	if err != nil || !slices.Equal(verified.FailureKeys, []string{legacyKey}) || len(verified.Feedback) != 1 {
		t.Fatalf("legacy assignment = %+v, %v", verified, err)
	}
	f.change(func() {
		f.comments = append(f.comments, f.commands[13])
		run := f.runs[123]
		run.Status = "completed"
		f.runs[123] = run
	})
	snapshot, err := agentci.Read(ctx, f.cfg.Client, request.Head, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := agentci.PlanRepair(ctx, f.cfg.Client, snapshot)
	if err != nil || len(plan.Failures) != 1 || plan.Failures[0].Key == legacyKey {
		t.Fatalf("current plan = %+v, %v", plan, err)
	}
	request.Failures = plan.Failures
	second := openFixture(t, f.dispatch(t, request, 124, 14))
	if ok, err := second.OpenRevision(ctx); ok || !errors.Is(err, ErrUnauthorized) || f.writes != 1 {
		t.Fatalf("duplicate failure admission = %t, %v, writes = %d", ok, err, f.writes)
	}
}

func TestAutomaticRevisionKeepsLegacyJestReceiptAfterCaseDetection(t *testing.T) {
	for _, aggregate := range []bool{false, true} {
		t.Run(fmt.Sprintf("aggregate=%t", aggregate), func(t *testing.T) {
			f, request := automaticFixture(t)
			const suite = "FAIL ui/src/Button.test.tsx"
			const suiteExcerpt = "Jest reported a failure in ui/src/Button.test.tsx."
			excerpt := suiteExcerpt
			signature := suiteExcerpt
			annotation := suite
			if aggregate {
				excerpt += "\nTypeScript reported TS1234 at ui/a.ts:20:30."
				signature += "\nTypeScript reported TS1234 at ui/a.ts."
				annotation = "ui/a.ts(20,30): error TS1234: invalid argument\n" + annotation
			}
			key := fmt.Sprintf("%x", sha256.Sum256([]byte("1234\x0083\x00Compile\x00"+signature)))
			request.Reviews = nil
			request.Failures = []autorequest.Failure{{Key: key, Name: "UI build / Compile", Excerpt: excerpt}}
			f.change(func() {
				f.ciConclusion = "failure"
				f.ciAnnotation = annotation
			})
			first := openFixture(t, f.dispatch(t, request, 123, 13))
			a := accepted(t, first)
			original, _, err := first.VerifyWork(t.Context(), a)
			if err != nil || !slices.Equal(original.FailureKeys, []string{key}) || len(original.Feedback) != 1 {
				t.Fatalf("legacy assignment = %+v, %v", original, err)
			}

			f.change(func() { f.ciAnnotation += "\n  ● Button › opens\n  ● Button › closes" })
			verified, _, err := first.VerifyWork(t.Context(), a)
			if err != nil || !slices.Equal(verified.Feedback, original.Feedback) || !slices.Equal(verified.FailureKeys, original.FailureKeys) {
				t.Fatalf("verified legacy assignment = %+v, %v", verified, err)
			}

			f.change(func() {
				f.comments = append(f.comments, f.commands[13])
				run := f.runs[123]
				run.Status = "completed"
				f.runs[123] = run
			})
			snapshot, err := agentci.Read(t.Context(), f.cfg.Client, request.Head, "", nil)
			if err != nil {
				t.Fatal(err)
			}
			plan, err := agentci.PlanRepair(t.Context(), f.cfg.Client, snapshot)
			want := 2
			if aggregate {
				want++
			}
			if err != nil || len(plan.Failures) != want {
				t.Fatalf("current plan = %+v, %v", plan, err)
			}
			request.Failures = plan.Failures
			second := openFixture(t, f.dispatch(t, request, 124, 14))
			if ok, err := second.OpenRevision(t.Context()); ok || !errors.Is(err, ErrUnauthorized) || f.writes != 1 {
				t.Fatalf("duplicate failure admission = %t, %v, writes = %d", ok, err, f.writes)
			}
		})
	}
}
