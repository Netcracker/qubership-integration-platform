package jev

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const validResponse = `{"id":"decision-1","model":"typesafe/jev-1.14-20261001","provider":"Typesafe","answers":{"route":{"type":"choice","choice":"fix","probabilities":{"fix":0.8,"skip":0.2}}}}`

func testRequest() Request {
	return Request{Model: DefaultModel, State: map[string]any{"issue": "example"}, Questions: map[string]Question{"route": {Type: "choice", Instructions: "Choose a route", Criteria: map[string]string{"fix": "Repair it", "skip": "Leave it"}}}}
}
func testClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := NewClient("private-test-token", Options{Endpoint: server.URL, HTTPClient: server.Client()})
	if err != nil {
		t.Fatal(err)
	}
	return client
}
func TestClassifyWire(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.Header.Get("Authorization") != "Bearer private-test-token" || r.Header.Get("Content-Type") != "application/json" {
			t.Error("incorrect wire request")
		}
		var got Request
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Error(err)
		}
		if got.Model != DefaultModel || got.Questions["route"].Criteria["fix"] != "Repair it" {
			t.Errorf("unexpected request: %#v", got)
		}
		fmt.Fprint(w, validResponse)
	})
	result, err := client.Classify(context.Background(), testRequest())
	if err != nil {
		t.Fatal(err)
	}
	if result.Usage != nil || result.Answers["route"].Confidence != nil {
		t.Fatal("absent fields were populated")
	}
}
func TestAPIErrors(t *testing.T) {
	for _, status := range []int{400, 401, 402, 429, 500, 503} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			calls := 0
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.Header().Set("X-Request-ID", "request-1")
				w.Header().Set("Retry-After", "30")
				w.WriteHeader(status)
				fmt.Fprint(w, "private-test-token secret response")
			})
			_, err := client.Classify(context.Background(), testRequest())
			var apiErr *APIError
			if !errors.As(err, &apiErr) || apiErr.StatusCode != status || apiErr.RequestID != "request-1" || apiErr.RetryAfter != "30" || calls != 1 {
				t.Fatalf("unexpected error: %v", err)
			}
			if strings.Contains(err.Error(), "private-test-token") || strings.Contains(err.Error(), "secret") {
				t.Fatal("error exposed response data")
			}
		})
	}
}
func TestRejectInvalidResponses(t *testing.T) {
	cases := map[string]string{
		"missing answers":      `{"id":"1","model":"m","answers":{}}`,
		"unknown choice":       strings.Replace(validResponse, `"choice":"fix"`, `"choice":"other"`, 1),
		"missing option":       strings.Replace(validResponse, `,"skip":0.2`, "", 1),
		"extra option":         strings.Replace(validResponse, `"skip":0.2`, `"skip":0.1,"other":0.1`, 1),
		"invalid sum":          strings.Replace(validResponse, `0.8`, `0.7`, 1),
		"negative probability": strings.Replace(validResponse, `0.8`, `-0.8`, 1),
		"wrong type":           strings.Replace(validResponse, `"type":"choice"`, `"type":"boolean"`, 1),
		"negative usage":       strings.Replace(validResponse, `"provider":"Typesafe"`, `"usage":{"input_tokens":-1}`, 1),
		"invalid JSON":         `not JSON private-test-token`,
		"oversized":            strings.Repeat("x", maxResponseBytes+1),
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, body) })
			if _, err := client.Classify(context.Background(), testRequest()); err == nil {
				t.Fatal("accepted invalid response")
			}
		})
	}
}
func TestUsageAndConfidence(t *testing.T) {
	body := strings.Replace(validResponse, `"provider":"Typesafe"`, `"usage":{"input_tokens":0,"cost":0}`, 1)
	body = strings.Replace(body, `"choice":"fix"`, `"choice":"fix","confidence":0.9`, 1)
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, body) })
	result, err := client.Classify(context.Background(), testRequest())
	if err != nil {
		t.Fatal(err)
	}
	if result.Usage.InputTokens == nil || *result.Usage.InputTokens != 0 || result.Usage.OutputTokens != nil || result.Usage.Cost == nil || *result.Answers["route"].Confidence != 0.9 {
		t.Fatal("usage or confidence changed")
	}
}
func TestRedirectDoesNotSendToken(t *testing.T) {
	forwarded := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forwarded = true }))
	defer target.Close()
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusTemporaryRedirect)
	})
	_, err := client.Classify(context.Background(), testRequest())
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.StatusCode != 307 || forwarded {
		t.Fatalf("redirect followed: %v", err)
	}
}
func TestCancelledContext(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) { t.Error("cancelled request reached server") })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := client.Classify(ctx, testRequest())
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("unexpected error: %v", err)
	}
}
func TestValidateRequest(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) { t.Error("invalid request reached server") })
	for _, model := range []string{"", "openai/gpt-4", "typesafe/jev-latest"} {
		req := testRequest()
		req.Model = model
		if _, err := client.Classify(context.Background(), req); err == nil {
			t.Fatal("accepted invalid model")
		}
	}
	req := testRequest()
	req.Questions["route"].Criteria[""] = "empty"
	if _, err := client.Classify(context.Background(), req); err == nil {
		t.Fatal("accepted empty option")
	}
	if _, err := NewClient("token", Options{Endpoint: "https://example.com"}); err == nil {
		t.Fatal("accepted arbitrary endpoint")
	}
}
