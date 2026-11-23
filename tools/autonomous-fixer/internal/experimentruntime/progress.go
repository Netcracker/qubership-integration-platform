package experimentruntime

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type ProgressOptions struct {
	Command           string
	Runtime           string
	Stage             string
	LatestPath        string
	HeartbeatInterval time.Duration
}

type CommandProgress struct {
	ID            string     `json:"id"`
	Command       string     `json:"command"`
	StartedAt     time.Time  `json:"started_at"`
	FinishedAt    *time.Time `json:"finished_at,omitempty"`
	Status        string     `json:"status"`
	ExitCode      *int       `json:"exit_code,omitempty"`
	OutputExcerpt string     `json:"output_excerpt,omitempty"`
}

type Progress struct {
	Runtime            string            `json:"runtime"`
	Stage              string            `json:"stage"`
	PID                int               `json:"pid"`
	Status             string            `json:"status"`
	ObservedAt         time.Time         `json:"observed_at"`
	StartedAt          time.Time         `json:"started_at"`
	LastEventAt        *time.Time        `json:"last_event_at,omitempty"`
	LastEventType      string            `json:"last_event_type,omitempty"`
	LastStdoutAt       *time.Time        `json:"last_stdout_at,omitempty"`
	LastStderrAt       *time.Time        `json:"last_stderr_at,omitempty"`
	IdleSeconds        float64           `json:"idle_seconds"`
	OutputIdleSeconds  float64           `json:"output_idle_seconds"`
	WallElapsedSeconds float64           `json:"wall_elapsed_seconds"`
	EventCount         int               `json:"event_count"`
	MalformedLines     int               `json:"malformed_lines"`
	StdoutBytes        int64             `json:"stdout_bytes"`
	StderrBytes        int64             `json:"stderr_bytes"`
	ActiveCommands     []CommandProgress `json:"active_commands"`
	LastCommand        *CommandProgress  `json:"last_command,omitempty"`
	ProcessActivity    ProcessActivity   `json:"process_activity"`
}

type progressRecorder struct {
	mu        sync.Mutex
	options   ProgressOptions
	state     Progress
	pending   []byte
	commands  map[string]CommandProgress
	log       *os.File
	directory string
	err       error
	stop      chan struct{}
	done      chan struct{}
}

func newProgressRecorder(directory string, options ProgressOptions) (*progressRecorder, error) {
	if options.Runtime != "codex" && options.Runtime != "opencode" && options.Runtime != "command" {
		return nil, fmt.Errorf("progress requires a supported runtime or command")
	}
	if options.LatestPath != "" && !filepath.IsAbs(options.LatestPath) {
		return nil, fmt.Errorf("latest progress path must be absolute")
	}
	log, err := os.OpenFile(filepath.Join(directory, "progress.jsonl"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	if options.HeartbeatInterval <= 0 {
		options.HeartbeatInterval = 30 * time.Second
	}
	now := time.Now().UTC()
	p := &progressRecorder{options: options, state: Progress{Runtime: options.Runtime, Stage: options.Stage, Status: "starting", StartedAt: now}, commands: map[string]CommandProgress{}, log: log, directory: directory, stop: make(chan struct{}), done: make(chan struct{})}
	if options.Runtime == "command" {
		p.commands["process"] = CommandProgress{ID: "process", Command: options.Command, StartedAt: now, Status: "starting"}
	}
	p.save(now)
	if p.err != nil {
		log.Close()
		return nil, p.err
	}
	go func() {
		defer close(p.done)
		ticker := time.NewTicker(options.HeartbeatInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				p.mu.Lock()
				p.save(time.Now().UTC())
				p.mu.Unlock()
			case <-p.stop:
				return
			}
		}
	}()
	return p, nil
}

func (p *progressRecorder) started(pid int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.state.PID = pid
	p.state.ProcessActivity = processActivity(pid, ProcessActivity{})
	p.state.Status = "running"
	now := time.Now().UTC()
	if p.options.Runtime == "command" {
		command := p.commands["process"]
		command.Status = "in_progress"
		command.StartedAt = now
		p.commands["process"] = command
		p.state.EventCount++
		p.state.LastEventAt = &now
		p.state.LastEventType = "command.started"
	}
	p.save(now)
}

func (p *progressRecorder) finish(status string, exitCode int) error {
	close(p.stop)
	<-p.done
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.pending) > 0 {
		p.event(p.pending, time.Now().UTC())
		p.pending = nil
	}
	p.state.Status = status
	if p.options.Runtime == "command" {
		now := time.Now().UTC()
		command := p.commands["process"]
		command.FinishedAt = &now
		command.ExitCode = &exitCode
		command.Status = "failed"
		if status == "completed_unverified" && exitCode == 0 {
			command.Status = "completed"
		}
		if status == "canceled" || status == "start_error" {
			command.Status = status
		}
		p.state.LastCommand = &command
		delete(p.commands, "process")
		p.state.EventCount++
		p.state.LastEventAt = &now
		p.state.LastEventType = "command.completed"
	}
	p.state.ProcessActivity = processActivity(p.state.PID, p.state.ProcessActivity)
	p.save(time.Now().UTC())
	closeErr := p.log.Close()
	if p.err != nil {
		return p.err
	}
	return closeErr
}

