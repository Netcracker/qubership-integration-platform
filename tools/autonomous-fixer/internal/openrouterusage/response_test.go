package openrouterusage

import (
	"encoding/json"
	"io"
	"strings"
	"testing"
	"testing/iotest"
)

func TestReportsUsageAcrossStreamChunkBoundaries(t *testing.T) {
	wire := ": upstream\r\n\r\ndata: {\"id\":\"fixture-id\",\"model\":\"openai/fixture\",\"choices\":[{\"content\":\"inert-sensitive-text\"}]}\r\n\r\n" +
		"data: malformed\n\ndata: {\"id\":\"fixture-id\",\n" +
		"data: \"usage\":{\"prompt_tokens\":24,\"completion_tokens\":6,\"cost\":0.004,\"prompt_tokens_details\":{\"cached_tokens\":3},\"completion_tokens_details\":{\"reasoning_tokens\":2}}}\n\n" +
		"data: [DONE]\n\n"
	var meter Recorder
	for range 2 {
		finish := meter.Begin()
		var usage Generation
		reader := Observe(io.NopCloser(iotest.OneByteReader(strings.NewReader(wire))), "text/event-stream; charset=utf-8", "inert-sensitive-text", &usage)
		got, err := io.ReadAll(reader)
		if err != nil || string(got) != wire {
			t.Fatal("the observer changed the streamed bytes", err)
		}
		finish(usage)
	}
	report := meter.Report()
	if report.Attempts != 2 || len(report.Generations) != 1 || report.DuplicateResponses != 1 ||
		report.ReportedCostUSD == nil || *report.ReportedCostUSD != 0.004 {
		t.Fatal(report)
	}
	generation := report.Generations[0]
	if generation.ServedModel != "openai/fixture" || *generation.InputTokens != 24 ||
		*generation.OutputTokens != 6 || *generation.CachedTokens != 3 || *generation.Reasoning != 2 {
		t.Fatal(generation)
	}
	b, err := json.Marshal(report)
	if err != nil || strings.Contains(string(b), "inert-sensitive-text") || strings.Contains(string(b), "fixture-id") {
		t.Fatal("usage report contains raw response content", err)
	}
}

func TestMalformedOrMissingUsageStaysUnknown(t *testing.T) {
	for _, response := range []string{
		`{"id":"fixture","model":"openai/fixture"}`,
		`{"id":"fixture","usage":{"cost":null}}`,
		`{"id":"fixture","usage":{"cost":-1}}`,
		`{"id":"fixture","usage":{"cost":"unknown"}}`,
		`{"usage":{"cost":0.5}}`,
		`{"id":"fixture","usage":`,
	} {
		var meter Recorder
		finish := meter.Begin()
		var usage Generation
		body := Observe(io.NopCloser(strings.NewReader(response)), "application/json", "", &usage)
		raw, err := io.ReadAll(body)
		if err != nil || string(raw) != response {
			t.Fatal("the observer changed the JSON body", err)
		}
		finish(usage)
		report := meter.Report()
		if report.Attempts != 1 || report.UnknownCosts != 1 || report.ReportedCostUSD != nil || report.KnownCostUSD != 0 {
			t.Fatal(response, report)
		}
	}
}

func TestZeroCostAndConflictingDuplicateRemainDistinct(t *testing.T) {
	var meter Recorder
	for _, raw := range []string{
		`{"id":"free","usage":{"cost":0}}`,
		`{"id":"conflicting","usage":{"cost":0.003}}`,
		`{"id":"conflicting","usage":{"cost":0.004}}`,
	} {
		finish := meter.Begin()
		var usage Generation
		if _, err := io.Copy(io.Discard, Observe(io.NopCloser(strings.NewReader(raw)), "application/json", "", &usage)); err != nil {
			t.Fatal(err)
		}
		finish(usage)
	}
	report := meter.Report()
	if len(report.Generations) != 2 || report.DuplicateResponses != 1 || report.UnknownCosts != 1 ||
		report.ReportedCostUSD != nil || report.KnownCostUSD != 0 {
		t.Fatal(report)
	}
	for _, generation := range report.Generations {
		if generation.Conflict == (generation.CostUSD != nil) {
			t.Fatal(generation)
		}
	}
}

func TestOversizedEventDoesNotHideLaterUsage(t *testing.T) {
	wire := "data: " + strings.Repeat("x", maxFrameBytes) + "\n\ndata: {\"id\":\"fixture\",\"usage\":{\"cost\":0.003}}\n\n"
	var generation Generation
	if _, err := io.Copy(io.Discard, Observe(io.NopCloser(strings.NewReader(wire)), "text/event-stream", "", &generation)); err != nil {
		t.Fatal(err)
	}
	if generation.IDSHA256 == "" || generation.CostUSD == nil || *generation.CostUSD != .003 {
		t.Fatal(generation)
	}
}
