package experimentprobe

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
)

// Recheck reinterprets recorded events without launching a process or changing the original report.
func Recheck(directory string) (Report, error) {
	var report Report
	directory, err := filepath.Abs(directory)
	if err != nil {
		return report, err
	}
	b, err := os.ReadFile(filepath.Join(directory, "report.json"))
	if err != nil {
		return report, err
	}
	if err = json.Unmarshal(b, &report); err != nil {
		return report, err
	}
	report.Directory = directory
	report.EffectiveModelVerified = false
	report.AccessPassed = false
	report.ContextPassed = false
	report.ToolPassed = false
	report.Outcome = "unverified"
	report.Final = nil
	markers := map[string]string{}
	for key, name := range map[string]string{"root_marker": "AGENTS.md", "skill_marker": ".agents/skills/probe/SKILL.md", "payload_marker": "payload.txt"} {
		b, err = os.ReadFile(filepath.Join(directory, "fixture-source", name))
		if err != nil {
			return report, err
		}
		matches := regexp.MustCompile(key+`_[a-f0-9]{32}`).FindAllString(string(b), -1)
		if len(matches) != 1 {
			return report, fmt.Errorf("fixture source must contain one %s", key)
		}
		markers[key] = matches[0]
	}
	raw, err := os.ReadFile(filepath.Join(directory, "stdout.jsonl"))
	if err != nil {
		return report, err
	}
	report.Events, err = experimentruntime.ParseEvents(bytes.NewReader(raw), report.Profile.Runtime)
	if err != nil {
		return report, err
	}
	report.Final, report.ToolPassed = semanticEvidence(raw, report.Profile.Runtime, markers)
	report.ContextPassed = matches(report.Final, markers)
	report.AccessPassed = report.ContextPassed && !report.Events.HasError && report.Process.ExitCode == 0 && report.Process.Classification == "completed_unverified" && report.Events.MalformedLines == 0 && len(report.Events.UnknownTypes) == 0
	if report.Events.HasError {
		report.Outcome = "runtime_error"
	} else if report.AccessPassed && report.ToolPassed {
		report.Outcome = "capability_observed_unverified_model"
	}
	return report, writeJSON(filepath.Join(directory, "report-rechecked.json"), report)
}
