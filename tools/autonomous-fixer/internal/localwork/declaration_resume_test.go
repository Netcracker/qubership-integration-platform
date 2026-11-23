package localwork

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func correctedResumeFixture(t *testing.T) (Config, workcycle.Report, string) {
	t.Helper()
	cfg := fixture(t)
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestDeclarationCorrectionRuntimeHelper\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	cfg.ProductCheckBinary = cfg.RuntimeBinary
	cfg.Environment["QIP_DECLARATION_PRODUCT_TRACE"] = filepath.Join(filepath.Dir(cfg.Workspace), "product-trace")
	cfg.Environment["QIP_DECLARATION_AMBIGUOUS"] = "1"
	cfg.Environment["QIP_DECLARATION_VERIFY_BLOCKED"] = "1"
	seed, err := Execute(context.Background(), cfg)
	if err != nil || seed.Status != "verification-blocked" {
		t.Fatal(seed.Status, err)
	}
	source := cfg.OutputDirectory
	cfg.ResumeFrom = filepath.Join(source, "report.json")
	cfg.ResumeMode = "verification"
	cfg.OutputDirectory = filepath.Join(filepath.Dir(source), "continuation")
	delete(cfg.Environment, "QIP_DECLARATION_VERIFY_BLOCKED")
	cfg.Environment["QIP_DECLARATION_VERIFY_DATA"] = "data-C"
	return cfg, seed, source
}

func TestCorrectedDeclarationCanResumeFinalVerification(t *testing.T) {
	cfg, seed, source := correctedResumeFixture(t)
	rawPath := filepath.Join(source, "step-0002-reproduce", "response.json")
	rawBefore, _ := os.ReadFile(rawPath)
	var raw, bound workcycle.Response
	if json.Unmarshal(rawBefore, &raw) != nil {
		t.Fatal("invalid original")
	}
	b, _ := os.ReadFile(filepath.Join(source, "step-0002-reproduce", "response-bound.json"))
	if json.Unmarshal(b, &bound) != nil {
		t.Fatal("invalid bound")
	}
	if reflect.DeepEqual(raw, bound) || !reflect.DeepEqual(bound, seed.Observations[1].Declaration) {
		t.Fatal("bound and raw declarations lost their distinct meanings")
	}
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	rawAfter, _ := os.ReadFile(rawPath)
	if string(rawAfter) != string(rawBefore) {
		t.Fatal("resume rewrote original claims")
	}
	corrections, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", "declaration-correction"))
	if len(corrections) != 0 {
		t.Fatal("resume repeated declaration correction")
	}
	if report.Candidate.ID != seed.Candidate.ID {
		t.Fatal("resume changed source")
	}
}

func TestCorrectedResumeRejectsChangedDeclarationArchive(t *testing.T) {
	for _, name := range []string{"response-before-resolution.json", "declaration-correction/response.json", "declaration-correction/process.json"} {
		t.Run(name, func(t *testing.T) {
			cfg, _, source := correctedResumeFixture(t)
			path := filepath.Join(source, "step-0002-reproduce", name)
			if err := os.WriteFile(path, []byte("{}"), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := Prepare(context.Background(), cfg); err == nil {
				t.Fatal("changed correction archive accepted")
			}
			steps, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
			if len(steps) != 0 {
				t.Fatal("started native work before validating correction history")
			}
		})
	}
}