type progressWriter struct {
	recorder *progressRecorder
	stream   string
}

func (w progressWriter) Write(data []byte) (int, error) {
	p := w.recorder
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now().UTC()
	if w.stream == "stderr" {
		p.state.LastStderrAt = &now
		p.state.StderrBytes += int64(len(data))
	} else {
		p.state.LastStdoutAt = &now
		p.state.StdoutBytes += int64(len(data))
		if p.options.Runtime != "command" {
			p.pending = append(p.pending, data...)
		}
		for {
			i := bytes.IndexByte(p.pending, '\n')
			if i < 0 {
				break
			}
			p.event(p.pending[:i], now)
			p.pending = p.pending[i+1:]
		}
		if len(p.pending) == 0 {
			p.pending = nil
		}
	}
	if p.options.Runtime == "command" {
		p.state.EventCount++
		p.state.LastEventAt = &now
		p.state.LastEventType = w.stream + ".received"
		command := p.commands["process"]
		command.OutputExcerpt = tail(command.OutputExcerpt+string(data), 4000)
		p.commands["process"] = command
	}
	p.save(now)
	// Preserve runtime output even if the progress destination becomes unavailable.
	return len(data), nil
}

func (p *progressRecorder) event(line []byte, now time.Time) {
	var e struct {
		Type string `json:"type"`
		Item struct {
			ID       string `json:"id"`
			Type     string `json:"type"`
			Command  string `json:"command"`
			Status   string `json:"status"`
			ExitCode *int   `json:"exit_code"`
			Output   string `json:"aggregated_output"`
		} `json:"item"`
		Part struct {
			Type   string `json:"type"`
			CallID string `json:"callID"`
			Tool   string `json:"tool"`
			State  struct {
				Status string `json:"status"`
				Input  struct {
					Command string `json:"command"`
				} `json:"input"`
				Output string `json:"output"`
			} `json:"state"`
		} `json:"part"`
	}
	if json.Unmarshal(line, &e) != nil || e.Type == "" {
		p.state.MalformedLines++
		return
	}
	p.state.EventCount++
	p.state.LastEventAt = &now
	p.state.LastEventType = e.Type
	id, command, status, output, exitCode := e.Item.ID, e.Item.Command, e.Item.Status, e.Item.Output, e.Item.ExitCode
	isCommand := p.options.Runtime == "codex" && e.Item.Type == "command_execution"
	if p.options.Runtime == "opencode" && e.Type == "tool_use" && e.Part.Tool == "bash" {
		isCommand = true
		id, command, status, output = e.Part.CallID, e.Part.State.Input.Command, e.Part.State.Status, e.Part.State.Output
	}
	if !isCommand || id == "" {
		return
	}
	previous, exists := p.commands[id]
	if !exists {
		previous = CommandProgress{ID: id, Command: command, StartedAt: now}
	}
	if command != "" {
		previous.Command = command
	}
	previous.Status = status
	previous.ExitCode = exitCode
	previous.OutputExcerpt = tail(output, 4000)
	if e.Type == "item.completed" || status == "completed" || status == "error" || status == "failed" {
		previous.FinishedAt = &now
		p.state.LastCommand = &previous
		delete(p.commands, id)
	} else {
		p.commands[id] = previous
	}
}

func (p *progressRecorder) save(now time.Time) {
	p.state.ObservedAt = now
	idleSince, outputSince := p.state.StartedAt, p.state.StartedAt
	if p.state.LastEventAt != nil {
		idleSince = *p.state.LastEventAt
	}
	for _, timestamp := range []*time.Time{p.state.LastStdoutAt, p.state.LastStderrAt} {
		if timestamp != nil && timestamp.After(outputSince) {
			outputSince = *timestamp
		}
	}
	p.state.IdleSeconds = nonnegative(now.Sub(idleSince).Seconds())
	p.state.OutputIdleSeconds = nonnegative(now.Sub(outputSince).Seconds())
	p.state.WallElapsedSeconds = nonnegative(now.Sub(p.state.StartedAt).Seconds())
	p.state.ActiveCommands = nil
	ids := make([]string, 0, len(p.commands))
	for id := range p.commands {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		p.state.ActiveCommands = append(p.state.ActiveCommands, p.commands[id])
	}
	if p.state.ProcessActivity.SampledAt.IsZero() || now.Sub(p.state.ProcessActivity.SampledAt) >= p.options.HeartbeatInterval {
		p.state.ProcessActivity = processActivity(p.state.PID, p.state.ProcessActivity)
	}
	data, err := json.Marshal(p.state)
	if err == nil {
		_, err = p.log.Write(append(data, '\n'))
	}
	if err == nil {
		err = atomicProgress(filepath.Join(p.directory, "progress.json"), data)
	}
	if err == nil && p.options.LatestPath != "" {
		err = atomicProgress(p.options.LatestPath, data)
	}
	if p.err == nil {
		p.err = err
	}
}

func atomicProgress(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".progress-*")
	if err != nil {
		return err
	}
	temporary := f.Name()
	defer os.Remove(temporary)
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

func nonnegative(value float64) float64 {
	if value < 0 {
		return 0
	}
	return value
}
func tail(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return strings.ToValidUTF8(value[len(value)-limit:], "")
}

var _ io.Writer = progressWriter{}
