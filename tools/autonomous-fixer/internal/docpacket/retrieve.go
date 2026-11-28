package docpacket

import (
	"fmt"
	"path"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

type retrievalResult struct{ docs, sources, evidence, limitations []string }
type rankedPage struct {
	path    string
	score   int
	matches []string
}

var words = regexp.MustCompile(`[A-Za-z][A-Za-z0-9]{3,}`)
var links = regexp.MustCompile(`\]\(([^)#?]+)(?:[?#][^)]*)?\)`)
var stopWords = map[string]bool{"this": true, "that": true, "with": true, "from": true, "when": true, "then": true, "into": true, "have": true, "should": true, "using": true, "user": true, "issue": true, "change": true, "changes": true, "the": true, "file": true, "src": true, "components": true, "component": true, "schemas": true, "runtime": true, "catalog": true, "help": true, "docs": true, "readme": true, "main": true, "java": true, "tsx": true, "json": true, "typescript": true, "function": true, "const": true, "return": true, "import": true, "export": true, "class": true, "public": true, "private": true, "string": true, "boolean": true, "false": true, "true": true}

func splitIdentifierWords(s string) string {
	// Split identifiers as well as path and prose words.
	var split strings.Builder
	var prev rune
	for _, r := range s {
		if unicode.IsUpper(r) && unicode.IsLower(prev) {
			split.WriteByte(' ')
		}
		split.WriteRune(r)
		prev = r
	}
	return split.String()
}

func terms(s string) []string {
	seen := map[string]bool{}
	var out []string
	for _, w := range words.FindAllString(splitIdentifierWords(s), -1) {
		w = strings.ToLower(w)
		if !stopWords[w] && !seen[w] {
			seen[w] = true
			out = append(out, w)
		}
	}
	sort.Strings(out)
	return out
}

func buildOnly(paths []string) bool {
	if len(paths) == 0 {
		return false
	}
	for _, p := range paths {
		if !(strings.HasSuffix(p, "/Dockerfile") || strings.HasPrefix(p, "infrastructure/docker/") || strings.HasPrefix(p, ".github/")) {
			return false
		}
	}
	return true
}

func isPatchChange(line string) bool {
	return (strings.HasPrefix(line, "+") || strings.HasPrefix(line, "-")) && !strings.HasPrefix(line, "+++") && !strings.HasPrefix(line, "---")
}

// retrieve searches only Git objects at the documentation revision.
func retrieve(git func(...string) ([]byte, error), sha string, changed []string, issue Issue, diff string) (retrievalResult, error) {
	var result retrievalResult
	if buildOnly(changed) {
		result.evidence = append(result.evidence, "Only build and infrastructure paths changed; no product help search is required by the retrieval plan.")
		return result, nil
	}
	tree, err := git("ls-tree", "-r", "--name-only", "-z", sha, "--", "help/docs")
	if err != nil {
		return result, err
	}
	var pages []string
	for _, p := range strings.Split(string(tree), "\x00") {
		if strings.HasSuffix(strings.ToLower(p), ".md") || strings.HasSuffix(strings.ToLower(p), ".mdx") {
			pages = append(pages, p)
		}
	}
	sort.Strings(pages)
	pathTerms := terms(strings.Join(changed, " "))
	issueTerms := terms(issue.Title + " " + issue.Body)
	// Identifiers from changed lines are useful, but common prose and imports are noisy.
	var changedLines strings.Builder
	for _, line := range strings.Split(diff, "\n") {
		if isPatchChange(line) {
			changedLines.WriteString(line)
			changedLines.WriteByte(' ')
		}
	}
	symbolTerms := terms(changedLines.String())
	if len(symbolTerms) > 100 {
		symbolTerms = symbolTerms[:100]
		result.evidence = append(result.evidence, "Changed-line query terms capped at 100 in lexical order.")

	}
	result.evidence = append(result.evidence, fmt.Sprintf("Documentation search at %s: path terms %q; issue terms %q; changed-line terms %q. Score: 5 per path term in page path, 2 per path term in content, 2 per issue term and 1 per changed-line term in path or content; ties sort by path.", sha, pathTerms, issueTerms, symbolTerms))
	var ranked []rankedPage
	for _, p := range pages {
		b, e := git("show", sha+":"+p)
		if e != nil {
			return result, e
		}
		name := strings.ToLower(p)
		body := strings.ToLower(string(b))
		score := 0
		var matches []string
		for _, t := range pathTerms {
			if strings.Contains(name, t) {
				score += 5
				matches = append(matches, "path:"+t)
			}
			if strings.Contains(body, t) {
				score += 2
			}
		}
		for _, t := range issueTerms {
			if strings.Contains(name, t) || strings.Contains(body, t) {
				score += 2
				matches = append(matches, "issue:"+t)
			}
		}
		for _, t := range symbolTerms {
			if strings.Contains(name, t) || strings.Contains(body, t) {
				score++
				matches = append(matches, "symbol:"+t)
			}
		}
		if score > 0 {
			ranked = append(ranked, rankedPage{p, score, matches})
		}
	}
	sort.Slice(ranked, func(i, j int) bool {
		if ranked[i].score != ranked[j].score {
			return ranked[i].score > ranked[j].score
		}
		return ranked[i].path < ranked[j].path
	})
	const maxPages = 8
	for i, c := range ranked {
		if i < maxPages {
			result.docs = append(result.docs, c.path)
			result.evidence = append(result.evidence, fmt.Sprintf("Documentation search selected %s: score %d, matches %q.", c.path, c.score, c.matches))
		} else {
			result.evidence = append(result.evidence, fmt.Sprintf("Documentation search omitted %s: score %d, candidate limit %d.", c.path, c.score, maxPages))

		}
	}
	if len(ranked) == 0 {
		result.evidence = append(result.evidence, "Documentation search found no matching pages.")
	}
	// Include local Markdown links from selected pages, one hop, within a fixed candidate limit.
	seen := map[string]bool{}
	for _, p := range result.docs {
		seen[p] = true
	}
	for _, p := range append([]string(nil), result.docs...) {
		b, e := git("show", sha+":"+p)
		if e != nil {
			return result, e
		}
		for _, m := range links.FindAllStringSubmatch(string(b), -1) {
			target := m[1]
			if strings.Contains(target, ":") || strings.HasPrefix(target, "/") {
				continue
			}
			target = path.Clean(path.Join(path.Dir(p), target))
			if !strings.HasPrefix(target, "help/docs/") || !(strings.HasSuffix(strings.ToLower(target), ".md") || strings.HasSuffix(strings.ToLower(target), ".mdx")) {
				continue
			}
			if seen[target] {
				result.evidence = append(result.evidence, fmt.Sprintf("Selected local link %s from %s (already selected).", target, p))
				continue
			}
			seen[target] = true
			if len(terms(target)) > 0 {
				matched := false
				for _, term := range issueTerms {
					if strings.Contains(strings.ToLower(target), term) {
						matched = true
						break
					}
				}
				if !matched {
					result.evidence = append(result.evidence, fmt.Sprintf("Local link from %s omitted: %s does not match issue terms.", p, target))
					continue
				}
			}
			if _, e := git("cat-file", "-e", sha+":"+target); e != nil {
				result.evidence = append(result.evidence, fmt.Sprintf("Local link from %s omitted: %s is absent at %s.", p, target, sha))
				result.limitations = append(result.limitations, fmt.Sprintf("Local link from %s is unresolved: %s.", p, target))
				continue
			}
			if len(result.docs) >= 20 {
				result.evidence = append(result.evidence, fmt.Sprintf("Local link from %s omitted: %s exceeds the 20-page candidate limit.", p, target))
				result.limitations = append(result.limitations, "Local links exceeded the page limit.")
				continue
			}
			result.docs = append(result.docs, target)
			result.evidence = append(result.evidence, fmt.Sprintf("Selected local link %s from %s.", target, p))
		}
	}
	// Current versions of changed production files complement the historical diff.
	sourceCandidates := append([]string(nil), changed...)
	rankSource := func(p string) int {
		switch {
		case strings.HasPrefix(p, "schemas/src/main/"):
			return 0
		case strings.Contains(p, "/resources/elements/"):
			return 1
		case strings.HasPrefix(p, "ui/src/pages/") || len(autoDocumentationCandidates([]string{p})) > 0:
			return 2
		case strings.HasPrefix(p, "ui/src/"):
			return 3
		default:
			return 4
		}
	}
	sort.SliceStable(sourceCandidates, func(i, j int) bool { return rankSource(sourceCandidates[i]) < rankSource(sourceCandidates[j]) })
	for _, p := range sourceCandidates {
		if strings.HasPrefix(p, "help/") || compressed(p) {
			continue
		}
		if _, e := git("cat-file", "-e", sha+":"+p); e != nil {
			result.evidence = append(result.evidence, fmt.Sprintf("Current source omitted: %s is absent at %s.", p, sha))
			continue
		}
		if len(result.sources) >= 20 {
			result.evidence = append(result.evidence, fmt.Sprintf("Current source omitted: %s exceeds the 20-file candidate limit.", p))
			result.limitations = append(result.limitations, "Current sources exceeded the file limit.")
			continue
		}
		result.sources = append(result.sources, p)
		result.evidence = append(result.evidence, fmt.Sprintf("Selected current source %s because its production path changed.", p))
	}
	// Find other production files that name a changed type or component.
	identifiers := map[string]bool{}
	for _, p := range changed {
		base := strings.TrimSuffix(path.Base(p), path.Ext(p))
		if len(base) >= 5 && base != "index" && base != "readme" && base != "schema" && !strings.ContainsAny(base, " .-") {
			identifiers[base] = true
		}
	}
	var names []string
	for name := range identifiers {
		names = append(names, name)
	}
	sort.Strings(names)
	seenSources := map[string]bool{}
	for _, p := range result.sources {
		seenSources[p] = true
	}
	for _, name := range names {
		result.evidence = append(result.evidence, fmt.Sprintf("Source consumer search at %s: fixed identifier %q in UI, schemas, and runtime modules.", sha, name))
		found, err := git("grep", "-l", "-z", "-F", "-e", name, sha, "--", "ui/src", "schemas", "runtime-catalog/src/main", "engine/src/main", "micro-engine/src/main", "integration-build-pipeline/src/main", "testing-service/internal")
		if err != nil {
			if strings.Contains(err.Error(), "exit status 1:") {
				result.evidence = append(result.evidence, fmt.Sprintf("Source consumer search for %q returned no results.", name))
			} else {
				result.evidence = append(result.evidence, fmt.Sprintf("Source consumer search for %q failed: %v.", name, err))
				result.limitations = append(result.limitations, fmt.Sprintf("Source consumer search failed for %q.", name))
			}
			continue
		}
		for _, entry := range strings.Split(string(found), "\x00") {
			p := strings.TrimPrefix(entry, sha+":")
			if p == "" || seenSources[p] || compressed(p) {
				continue
			}
			seenSources[p] = true
			if len(result.sources) >= 20 {
				result.evidence = append(result.evidence, fmt.Sprintf("Source consumer %s omitted: 20-file candidate limit (identifier %q).", p, name))
				result.limitations = append(result.limitations, "Source consumers exceeded the file limit.")
				continue
			}
			result.sources = append(result.sources, p)
			result.evidence = append(result.evidence, fmt.Sprintf("Selected source consumer %s: fixed identifier %q.", p, name))
		}
	}
	return result, nil
}
