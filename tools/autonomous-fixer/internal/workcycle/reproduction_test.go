package workcycle

import (
	"context"
	"testing"
)

func TestReproductionKeepsPermanentTestAndProductionBaseline(t *testing.T) {
	for _, productionEdit := range []bool{false, true} {
		t.Run(map[bool]string{false: "test only", true: "production edit"}[productionEdit], func(t *testing.T) {
			f := newFake()
			// A clean checkout has no changed-file hashes.
			f.alter = func(i Invocation, r *Response, f *fake) {
				if i.Stage == ReproduceStage {
					paths := []string{"e2e/specs/ui/navigation.spec.ts"}
					hashes := map[string]string{paths[0]: "new-test"}
					if productionEdit {
						paths = append(paths, "ui/src/Chains.tsx")
						hashes[paths[1]] = "changed"
					}
					f.snapshot = Snapshot{ID: "baseline-with-test", ChangedPaths: paths, FileHashes: hashes}
				}
				if i.Stage == ImplementStage && i.Candidate.ID != "baseline-with-test" {
					t.Fatal("regression test lost before implementation")
				}
			}
			report, err := Run(context.Background(), request(), f)
			if productionEdit {
				if err == nil {
					t.Fatal("accepted production edit during reproduction")
				}
				for _, call := range f.calls {
					if call.Stage == ImplementStage {
						t.Fatal("implemented after invalid reproduction")
					}
				}
			} else if err != nil || report.Status != "verified" {
				t.Fatalf("%+v %v", report, err)
			}
		})
	}
}

func TestReproductionChangeRequiresKnownTestDelta(t *testing.T) {
	before := Snapshot{ID: "base", FileHashes: map[string]string{}}
	for _, path := range []string{"e2e/support/api.ts", "e2e/playwright.config.ts", "ui/package.json", "ui/tests/setup.ts", "ui/tests/fixtures/chain.json", "ui/src/components/Chains.tsx", "engine/src/main/java/App.java", "../ui/src/App.test.ts", "e2e/specs/../../support/config.spec.ts"} {
		after := Snapshot{ID: "changed", ChangedPaths: []string{path}, FileHashes: map[string]string{path: "hash"}}
		if stageChangeAllowed(ReproduceStage, before, after) {
			t.Fatalf("accepted %s", path)
		}
	}
	unknown := Snapshot{ID: "changed", ChangedPaths: []string{"ui/src/App.test.ts"}}
	if stageChangeAllowed(ReproduceStage, before, unknown) {
		t.Fatal("accepted unmeasured delta")
	}
	for _, path := range []string{"ui/src/App.test.tsx", "ui/tests/pages/Chains.test.tsx", "ui/tests/pages/ChainPage.test.tsx", "e2e/specs/ui/navigation.spec.ts", "runtime-catalog/src/test/java/AppTest.java", "testing-service/internal/app_test.go"} {
		after := Snapshot{ID: "changed", ChangedPaths: []string{path}, FileHashes: map[string]string{path: "hash"}}
		if !stageChangeAllowed(ReproduceStage, before, after) {
			t.Fatalf("rejected %s", path)
		}
		if stageChangeAllowed(VerifyStage, before, after) || stageChangeAllowed(ReviewStage, before, after) {
			t.Fatal("relaxed read-only stage")
		}
	}
}

func TestResumeAcceptsMeasuredTestOnlyReproduction(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReproduceStage {
			f.snapshot = Snapshot{ID: "baseline-with-test", ChangedPaths: []string{"ui/src/App.test.tsx"}, FileHashes: map[string]string{"ui/src/App.test.tsx": "test"}}
		}
		if i.Stage == VerifyStage {
			r.Question = &Question{Kind: "verification-blocked", Text: "Preview unavailable", Needed: "Restore preview", Evidence: []string{"observation"}}
		}
	}
	seed, err := Run(context.Background(), request(), f)
	if err != nil || seed.Status != "verification-blocked" {
		t.Fatal(seed, err)
	}
	report, err := ResumeVerification(context.Background(), request(), resumeDriver(seed), seed)
	if err != nil || report.Status != "verified" {
		t.Fatal(report, err)
	}
}

func TestResumeRejectsCandidateWithOnlyReproductionEdits(t *testing.T) {
	f := newFake()
	f.alter = func(i Invocation, r *Response, f *fake) {
		if i.Stage == ReproduceStage {
			f.snapshot = Snapshot{ID: "baseline-with-test", ChangedPaths: []string{"ui/tests/pages/Chains.test.tsx"}, FileHashes: map[string]string{"ui/tests/pages/Chains.test.tsx": "test"}}
		}
		if i.Stage == ImplementStage {
			f.snapshot = i.Candidate
		}
		if i.Stage == VerifyStage {
			r.Question = &Question{Kind: "verification-blocked", Text: "Preview unavailable", Needed: "Restore preview", Evidence: []string{"observation"}}
		}
	}
	seed, err := Run(context.Background(), request(), f)
	if err != nil || seed.Status != "verification-blocked" {
		t.Fatal(seed, err)
	}
	driver := resumeDriver(seed)
	report, err := ResumeVerification(context.Background(), request(), driver, seed)
	if err == nil || report.Status == "verified" || len(driver.calls) != 0 {
		t.Fatal("accepted reproduction-only candidate", report, err)
	}
}
