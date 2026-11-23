package agentci

import (
	"cmp"
	"context"
	"fmt"
	"net/url"
	"slices"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/fixer"
)

type Requirement struct {
	Name  string `json:"name"`
	AppID int64  `json:"app_id,omitempty"`
}

func Required(ctx context.Context, client *fixer.GitHubClient, baseRef, configuredNames string) ([]Requirement, []string) {
	var requirements []Requirement
	var diagnostics []string
	hasConfiguredChecks := strings.TrimSpace(configuredNames) != ""
	var rules []struct {
		Type       string `json:"type"`
		Parameters struct {
			Checks []struct {
				Context string `json:"context"`
				AppID   int64  `json:"integration_id"`
			} `json:"required_status_checks"`
		} `json:"parameters"`
	}
	branch := url.PathEscape(baseRef)
	if err := client.Request(ctx, "GET", "/rules/branches/"+branch, nil, &rules); err != nil {
		diagnostics = append(diagnostics, fmt.Sprintf("GitHub branch rules could not be read (%v).", err))
	}
	for _, rule := range rules {
		switch rule.Type {
		case "required_status_checks":
			for _, check := range rule.Parameters.Checks {
				requirements = append(requirements, Requirement{Name: check.Context, AppID: max(0, check.AppID)})
			}
		case "workflows":
			if !hasConfiguredChecks {
				diagnostics = append(diagnostics, "The branch rules require workflows. Set AGENT_CI_REQUIRED_CHECKS to track their expected job names.")
			}
		}
	}
	var protection struct {
		Protected  bool `json:"protected"`
		Protection struct {
			Enabled bool `json:"enabled"`
			Status  struct {
				Contexts []string `json:"contexts"`
				Checks   []struct {
					Context string `json:"context"`
					AppID   int64  `json:"app_id"`
				} `json:"checks"`
			} `json:"required_status_checks"`
		} `json:"protection"`
	}
	if err := client.Request(ctx, "GET", "/branches/"+branch, nil, &protection); err != nil {
		diagnostics = append(diagnostics, fmt.Sprintf("GitHub branch protection could not be read (%v).", err))
	}
	for _, check := range protection.Protection.Status.Checks {
		requirements = append(requirements, Requirement{Name: check.Context, AppID: max(0, check.AppID)})
	}
	for _, name := range append(protection.Protection.Status.Contexts, strings.Split(configuredNames, "\n")...) {
		name = strings.TrimSpace(name)
		if name != "" && !slices.ContainsFunc(requirements, func(check Requirement) bool { return check.Name == name }) {
			requirements = append(requirements, Requirement{Name: name})
		}
	}
	if protection.Protected && !protection.Protection.Enabled && len(rules) == 0 && !hasConfiguredChecks {
		diagnostics = append(diagnostics, "GitHub reports branch protection without check details. Set AGENT_CI_REQUIRED_CHECKS to track their expected job names.")
	}
	return uniqueRequirements(requirements), diagnostics
}

func uniqueRequirements(requirements []Requirement) []Requirement {
	result := slices.DeleteFunc(slices.Clone(requirements), func(check Requirement) bool { return check.Name == "" })
	slices.SortFunc(result, func(a, b Requirement) int {
		return cmp.Or(cmp.Compare(a.Name, b.Name), cmp.Compare(a.AppID, b.AppID))
	})
	return slices.Compact(result)
}
