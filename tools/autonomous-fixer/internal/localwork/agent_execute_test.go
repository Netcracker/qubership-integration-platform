package localwork

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestExplicitAgentExecuteUsesFreeAuthorWorkflow(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" || report.Workflow != "agent" {
		t.Fatal(report.Status, err)
	}
	if report.Contract != nil {
		t.Fatal("author still required to construct a machine contract")
	}
	files, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*"))
	if len(files) != 3 || !strings.HasSuffix(files[0], "-author") || !strings.HasSuffix(files[1], "-review") || !strings.HasSuffix(files[2], "-verify") {
		t.Fatal(files)
	}
	for _, glob := range []string{"declaration-correction", "response-schema.json", "observation-catalog.json"} {
		matches, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*", glob))
		if len(matches) > 0 {
			t.Fatal("legacy declaration gate reached", matches)
		}
	}
	if !strings.Contains(report.Observations[0].Declaration.Narrative, "/original/issue.png") {
		t.Fatal("reference to source attachment lost")
	}
	public, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "config.json"))
	if err != nil || !strings.Contains(string(public), `"workflow": "agent"`) {
		t.Fatal("agent workflow not recorded", err)
	}
}

func TestAgentMalformedReviewerPreservesAuthorWorkWithoutRestart(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	cfg.Environment["QIP_AGENT_ASSESSMENT"] = "I could not complete the review; see the source image."
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verification-blocked" {
		t.Fatal(report.Status, err)
	}
	authors, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-author"))
	if len(authors) != 1 {
		t.Fatal("formatting restarted author", authors)
	}
	raw, err := os.ReadFile(filepath.Join(cfg.OutputDirectory, "step-0002-review", "response-raw.txt"))
	if err != nil || !strings.Contains(string(raw), "source image") {
		t.Fatal("review notes lost", err)
	}
	code, _ := os.ReadFile(filepath.Join(cfg.Workspace, "engine/code.txt"))
	if string(code) != "candidate" {
		t.Fatal("author work lost")
	}
}

func TestAgentUnsupportedCrossRunResumeFailsBeforeStartingWork(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	cfg.ResumeFrom = "/old/report.json"
	if _, err := Prepare(context.Background(), cfg); err == nil {
		t.Fatal("unsupported cross-run resume accepted")
	}
	if _, err := os.Stat(cfg.OutputDirectory); !os.IsNotExist(err) {
		t.Fatal("created run before validating workflow", err)
	}
}

func TestAgentExecuteRepairHelper(t *testing.T) {
	if os.Getenv("QIP_AGENT_HELPER") != "1" {
		return
	}
	prompt, err := io.ReadAll(os.Stdin)
	if err != nil {
		os.Exit(2)
	}
	code, _ := os.ReadFile("engine/code.txt")
	if strings.Contains(string(prompt), `"stage": "review"`) && string(code) == "candidate" {
		os.Setenv("QIP_AGENT_ASSESSMENT", `{"decision":"changes-requested","summary":"Preserve the healthy peer.","findings":["Adjust the consumer behavior."]}`)
	}
	reader, writer, err := os.Pipe()
	if err != nil {
		os.Exit(2)
	}
	go func() { writer.Write(prompt); writer.Close() }()
	os.Stdin = reader
	TestAgentRuntimeHelper(t)
}

func TestExplicitAgentExecuteRepairsInSameNativeSession(t *testing.T) {
	cfg := agentFixture(t)
	cfg.Workflow = "agent"
	binary, _ := os.Executable()
	script := "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fixture runtime'; exit 0; fi\nexec '" + strings.ReplaceAll(binary, "'", "'\\''") + "' -test.run=TestAgentExecuteRepairHelper -- \"$@\"\n"
	if err := os.WriteFile(cfg.RuntimeBinary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	report, err := Execute(context.Background(), cfg)
	if err != nil || report.Status != "verified" {
		t.Fatal(report.Status, err)
	}
	authors, _ := filepath.Glob(filepath.Join(cfg.OutputDirectory, "step-*-author"))
	if len(authors) != 2 {
		t.Fatal(authors)
	}
	command, _ := os.ReadFile(filepath.Join(authors[1], "command.json"))
	if !strings.Contains(string(command), "resume") || !strings.Contains(string(command), "author-session-123") {
		t.Fatal("repair did not resume native author", string(command))
	}
	code, _ := os.ReadFile(filepath.Join(cfg.Workspace, "engine/code.txt"))
	if string(code) != "repaired" {
		t.Fatal("feedback not applied")
	}
}
