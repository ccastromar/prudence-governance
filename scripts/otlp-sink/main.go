// otlp-sink is the smallest local observability backend: an OTLP/HTTP receiver that keeps the
// spans and metrics AOS, Prudence and Ernest send, flattened to one JSON object per line, and
// answers simple queries about them. It stands in for the collector, Tempo and Prometheus on a
// machine without Docker (scripts/e2e-suite.sh checks the suite's observability with it); it
// is not a store: everything is lost when it stops.
//
//	go run ./scripts/otlp-sink -addr 127.0.0.1:4318 -dir /tmp/otlp
//	OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 ...   # AOS, Ernest
//	PRUDENCE_OTEL_ENABLED=true PRUDENCE_OTEL_ENDPOINT=http://127.0.0.1:4318 ...
//
// Receives POST /v1/traces, /v1/metrics and /v1/logs (protobuf or JSON). Writes spans.jsonl and
// metrics.jsonl in -dir. Answers GET /spans?trace_id=…, /spans?linked_trace_id=…,
// /spans?name=… and /metrics?name=… with the matching lines as a JSON array.
package main

import (
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	collogs "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	colmetrics "go.opentelemetry.io/proto/otlp/collector/metrics/v1"
	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	metricspb "go.opentelemetry.io/proto/otlp/metrics/v1"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// Span is one span as the sink keeps it.
type Span struct {
	Service      string            `json:"service"`
	Name         string            `json:"name"`
	TraceID      string            `json:"trace_id"`
	SpanID       string            `json:"span_id"`
	ParentSpanID string            `json:"parent_span_id,omitempty"`
	Kind         string            `json:"kind"`
	Links        []Link            `json:"links,omitempty"`
	Attributes   map[string]string `json:"attributes,omitempty"`
}

type Link struct {
	TraceID string `json:"trace_id"`
	SpanID  string `json:"span_id"`
}

// Point is one metric data point.
type Point struct {
	Service    string            `json:"service"`
	Name       string            `json:"name"`
	Unit       string            `json:"unit,omitempty"`
	Type       string            `json:"type"`
	Attributes map[string]string `json:"attributes,omitempty"`
	Value      float64           `json:"value,omitempty"`
	Count      uint64            `json:"count,omitempty"`
	Bounds     []float64         `json:"bounds,omitempty"`
}

type sink struct {
	mu      sync.Mutex
	spans   []Span
	points  []Point
	spanOut *os.File
	metOut  *os.File
}

func main() {
	addr := flag.String("addr", "127.0.0.1:4318", "listen address")
	dir := flag.String("dir", ".", "directory for spans.jsonl and metrics.jsonl")
	flag.Parse()
	if err := os.MkdirAll(*dir, 0o755); err != nil {
		log.Fatal(err)
	}
	s := &sink{}
	var err error
	if s.spanOut, err = createIn(*dir, "spans.jsonl"); err != nil {
		log.Fatal(err)
	}
	if s.metOut, err = createIn(*dir, "metrics.jsonl"); err != nil {
		log.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/traces", s.traces)
	mux.HandleFunc("POST /v1/metrics", s.metrics)
	mux.HandleFunc("POST /v1/logs", func(w http.ResponseWriter, r *http.Request) {
		var req collogs.ExportLogsServiceRequest
		if decode(w, r, &req) {
			reply(w, r, &collogs.ExportLogsServiceResponse{})
		}
	})
	mux.HandleFunc("GET /spans", s.querySpans)
	mux.HandleFunc("GET /metrics", s.queryMetrics)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })
	log.Printf("otlp-sink listening on %s, writing to %s", *addr, *dir)
	log.Fatal(http.ListenAndServe(*addr, mux))
}

func createIn(dir, name string) (*os.File, error) {
	return os.Create(filepath.Join(dir, name))
}

// decode reads an OTLP request in protobuf or JSON, as its Content-Type says.
func decode(w http.ResponseWriter, r *http.Request, msg proto.Message) bool {
	body, err := io.ReadAll(io.LimitReader(r.Body, 32<<20))
	if err == nil {
		if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			err = protojson.Unmarshal(body, msg)
		} else {
			err = proto.Unmarshal(body, msg)
		}
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return false
	}
	return true
}

func reply(w http.ResponseWriter, r *http.Request, msg proto.Message) {
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		w.Header().Set("Content-Type", "application/json")
		out, _ := protojson.Marshal(msg)
		_, _ = w.Write(out)
		return
	}
	w.Header().Set("Content-Type", "application/x-protobuf")
	out, _ := proto.Marshal(msg)
	_, _ = w.Write(out)
}

