package openrouterusage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"mime"
	"regexp"
	"strings"
)

const maxFrameBytes = 2 << 20

var modelName = regexp.MustCompile(`^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_./:-]+$`)

type responseReader struct {
	io.ReadCloser
	generation    *Generation
	secret        string
	stream        bool
	line          []byte
	event         []byte
	discard       bool
	lineTruncated bool
}

func Observe(body io.ReadCloser, contentType, secret string, generation *Generation) io.ReadCloser {
	media, _, _ := mime.ParseMediaType(contentType)
	if media != "text/event-stream" && media != "application/json" {
		return body
	}
	return &responseReader{ReadCloser: body, generation: generation, secret: secret, stream: media == "text/event-stream"}
}

func (r *responseReader) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	if r.stream {
		r.readStream(p[:n])
	} else if !r.discard {
		if len(r.event)+n <= maxFrameBytes {
			r.event = append(r.event, p[:n]...)
		} else {
			r.event = nil
			r.discard = true
		}
	}
	if err == io.EOF && !r.discard {
		if r.stream && len(r.line) > 0 {
			r.readLine()
		}
		r.observeJSON(r.event)
		r.event = nil
	}
	return n, err
}

func (r *responseReader) readStream(data []byte) {
	for len(data) > 0 {
		line, rest, found := bytes.Cut(data, []byte("\n"))
		if !r.lineTruncated && len(r.line)+len(line) <= maxFrameBytes {
			r.line = append(r.line, line...)
		} else {
			r.line = nil
			r.discard = true
			r.lineTruncated = true
		}
		if found {
			r.readLine()
		}
		data = rest
	}
}

func (r *responseReader) readLine() {
	if r.lineTruncated {
		r.lineTruncated = false
		return
	}
	line := bytes.TrimSuffix(r.line, []byte("\r"))
	switch {
	case len(line) == 0:
		r.finishEvent()
	case r.discard:
	case bytes.HasPrefix(line, []byte("data:")):
		data := bytes.TrimPrefix(line[len("data:"):], []byte(" "))
		if len(r.event)+len(data)+1 > maxFrameBytes {
			r.discard = true
		} else {
			if len(r.event) != 0 {
				r.event = append(r.event, '\n')
			}
			r.event = append(r.event, data...)
		}
	}
	r.line = nil
}

func (r *responseReader) finishEvent() {
	if !r.discard {
		r.observeJSON(r.event)
	}
	r.event = nil
	r.discard = false
}

func (r *responseReader) observeJSON(data []byte) {
	var payload struct {
		ID    string `json:"id"`
		Model string `json:"model"`
		Usage struct {
			InputTokens  *int64   `json:"prompt_tokens"`
			OutputTokens *int64   `json:"completion_tokens"`
			CostUSD      *float64 `json:"cost"`
			Prompt       struct {
				Cached *int64 `json:"cached_tokens"`
			} `json:"prompt_tokens_details"`
			Completion struct {
				Reasoning *int64 `json:"reasoning_tokens"`
			} `json:"completion_tokens_details"`
		} `json:"usage"`
	}
	if len(data) == 0 || json.Unmarshal(data, &payload) != nil {
		return
	}
	g := Generation{
		InputTokens:  payload.Usage.InputTokens,
		OutputTokens: payload.Usage.OutputTokens,
		CachedTokens: payload.Usage.Prompt.Cached,
		Reasoning:    payload.Usage.Completion.Reasoning,
		CostUSD:      payload.Usage.CostUSD,
	}
	if payload.ID != "" {
		id := sha256.Sum256([]byte(payload.ID))
		g.IDSHA256 = hex.EncodeToString(id[:])
	}
	if len(payload.Model) <= 256 && modelName.MatchString(payload.Model) && (r.secret == "" || !strings.Contains(payload.Model, r.secret)) {
		g.ServedModel = payload.Model
	}
	r.generation.merge(g)
}
