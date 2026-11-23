package jev

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const DefaultModel = "typesafe/jev-1.14"
const defaultEndpoint = "https://openrouter.ai/api/alpha/decisions"
const maxResponseBytes = 2 << 20

var modelPattern = regexp.MustCompile(`^typesafe/jev-[0-9]+\.[0-9]+$`)

type Request struct {
	Model     string              `json:"model"`
	State     any                 `json:"state"`
	Questions map[string]Question `json:"questions"`
}
type Question struct {
	Type         string            `json:"type"`
	Instructions string            `json:"instructions"`
	Criteria     map[string]string `json:"criteria"`
}
type Answer struct {
	Type          string             `json:"type"`
	Choice        string             `json:"choice"`
	Confidence    *float64           `json:"confidence,omitempty"`
	Probabilities map[string]float64 `json:"probabilities"`
}

func (a *Answer) UnmarshalJSON(data []byte) error {
	var wire struct {
		Type          string              `json:"type"`
		Choice        string              `json:"choice"`
		Confidence    *float64            `json:"confidence"`
		Probabilities map[string]*float64 `json:"probabilities"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	result := Answer{Type: wire.Type, Choice: wire.Choice, Confidence: wire.Confidence, Probabilities: make(map[string]float64, len(wire.Probabilities))}
	for key, value := range wire.Probabilities {
		if value == nil {
			return errors.New("probabilities must be numeric, not null")
		}
		result.Probabilities[key] = *value
	}
	*a = result
	return nil
}

type Usage struct {
	InputTokens  *int64   `json:"input_tokens,omitempty"`
	OutputTokens *int64   `json:"output_tokens,omitempty"`
	Cost         *float64 `json:"cost,omitempty"`
}
type Response struct {
	ID       string            `json:"id"`
	Model    string            `json:"model"`
	Provider string            `json:"provider"`
	Answers  map[string]Answer `json:"answers"`
	Usage    *Usage            `json:"usage,omitempty"`
}
type Options struct {
	HTTPClient *http.Client
	Endpoint   string
}
type Client struct {
	token      string
	endpoint   string
	httpClient *http.Client
}
type APIError struct {
	StatusCode int
	RequestID  string
	RetryAfter string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("OpenRouter Decisions request failed (HTTP %d)", e.StatusCode)
}

func NewClient(token string, options Options) (*Client, error) {
	if strings.TrimSpace(token) == "" {
		return nil, errors.New("OpenRouter API token is required")
	}
	endpoint := options.Endpoint
	if endpoint == "" {
		endpoint = defaultEndpoint
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("invalid Decisions endpoint")
	}
	local := u.Hostname() == "localhost" || net.ParseIP(u.Hostname()) != nil && net.ParseIP(u.Hostname()).IsLoopback()
	if endpoint != defaultEndpoint && !(options.HTTPClient != nil && local && (u.Scheme == "http" || u.Scheme == "https")) {
		return nil, errors.New("Decisions endpoint must be OpenRouter or a local test server")
	}
	hc := http.Client{Timeout: 60 * time.Second}
	if options.HTTPClient != nil {
		hc = *options.HTTPClient
		if hc.Timeout == 0 {
			hc.Timeout = 60 * time.Second
		}
	}
	hc.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &Client{token: token, endpoint: endpoint, httpClient: &hc}, nil
}

func (c *Client) Classify(ctx context.Context, request Request) (Response, error) {
	var result Response
	if err := validateRequest(request); err != nil {
		return result, err
	}
	body, err := json.Marshal(request)
	if err != nil {
		return result, errors.New("cannot encode Decisions request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return result, errors.New("cannot create Decisions request")
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return result, ctx.Err()
		}
		return result, errors.New("Decisions request transport failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return result, &APIError{StatusCode: resp.StatusCode, RequestID: resp.Header.Get("X-Request-ID"), RetryAfter: resp.Header.Get("Retry-After")}
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if err != nil {
		return result, errors.New("cannot read Decisions response")
	}
	if len(data) > maxResponseBytes {
		return result, errors.New("Decisions response exceeds size limit")
	}
	if json.Unmarshal(data, &result) != nil {
		return Response{}, errors.New("invalid Decisions response JSON")
	}
	if err := validateResponse(request, result); err != nil {
		return Response{}, err
	}
	return result, nil
}
func validateRequest(r Request) error {
	if !modelPattern.MatchString(r.Model) {
		return errors.New("an explicit typesafe/jev model version is required")
	}
	if len(r.Questions) == 0 {
		return errors.New("Decisions request requires questions")
	}
	for key, q := range r.Questions {
		if strings.TrimSpace(key) == "" || q.Type != "choice" || len(q.Criteria) == 0 {
			return errors.New("invalid Decisions choice question")
		}
		for option := range q.Criteria {
			if strings.TrimSpace(option) == "" {
				return errors.New("Decisions choice keys must not be empty")
			}
		}
	}
	return nil
}
func probability(v float64) bool { return !math.IsNaN(v) && !math.IsInf(v, 0) && v >= 0 && v <= 1 }
func ValidateResponse(req Request, r Response) error {
	return validateResponse(req, r)
}

func validateResponse(req Request, r Response) error {
	if r.ID == "" || r.Model == "" || len(r.Answers) != len(req.Questions) {
		return errors.New("incomplete Decisions response")
	}
	for key, q := range req.Questions {
		a, ok := r.Answers[key]
		if !ok || a.Type != "choice" || len(a.Probabilities) != len(q.Criteria) {
			return errors.New("Decisions response questions do not match request")
		}
		if _, ok := q.Criteria[a.Choice]; !ok {
			return errors.New("Decisions response contains an unknown choice")
		}
		sum := 0.0
		for option := range q.Criteria {
			p, ok := a.Probabilities[option]
			if !ok || !probability(p) {
				return errors.New("invalid Decisions probability distribution")
			}
			sum += p
		}
		if math.Abs(sum-1) > 1e-3 {
			return errors.New("Decisions probabilities must sum to one")
		}
		if a.Confidence != nil && !probability(*a.Confidence) {
			return errors.New("invalid Decisions confidence")
		}
	}
	if u := r.Usage; u != nil {
		if u.InputTokens != nil && *u.InputTokens < 0 || u.OutputTokens != nil && *u.OutputTokens < 0 || u.Cost != nil && (math.IsNaN(*u.Cost) || math.IsInf(*u.Cost, 0) || *u.Cost < 0) {
			return errors.New("invalid Decisions usage")
		}
	}
	return nil
}