func (s *sink) traces(w http.ResponseWriter, r *http.Request) {
	var req coltrace.ExportTraceServiceRequest
	if !decode(w, r, &req) {
		return
	}
	var got []Span
	for _, rs := range req.ResourceSpans {
		service := attrs(rs.GetResource().GetAttributes())["service.name"]
		for _, ss := range rs.ScopeSpans {
			for _, sp := range ss.Spans {
				span := Span{
					Service:      service,
					Name:         sp.Name,
					TraceID:      hex.EncodeToString(sp.TraceId),
					SpanID:       hex.EncodeToString(sp.SpanId),
					ParentSpanID: hex.EncodeToString(sp.ParentSpanId),
					Kind:         strings.TrimPrefix(sp.Kind.String(), "SPAN_KIND_"),
					Attributes:   attrs(sp.Attributes),
				}
				for _, l := range sp.Links {
					span.Links = append(span.Links, Link{TraceID: hex.EncodeToString(l.TraceId), SpanID: hex.EncodeToString(l.SpanId)})
				}
				got = append(got, span)
			}
		}
	}
	s.mu.Lock()
	s.spans = append(s.spans, got...)
	enc := json.NewEncoder(s.spanOut)
	for _, sp := range got {
		_ = enc.Encode(sp)
	}
	s.mu.Unlock()
	reply(w, r, &coltrace.ExportTraceServiceResponse{})
}

func (s *sink) metrics(w http.ResponseWriter, r *http.Request) {
	var req colmetrics.ExportMetricsServiceRequest
	if !decode(w, r, &req) {
		return
	}
	var got []Point
	for _, rm := range req.ResourceMetrics {
		service := attrs(rm.GetResource().GetAttributes())["service.name"]
		for _, sm := range rm.ScopeMetrics {
			for _, m := range sm.Metrics {
				base := Point{Service: service, Name: m.Name, Unit: m.Unit}
				switch data := m.Data.(type) {
				case *metricspb.Metric_Sum:
					for _, dp := range data.Sum.DataPoints {
						p := base
						p.Type, p.Attributes, p.Value = "sum", attrs(dp.Attributes), number(dp)
						got = append(got, p)
					}
				case *metricspb.Metric_Gauge:
					for _, dp := range data.Gauge.DataPoints {
						p := base
						p.Type, p.Attributes, p.Value = "gauge", attrs(dp.Attributes), number(dp)
						got = append(got, p)
					}
				case *metricspb.Metric_Histogram:
					for _, dp := range data.Histogram.DataPoints {
						p := base
						p.Type, p.Attributes, p.Count, p.Bounds = "histogram", attrs(dp.Attributes), dp.Count, dp.ExplicitBounds
						got = append(got, p)
					}
				}
			}
		}
	}
	s.mu.Lock()
	s.points = append(s.points, got...)
	enc := json.NewEncoder(s.metOut)
	for _, p := range got {
		_ = enc.Encode(p)
	}
	s.mu.Unlock()
	reply(w, r, &colmetrics.ExportMetricsServiceResponse{})
}

func number(dp *metricspb.NumberDataPoint) float64 {
	if v, ok := dp.Value.(*metricspb.NumberDataPoint_AsInt); ok {
		return float64(v.AsInt)
	}
	return dp.GetAsDouble()
}

func attrs(kvs []*commonpb.KeyValue) map[string]string {
	if len(kvs) == 0 {
		return nil
	}
	out := make(map[string]string, len(kvs))
	for _, kv := range kvs {
		v := kv.GetValue()
		switch {
		case v.GetStringValue() != "":
			out[kv.Key] = v.GetStringValue()
		default:
			b, _ := protojson.Marshal(v)
			out[kv.Key] = string(b)
		}
	}
	return out
}

func (s *sink) querySpans(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	s.mu.Lock()
	out := []Span{}
	for _, sp := range s.spans {
		if t := q.Get("trace_id"); t != "" && sp.TraceID != t {
			continue
		}
		if n := q.Get("name"); n != "" && sp.Name != n {
			continue
		}
		if t := q.Get("linked_trace_id"); t != "" && !linksTo(sp, t) {
			continue
		}
		out = append(out, sp)
	}
	s.mu.Unlock()
	writeJSON(w, out)
}

func linksTo(sp Span, traceID string) bool {
	for _, l := range sp.Links {
		if l.TraceID == traceID {
			return true
		}
	}
	return false
}

func (s *sink) queryMetrics(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	s.mu.Lock()
	out := []Point{}
	for _, p := range s.points {
		if name == "" || p.Name == name {
			out = append(out, p)
		}
	}
	s.mu.Unlock()
	writeJSON(w, out)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
