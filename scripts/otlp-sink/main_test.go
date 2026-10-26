package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	colmetrics "go.opentelemetry.io/proto/otlp/collector/metrics/v1"
	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	metricspb "go.opentelemetry.io/proto/otlp/metrics/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	tracepb "go.opentelemetry.io/proto/otlp/trace/v1"
	"google.golang.org/protobuf/proto"
)

func id(b byte, n int) []byte { return bytes.Repeat([]byte{b}, n) }

// A span sent in protobuf is kept with its service and links, and found by the trace it links to.
func TestASpanIsKeptAndFoundByItsLink(t *testing.T) {
	dir := t.TempDir()
	s := &sink{}
	var err error
	if s.spanOut, err = createIn(dir, "spans.jsonl"); err != nil {
		t.Fatal(err)
	}
	body, _ := proto.Marshal(&coltrace.ExportTraceServiceRequest{ResourceSpans: []*tracepb.ResourceSpans{{
		Resource: &resourcepb.Resource{Attributes: []*commonpb.KeyValue{{Key: "service.name",
			Value: &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: "ernest-ingestor"}}}}},
		ScopeSpans: []*tracepb.ScopeSpans{{Spans: []*tracepb.Span{{
			Name: "ernest.kafka.process", TraceId: id(1, 16), SpanId: id(2, 8), Kind: tracepb.Span_SPAN_KIND_CONSUMER,
			Links: []*tracepb.Span_Link{{TraceId: id(9, 16), SpanId: id(8, 8)}},
		}}}},
	}}})
	rec := httptest.NewRecorder()
	s.traces(rec, httptest.NewRequest(http.MethodPost, "/v1/traces", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}

	rec = httptest.NewRecorder()
	s.querySpans(rec, httptest.NewRequest(http.MethodGet, "/spans?linked_trace_id=09090909090909090909090909090909", nil))
	var spans []Span
	_ = json.Unmarshal(rec.Body.Bytes(), &spans)
	if len(spans) != 1 || spans[0].Service != "ernest-ingestor" || spans[0].Kind != "CONSUMER" || spans[0].TraceID != "01010101010101010101010101010101" {
		t.Fatalf("spans = %+v", spans)
	}
}

// A histogram's exemplars keep the trace ids of its measurements.
func TestAHistogramKeepsItsExemplarsTraces(t *testing.T) {
	s := &sink{}
	var err error
	if s.metOut, err = createIn(t.TempDir(), "metrics.jsonl"); err != nil {
		t.Fatal(err)
	}
	body, _ := proto.Marshal(&colmetrics.ExportMetricsServiceRequest{ResourceMetrics: []*metricspb.ResourceMetrics{{
		ScopeMetrics: []*metricspb.ScopeMetrics{{Metrics: []*metricspb.Metric{{
			Name: "prudence.decision.duration",
			Data: &metricspb.Metric_Histogram{Histogram: &metricspb.Histogram{DataPoints: []*metricspb.HistogramDataPoint{{
				Count: 1, ExplicitBounds: []float64{0.25},
				Exemplars: []*metricspb.Exemplar{{TraceId: id(7, 16), SpanId: id(3, 8)}},
			}}}},
		}}}},
	}}})
	rec := httptest.NewRecorder()
	s.metrics(rec, httptest.NewRequest(http.MethodPost, "/v1/metrics", bytes.NewReader(body)))
	rec = httptest.NewRecorder()
	s.queryMetrics(rec, httptest.NewRequest(http.MethodGet, "/metrics?name=prudence.decision.duration", nil))
	var points []Point
	_ = json.Unmarshal(rec.Body.Bytes(), &points)
	if len(points) != 1 || len(points[0].Exemplars) != 1 || points[0].Exemplars[0] != "07070707070707070707070707070707" {
		t.Fatalf("points = %+v", points)
	}
}
