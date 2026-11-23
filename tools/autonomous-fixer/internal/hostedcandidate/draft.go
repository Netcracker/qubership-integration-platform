package hostedcandidate

import (
	"regexp"
	"slices"
	"strings"
	"unicode"
)

const verificationHeading = "How to verify"
const decisionLabel = "End-to-end decision:"

// All sections and the footer must fit within GitHub's body limit after escaping.
const draftSectionLimit = 2000

var draftHeadings = []string{"Why", "What", verificationHeading, "For the reviewer"}
var conventionalFixTitle = regexp.MustCompile(`^fix\([a-z][a-z0-9-]*(,[a-z][a-z0-9-]*)*\): \S`)

var draftText = strings.NewReplacer(
	"&", "&amp;", "<", "&lt;", ">", "&gt;", "@", "＠", "#", "＃",
	":", "&#58;", ".", "\\.", "\\", "\\\\", "`", "\\`",
	"[", "\\[", "]", "\\]", "*", "\\*", "_", "\\_", "!", "\\!", "|", "\\|", "~", "\\~",
	"-", "\\-", "+", "\\+", "=", "\\=",
)

// The publisher places literal commands in code blocks to retain copyable syntax.
var commandText = strings.NewReplacer(
	"&", "&amp;", "<", "&lt;", ">", "&gt;", "@", "&#64;", "#", "&#35;", ":", "&#58;",
)

func draftFromAuthor(summary, issueTitle string) (title, body string) {
	lines := strings.Split(strings.TrimSpace(summary), "\n")
	start, authorTitle := len(lines), ""
	for i := len(lines) - 1; i >= 0; i-- {
		if value, ok := strings.CutPrefix(strings.TrimSpace(lines[i]), "PR title: "); ok {
			start, authorTitle = i+1, value
			break
		}
	}
	sections := make(map[string][]string, len(draftHeadings))
	section, decision := "", ""
	for i, line := range lines[start:] {
		line = strings.TrimSpace(line)
		if start+i == len(lines)-1 && strings.EqualFold(line, "Outcome: fixed") {
			break
		}
		if heading, ok := strings.CutPrefix(line, "## "); ok {
			section = heading
			continue
		}
		if !slices.Contains(draftHeadings, section) {
			continue
		}
		if section == verificationHeading {
			if value, ok := strings.CutPrefix(line, decisionLabel); ok {
				decision = strings.TrimSpace(value)
				continue
			}
		}
		sections[section] = append(sections[section], line)
	}

	if decision == "" {
		decision = "The author report did not record an end-to-end decision."
	}
	var blocks []string
	for _, heading := range draftHeadings {
		content := renderDraftSection(strings.Join(sections[heading], "\n"), heading == verificationHeading)
		if heading == verificationHeading {
			if content == "" {
				content = "The author report did not provide reviewer verification steps."
			}
			content += "\n\n**" + decisionLabel + "** " + draftText.Replace(limitDraftText(decision))
		}
		if content != "" {
			blocks = append(blocks, "## "+heading+"\n\n"+content)
		}
	}
	return draftTitle(authorTitle, issueTitle), strings.Join(blocks, "\n\n")
}

func renderDraftSection(content string, allowCommands bool) string {
	lines := strings.Split(limitDraftText(strings.TrimSpace(content)), "\n")
	for i, line := range lines {
		if command, ok := strings.CutPrefix(line, "Command: "); ok && allowCommands {
			lines[i] = "\n<pre><code>" + commandText.Replace(command) + "</code></pre>\n"
		} else if bullet, ok := strings.CutPrefix(line, "- "); ok {
			lines[i] = "- " + draftText.Replace(bullet)
		} else {
			lines[i] = draftText.Replace(line)
		}
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

func limitDraftText(content string) string {
	runes := []rune(content)
	if len(runes) <= draftSectionLimit {
		return content
	}
	return string(runes[:draftSectionLimit]) + "…"
}

func draftTitle(authorTitle, issueTitle string) string {
	title := strings.Join(strings.Fields(authorTitle), " ")
	if !conventionalFixTitle.MatchString(title) || len([]rune(title)) > 95 {
		title = "fix: " + strings.Join(strings.Fields(issueTitle), " ")
	}
	title = strings.Map(func(r rune) rune {
		switch r {
		case '@':
			return '＠'
		case '#':
			return '＃'
		}
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, title)
	runes := []rune(title)
	return strings.TrimSpace(string(runes[:min(len(runes), 95)]))
}
