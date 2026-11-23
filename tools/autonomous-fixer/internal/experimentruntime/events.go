package experimentruntime

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strconv"
)

type TokenUsage struct {
	Input           *int64 `json:"input_tokens"`
	CachedInput     *int64 `json:"cached_input_tokens"`
	Output          *int64 `json:"output_tokens"`
	ReasoningOutput *int64 `json:"reasoning_output_tokens"`
}

type ErrorEvent struct {
	Type      string `json:"type"`
	Message   string `json:"message"`
	Recovered bool   `json:"recovered"`
	Sequence  int    `json:"sequence"`
}

type EventReport struct {
	CompletionValid               bool
	ErrorEvents                   []ErrorEvent
	CompactionObservability       string
	ContextOccupancyObservability string
	CompactionCount               *int
	PeakContextTokens             *int64
	Outcome                       string
	TerminalObserved              bool
	FinalObserved                 bool
	TextObserved                  bool
	HasError                      bool // Reconnects followed by valid completion are recorded as recovered.
	UnknownTypes                  []string
	MalformedLines                int
	Usage                         *TokenUsage
	StepUsage                     []json.RawMessage
}

// ParseEvents describes runtime evidence, not task correctness. OpenCode 2.2.5 does not
// emit its session.execution.succeeded event in run --format json output.
func ParseEvents(reader io.Reader, runtime string) (EventReport, error) {
	r := EventReport{Outcome: "unverified", CompactionObservability: "unavailable_from_cli_events", ContextOccupancyObservability: "unavailable_from_cli_events"}
	if runtime != "codex" && runtime != "opencode" {
		return r, fmt.Errorf("unsupported runtime %q", runtime)
	}
	decoder := bufio.NewReader(reader)
	unknown := map[string]bool{}
	sequence, lastFinal, lastCompleted, boundary, activity := 0, 0, 0, 0, 0
	fatal := false
	for {
		line, readErr := decoder.ReadBytes('\n')
		if len(line) > 0 {
			sequence++
			var event struct {
				Type    string `json:"type"`
				Message string `json:"message"`
				Error   struct {
					Message string `json:"message"`
				} `json:"error"`
				Usage *TokenUsage `json:"usage"`
				Item  struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"item"`
				Part struct {
					Type   string          `json:"type"`
					Text   string          `json:"text"`
					Reason string          `json:"reason"`
					Tokens json.RawMessage `json:"tokens"`
				} `json:"part"`
			}
			if err := json.Unmarshal(line, &event); err != nil || event.Type == "" {
				r.MalformedLines++
			} else {
				known := true
				if runtime == "codex" {
					switch event.Type {
					case "turn.completed":
						r.TerminalObserved = true
						r.Usage = event.Usage
						lastCompleted = sequence
					case "turn.failed", "error":
						message := event.Message
						if message == "" {
							message = event.Error.Message
						}
						r.ErrorEvents = append(r.ErrorEvents, ErrorEvent{Type: event.Type, Message: message, Sequence: sequence})
						if event.Type == "turn.failed" {
							r.TerminalObserved = true
						}
						if event.Type == "turn.failed" || !isReconnect(message) {
							fatal = true
						}
						boundary, activity = sequence, sequence
					case "item.completed":
						activity = sequence
						if event.Item.Type == "agent_message" && event.Item.Text != "" {
							r.FinalObserved = true
							lastFinal = sequence
						}
					case "thread.started", "turn.started":
						boundary, activity = sequence, sequence
					case "item.started", "item.updated":
						activity = sequence
					default:
						known = false
					}
				} else {
					switch event.Type {
					case "error":
						r.HasError = true
					case "text":
						if event.Part.Type == "text" && event.Part.Text != "" {
							r.TextObserved = true
						}
					case "step_finish":
						if len(event.Part.Tokens) > 0 && string(event.Part.Tokens) != "null" {
							r.StepUsage = append(r.StepUsage, append(json.RawMessage(nil), event.Part.Tokens...))
						}
					case "step_start", "reasoning", "tool_use", "background_wait", "retry":
					default:
						known = false
					}
				}
				if !known && !unknown[event.Type] {
					unknown[event.Type] = true
					r.UnknownTypes = append(r.UnknownTypes, event.Type)
				}
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				return r, readErr
			}
			break
		}
	}
	if runtime == "codex" {
		completed := lastFinal > boundary && lastCompleted > lastFinal && lastCompleted > activity
		for i := range r.ErrorEvents {
			r.ErrorEvents[i].Recovered = !fatal && completed && r.ErrorEvents[i].Type == "error" && isReconnect(r.ErrorEvents[i].Message) && lastFinal > r.ErrorEvents[i].Sequence
			if !r.ErrorEvents[i].Recovered {
				r.HasError = true
			}
		}
		r.CompletionValid = completed && !r.HasError && r.MalformedLines == 0 && len(r.UnknownTypes) == 0
	} else {
		r.CompletionValid = r.TextObserved && !r.HasError && r.MalformedLines == 0 && len(r.UnknownTypes) == 0
	}
	if r.HasError {
		r.Outcome = "runtime_error"
	} else if runtime == "codex" && r.CompletionValid {
		r.Outcome = "runtime_completed_unverified"
	}
	return r, nil
}

var reconnectMessage = regexp.MustCompile(`^Reconnecting\.\.\. ([1-9][0-9]*)/([1-9][0-9]*) \(.+\)$`)

func isReconnect(message string) bool {
	match := reconnectMessage.FindStringSubmatch(message)
	if match == nil {
		return false
	}
	attempt, err := strconv.Atoi(match[1])
	if err != nil {
		return false
	}
	limit, err := strconv.Atoi(match[2])
	return err == nil && attempt <= limit
}
