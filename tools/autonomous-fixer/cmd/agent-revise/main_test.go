package main

import (
	"errors"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/hostedrevision"
)

func TestUnconfirmedPublisherFailureLeavesTheResultOpenForRetry(t *testing.T) {
	signature := &agentsignature.Signature{Model: "openrouter/test/model"}
	result := hostedrevision.Result{Status: "published", Commit: "tip", Signature: signature}
	writes := 0
	report := func(got hostedrevision.Result) error {
		writes++
		if got != result {
			t.Fatalf("report = %#v", got)
		}
		return nil
	}
	transportFailure := errors.New("publication readback timed out")
	if err := finishRevision(result, transportFailure, report); !errors.Is(err, transportFailure) || writes != 0 {
		t.Fatalf("unconfirmed outcome = %v, writes = %d", err, writes)
	}
	if err := finishRevision(result, nil, report); err != nil || writes != 1 {
		t.Fatalf("retry outcome = %v, writes = %d", err, writes)
	}
}

func TestConfirmedStalePublicationClosesTheRequestWithTheWorkerSignature(t *testing.T) {
	signature := &agentsignature.Signature{Model: "openrouter/test/model"}
	result := hostedrevision.Result{Status: "published", Signature: signature}
	report := func(got hostedrevision.Result) error {
		if got.Status != "stale" || got.Signature != signature {
			t.Fatalf("report = %#v", got)
		}
		return nil
	}
	if err := finishRevision(result, hostedrevision.ErrStale, report); err != nil {
		t.Fatal(err)
	}
}
