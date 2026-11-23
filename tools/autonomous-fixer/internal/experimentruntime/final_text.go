package experimentruntime

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"strings"
)

// FinalText returns the last recorded assistant message, not proof of task completion.
func FinalText(reader io.Reader, runtime string) (string, error) {
	if runtime != "codex" && runtime != "opencode" {
		return "", fmt.Errorf("unsupported runtime %q", runtime)
	}
	var text strings.Builder
	var messageID string
	lines := bufio.NewReader(reader)
	for {
		line, readErr := lines.ReadBytes('\n')
		if len(line) > 0 {
			var event struct {
				Type string `json:"type"`
				Item struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"item"`
				Part struct {
					Type      string `json:"type"`
					Text      string `json:"text"`
					MessageID string `json:"messageID"`
				} `json:"part"`
			}
			if err := json.Unmarshal(line, &event); err != nil {
				return "", fmt.Errorf("decode runtime event: %w", err)
			}
			if runtime == "codex" && event.Type == "item.completed" && event.Item.Type == "agent_message" {
				text.Reset()
				text.WriteString(event.Item.Text)
			}
			if runtime == "opencode" && event.Type == "text" && event.Part.Type == "text" {
				if event.Part.MessageID == "" {
					return "", fmt.Errorf("OpenCode text event has no assistant message ID")
				}
				if messageID != event.Part.MessageID {
					messageID = event.Part.MessageID
					text.Reset()
				}
				text.WriteString(event.Part.Text)
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				return "", readErr
			}
			break
		}
	}
	if strings.TrimSpace(text.String()) == "" {
		return "", fmt.Errorf("runtime events contain no assistant message text")
	}
	return text.String(), nil
}
