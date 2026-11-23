package doccycle

import (
	"errors"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/workcycle"
)

func entryRoute(entry *workcycle.DocumentationEntry) (string, error) {
	if entry == nil {
		return string(AssessStage), nil
	}
	switch entry.Route {
	case string(AssessStage), string(WriteStage):
		if entry.Decision != nil {
			return "", errors.New("agent entry cannot contain a controller documentation decision")
		}
	case "docs-no-change":
		if err := validateDecision(entry.Decision); err != nil {
			return "", err
		}
		if entry.Decision.Decision != "no_change" || strings.TrimSpace(entry.Decision.IssueComment) == "" {
			return "", errors.New("automatic no-change entry requires a no-change decision and issue comment")
		}
	default:
		return "", errors.New("unknown documentation entry route")
	}
	if !evidence(entry.Evidence) {
		return "", errors.New("documentation entry requires routing evidence")
	}
	return entry.Route, nil
}
