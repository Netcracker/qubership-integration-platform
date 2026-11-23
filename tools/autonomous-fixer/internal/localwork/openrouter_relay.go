package localwork

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentruntime"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/openrouterusage"
)

func startOpenRouterRelay(key string, meter *openrouterusage.Recorder, profiles ...experimentruntime.Profile) (string, func(), error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", nil, fmt.Errorf("start the OpenRouter relay: %w", err)
	}
	upstream := &url.URL{Scheme: "https", Host: "openrouter.ai"}
	server := &http.Server{
		Handler:           openRouterRelay(upstream, key, profiles, meter),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go server.Serve(listener)
	return "http://" + listener.Addr().String() + "/api/v1", func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			_ = server.Close()
		}
	}, nil
}

func openRouterRelay(upstream *url.URL, key string, profiles []experimentruntime.Profile, meter *openrouterusage.Recorder) http.Handler {
	models := make(map[string]bool, len(profiles))
	for _, profile := range profiles {
		models[strings.TrimPrefix(profile.Model, "openrouter/")] = true
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.RawQuery != "" || r.URL.Path != "/api/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+experimentruntime.OpenRouterRelayKey {
			http.Error(w, "OpenRouter relay authorization is required.", http.StatusUnauthorized)
			return
		}
		var request map[string]json.RawMessage
		var model string
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil || json.Unmarshal(request["model"], &model) != nil {
			http.Error(w, "Send a model request as JSON.", http.StatusBadRequest)
			return
		}
		if !models[model] || request["models"] != nil {
			http.Error(w, "The OpenRouter relay accepts the configured models.", http.StatusForbidden)
			return
		}
		request["usage"] = json.RawMessage(`{"include":true}`)
		var stream bool
		if json.Unmarshal(request["stream"], &stream) == nil && stream {
			setStreamUsage(request)
		}
		payload, err := json.Marshal(request)
		if err != nil {
			http.Error(w, "The OpenRouter relay could not encode the model request.", http.StatusBadRequest)
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(payload))
		r.ContentLength = int64(len(payload))
		r.Header.Del("Content-Length")
		finish := meter.Begin()
		var generation openrouterusage.Generation
		if key == "" || !strings.Contains(model, key) {
			generation.RequestedModel = model
		}
		defer func() { finish(generation) }()
		proxy := &httputil.ReverseProxy{
			Rewrite: func(r *httputil.ProxyRequest) {
				r.SetURL(upstream)
				r.Out.Header.Set("Authorization", "Bearer "+key)
				r.Out.Header.Set("Accept-Encoding", "identity")
			},
			ModifyResponse: func(response *http.Response) error {
				response.Body = openrouterusage.Observe(response.Body, response.Header.Get("Content-Type"), key, &generation)
				return nil
			},
		}
		proxy.ServeHTTP(w, r)
	})
}

func setStreamUsage(request map[string]json.RawMessage) {
	var options map[string]json.RawMessage
	if value := request["stream_options"]; value != nil && json.Unmarshal(value, &options) != nil {
		return
	}
	if options == nil {
		options = make(map[string]json.RawMessage)
	}
	options["include_usage"] = json.RawMessage("true")
	request["stream_options"], _ = json.Marshal(options)
}
