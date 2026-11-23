package autofollow

import (
	"crypto/sha256"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/autorequest"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

func TestRepeatedDiagnosticDoesNotBlockNewDiagnosticInSameJob(t *testing.T) {
	for _, legacy := range []bool{false, true} {
		t.Run(fmt.Sprintf("legacy=%t", legacy), func(t *testing.T) {
			f := newFixture(t)
			f.check, f.annotation = "failure", "src/nav.ts(15,3): error TS2322: incompatible type"
			f.options.Apply = false
			first, err := f.reconcile()
			if err != nil || len(first.Failures) != 1 {
				t.Fatalf("initial CI plan = %+v, %v", first, err)
			}
			prior := first.Failures[0]
			if legacy {
				prior.Excerpt += "\nTypeScript reported TS2323 at src/nav.ts:18:3."
				identity := "2\x002\x00Test frontend\x00TypeScript reported TS2322 at src/nav.ts.\nTypeScript reported TS2323 at src/nav.ts."
				prior.Key = fmt.Sprintf("%x", sha256.Sum256([]byte(identity)))
			}
			f.addRequest(autorequest.Request{RepositoryID: 1, PullID: 54, PullNumber: 2, Head: headSHA, Failures: []autorequest.Failure{prior}}, time.Now().Add(-10*time.Minute))
			f.admit(200, 400, true, headSHA, prior.Key)
			f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
				"<!-- qip-agent-revision-result:v1 1:2:200:400:1:%s:published:%s -->", headSHA, nextSHA)})
			f.pr.Head.SHA = nextSHA
			f.annotation += "\nsrc/nav.ts(23,3): error TS2322: incompatible type"
			f.options.Apply = true
			report, err := f.reconcile()
			if err != nil || report.Status != "dispatched" || len(report.Failures) != 1 || len(report.Human) != 1 || f.dispatches != 1 || len(f.comments) != 5 {
				t.Fatalf("CI follow-up = %+v, %v", report, err)
			}
			if note := f.comments[3].Body; !strings.Contains(note, "src/nav.ts&#58;15&#58;3") || !strings.Contains(note, "#issuecomment-200") {
				t.Fatalf("maintainer note = %s", note)
			}
			request, err := autorequest.Decode(f.comments[4].Body)
			if err != nil || len(request.Failures) != 1 || request.Failures[0].Excerpt != "TypeScript reported TS2322 at src/nav.ts:23:3." {
				t.Fatalf("new revision request = %+v, %v", request, err)
			}
		})
	}
}

func TestJestRepairHistoryDistinguishesCasesAndKeepsSuiteCoverage(t *testing.T) {
	for _, legacy := range []bool{false, true} {
		t.Run(fmt.Sprintf("legacy=%t", legacy), func(t *testing.T) {
			f := newFixture(t)
			const suite = "FAIL ui/src/Button.test.tsx"
			f.check, f.annotation = "failure", suite+"\n  ● Button › opens"
			f.options.Apply = false
			first, err := f.reconcile()
			if err != nil || len(first.Failures) != 1 {
				t.Fatalf("initial CI plan = %+v, %v", first, err)
			}
			prior := first.Failures[0]
			if legacy {
				prior.Excerpt = "Jest reported a failure in ui/src/Button.test.tsx."
				prior.Key = fmt.Sprintf("%x", sha256.Sum256([]byte("2\x002\x00Test frontend\x00"+prior.Excerpt)))
			}
			f.addRequest(autorequest.Request{RepositoryID: 1, PullID: 54, PullNumber: 2, Head: headSHA, Failures: []autorequest.Failure{prior}}, time.Now().Add(-10*time.Minute))
			f.admit(200, 400, true, headSHA, prior.Key)
			f.comments = append(f.comments, fixer.Comment{ID: 240, User: f.bot, Body: fmt.Sprintf(
				"<!-- qip-agent-revision-result:v1 1:2:200:400:1:%s:published:%s -->", headSHA, nextSHA)})
			f.pr.Head.SHA = nextSHA
			f.annotation = suite + "\n  ● Button › opens\n\n  ● Button › closes"
			f.options.Apply = true
			report, err := f.reconcile()
			if legacy {
				if err != nil || report.Status != "needs-human" || len(report.Failures) != 0 || len(report.Human) != 2 ||
					f.dispatches != 0 || len(f.comments) != 4 {
					t.Fatalf("legacy suite follow-up = %+v, %v", report, err)
				}
				if _, err := f.reconcile(); err != nil || f.dispatches != 0 || len(f.comments) != 4 {
					t.Fatalf("legacy suite follow-up repeated: %v", err)
				}
				return
			}
			if err != nil || report.Status != "dispatched" || len(report.Failures) != 1 || len(report.Human) != 1 ||
				report.Failures[0].Key == prior.Key || f.dispatches != 1 || len(f.comments) != 5 {
				t.Fatalf("Jest case follow-up = %+v, %v", report, err)
			}
			if report, err := f.reconcile(); err != nil || report.Status != "active" || f.dispatches != 1 || len(f.comments) != 5 {
				t.Fatalf("Jest case follow-up repeated: %+v, %v", report, err)
			}
		})
	}
}

func TestPendingLegacyJestRequestKeepsItsExistingDelivery(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "FAIL ui/src/Button.test.tsx\n  ● Button › opens\n  ● Button › closes"
	excerpt := "Jest reported a failure in ui/src/Button.test.tsx."
	key := fmt.Sprintf("%x", sha256.Sum256([]byte("2\x002\x00Test frontend\x00"+excerpt)))
	f.addRequest(autorequest.Request{RepositoryID: 1, PullID: 54, PullNumber: 2, Head: headSHA,
		Failures: []autorequest.Failure{{Key: key, Name: "Ui Build / Test frontend", Excerpt: excerpt}}}, time.Now().Add(-time.Hour))

	if report, err := f.reconcile(); err != nil || report.Status != "dispatched" || report.RequestID != 200 ||
		len(report.Failures) != 1 || report.Failures[0].Key != key || f.dispatches != 1 || len(f.comments) != 2 {
		t.Fatalf("legacy request delivery = %+v, %v", report, err)
	}
	if report, err := f.reconcile(); err != nil || report.Status != "active" || f.dispatches != 1 || len(f.comments) != 2 {
		t.Fatalf("legacy request delivery repeated: %+v, %v", report, err)
	}
}

func TestLegacyJestReceiptKeepsCoverageWithoutRequestExcerpt(t *testing.T) {
	f := newFixture(t)
	f.check, f.annotation = "failure", "FAIL ui/src/Button.test.tsx\n  ● Button › opens"
	excerpt := "Jest reported a failure in ui/src/Button.test.tsx."
	key := fmt.Sprintf("%x", sha256.Sum256([]byte("2\x002\x00Test frontend\x00"+excerpt)))
	f.admit(200, 400, true, headSHA, key)
	f.runs = append(f.runs, runRecord{WorkflowRun: f.worker(400, 200, true, "completed"), Actor: f.bot})
	for range 2 {
		if report, err := f.reconcile(); err != nil || report.Status != "unchanged" ||
			len(report.Failures) != 0 || f.dispatches != 0 || len(f.comments) != 1 {
			t.Fatalf("legacy receipt follow-up = %+v, %v", report, err)
		}
	}
}
