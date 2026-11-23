package fixer

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
)

var StopReasons = []string{"approval-required", "risk-found", "decision-required", "context-missing", "access-missing", "verification-blocked", "environment-blocked", "external-dependency", "conflict-unresolved", "scope-review-required", "invalid", "stuck", "disagreement", "budget-exhausted"}
var loginPattern = regexp.MustCompile(`^[a-zA-Z0-9](?:[a-zA-Z0-9]|-[a-zA-Z0-9]){0,38}$`)
var protectedPath = regexp.MustCompile(`(?:^|/)(?:AGENTS|CLAUDE)\.md$|^\.github/workflows/(?:agent-[^/]+|pr-linked-issue)\.ya?ml$`)

func validLogin(s string) bool { return len(s) <= 39 && loginPattern.MatchString(s) }
func ParseAllowedActors(raw string) []string {
	result := []string{}
	for _, part := range strings.Split(raw, ",") {
		actor := strings.TrimSpace(part)
		if !validLogin(actor) {
			return []string{}
		}
		actor = strings.ToLower(actor)
		if !contains(result, actor) {
			result = append(result, actor)
		}
	}
	return result
}
func Allowed(raw string, actors ...string) bool {
	allowed := ParseAllowedActors(raw)
	for _, actor := range actors {
		if !validLogin(actor) || !contains(allowed, strings.ToLower(actor)) {
			return false
		}
	}
	return len(actors) > 0
}
func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
func requirementsSHA256(requirements string) string {
	digest := sha256.Sum256([]byte(requirements))
	return hex.EncodeToString(digest[:])
}

func EventKey(e Event) string {
	s := e.Source
	var fields []string
	id := func(n int64) string { return strconv.FormatInt(n, 10) }
	switch s.Kind {
	case "comment":
		if s.ID > 0 && s.Action == "created" {
			fields = []string{"comment", id(s.ID)}
		}
	case "label":
		if s.ID > 0 && contains([]string{"added", "removed"}, s.Action) {
			fields = []string{"label", id(s.ID), s.Action}
		}
	case "issue":
		if s.ID > 0 && s.Revision > 0 && strings.TrimSpace(e.Type) != "" {
			fields = []string{"issue", id(s.ID), id(s.Revision), e.Type, requirementsSHA256(e.Requirements)}
		}
	case "workflow":
		if s.RunID > 0 && s.RunAttempt > 0 && s.CallbackID > 0 && strings.TrimSpace(e.Type) != "" {
			fields = []string{"workflow", id(s.RunID), strconv.Itoa(s.RunAttempt), id(s.CallbackID), e.Type}
		}
	}
	if fields == nil {
		return ""
	}
	b, _ := json.Marshal(fields)
	return string(b)
}

type PathViolation struct {
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

func canonicalPath(p string) bool {
	if p == "" || strings.Contains(p, "\\") || regexp.MustCompile(`^[a-zA-Z]:`).MatchString(p) {
		return false
	}
	for _, r := range p {
		if r < 32 || r == 127 {
			return false
		}
	}
	for _, part := range strings.Split(p, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return true
}
func CheckPaths(paths, scopePaths []string, allowCI bool) []PathViolation {
	violations := []PathViolation{}
	for _, p := range paths {
		reason := ""
		protected := false
		for _, base := range []string{"tools/autonomous-fixer", ".apm", ".agents", ".codex", ".claude"} {
			if p == base || strings.HasPrefix(p, base+"/") {
				protected = true
			}
		}
		if protectedPath.MatchString(p) {
			protected = true
		}
		switch {
		case !canonicalPath(p):
			reason = "noncanonical-path"
		case protected:
			reason = "protected-path"
		case !allowCI && (p == ".github/actions" || p == ".github/workflows" || strings.HasPrefix(p, ".github/actions/") || strings.HasPrefix(p, ".github/workflows/")):
			reason = "ci-change"
		default:
			inScope := false
			for _, scope := range scopePaths {
				base := strings.TrimSuffix(scope, "/")
				if canonicalPath(base) && (p == base || strings.HasPrefix(p, base+"/")) {
					inScope = true
				}
			}
			if !inScope {
				reason = "outside-scope"
			}
		}
		if reason != "" {
			violations = append(violations, PathViolation{p, reason})
		}
	}
	return violations
}
