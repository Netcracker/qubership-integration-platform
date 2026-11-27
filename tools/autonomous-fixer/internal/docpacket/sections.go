package docpacket

import (
	"math"
	"slices"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

// selectHelpSections prefers mapped screens and changed pages. Otherwise it ranks the retrieved sections by issue terms.
func selectHelpSections(issue Issue, paths []string, resolved []DocMapping, pages []doctriage.Document) []doctriage.Document {
	relevant := make(map[string]bool)
	for _, mapping := range autoDocumentationCandidates(paths) {
		relevant[mapping.CurrentPath] = true
	}
	changedHelp := make(map[string]bool)
	for _, mapping := range resolved {
		if mapping.CurrentPath != "" {
			relevant[mapping.CurrentPath] = true
			changedHelp[mapping.CurrentPath] = true
		}
	}
	type passage struct {
		doc   doctriage.Document
		score int
	}
	title, body := terms(issue.Title), terms(issue.Body)
	var weights map[string]int
	if len(relevant) == 0 {
		body, weights = contentQuery(issue.Body, title, body, pages)
	}
	var matches []passage
	for _, page := range pages {
		if len(relevant) > 0 && !relevant[page.Path] {
			continue
		}
		pathWords := terms(page.Path)
		named := false
		for _, term := range title {
			if slices.Contains(pathWords, term) {
				named = true
				break
			}
		}
		if len(relevant) > 0 && !named && !changedHelp[page.Path] {
			continue
		}
		for _, text := range markdownSections(page.Content) {
			heading, sectionBody, _ := strings.Cut(text, "\n")
			if len(terms(sectionBody)) == 0 {
				continue
			}
			heading = strings.ToLower(heading)
			lower := strings.ToLower(text)
			score := 0
			for _, term := range title {
				if strings.Contains(lower, term) {
					score += 2 + weights[term]
				}
				if strings.Contains(heading, term) {
					score += 4
				}
				if strings.Contains(strings.ToLower(page.Path), term) {
					score++
				}
			}
			for _, term := range body {
				if strings.Contains(lower, term) {
					score += 1 + weights[term]
				}
			}
			if len(relevant) == 0 {
				score = int(float64(score) * 100 / math.Log2(float64(len(text)+2)))
			}
			if score > 0 && len(text) <= classifierSectionBytes {
				matches = append(matches, passage{doctriage.Document{Path: page.Path, Content: text}, score})
			}
		}
	}
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].score > matches[j].score })
	var selected []doctriage.Document
	for _, m := range matches[:min(3, len(matches))] {
		selected = append(selected, m.doc)
	}
	return selected
}

// contentQuery weights terms by inverse page frequency and preserves mixed-case identifiers.
func contentQuery(description string, title, body []string, pages []doctriage.Document) ([]string, map[string]int) {
	weights := make(map[string]int)
	lowerPages := make([]string, len(pages))
	for i, page := range pages {
		lowerPages[i] = strings.ToLower(page.Content)
	}
	// Keep identifiers such as TraceMe and DEBUG intact alongside prose search terms.
	seen := map[string]bool{}
	for _, term := range body {
		seen[term] = true
	}
	for _, token := range words.FindAllString(description, -1) {
		if token[1:] != strings.ToLower(token[1:]) {
			term := strings.ToLower(token)
			if !seen[term] {
				body = append(body, term)
				seen[term] = true
			}
			weights[term] = 5
		}
	}
	for _, term := range title {
		seen[term] = true
	}
	for term := range seen {
		count := 0
		for _, page := range lowerPages {
			if strings.Contains(page, term) {
				count++
			}
		}
		weights[term] = (1 + weights[term]) * len(pages) / max(1, count)
	}
	return body, weights
}

const classifierSectionBytes = 16 * 1024

func markdownSections(content string) []string {
	var sections []string
	start, offset := 0, 0
	var fence string
	for line := range strings.SplitAfterSeq(content, "\n") {
		trimmed := strings.TrimSpace(line)
		if fence != "" {
			if strings.HasPrefix(trimmed, fence) && strings.Trim(trimmed, fence[:1]) == "" {
				fence = ""
			}
		} else if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
			fence = trimmed[:len(trimmed)-len(strings.TrimLeft(trimmed, trimmed[:1]))]
		} else {
			rest := strings.TrimLeft(line, "#")
			level := len(line) - len(rest)
			if level >= 1 && level <= 6 && strings.HasPrefix(rest, " ") && offset > start {
				sections = append(sections, content[start:offset])
				start = offset
			}
		}
		offset += len(line)
	}
	return append(sections, content[start:])
}
