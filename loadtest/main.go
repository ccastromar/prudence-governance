// loadgen — a small, dependency-free load generator for the Prudence + Ernest stack.
//
//	loadgen http     closed-loop HTTP load: N workers send requests back to back for a
//	                 duration (or a total count) and report throughput, latency percentiles
//	                 and the status-code mix. Bodies are templates: {{uuid}} and {{seq}} are
//	                 replaced per request, so every request carries a unique id.
//	loadgen fake-opa a stand-in OPA that answers every decision query with {"result": true},
//	                 so the PDP runs its full path (registry lookup + OPA hop + outbox write).
//
// Results print as one human line and, with -json, one JSON line (for scripts).
package main

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type headers []string

func (h *headers) String() string     { return strings.Join(*h, ", ") }
func (h *headers) Set(v string) error { *h = append(*h, v); return nil }

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: loadgen http|fake-opa [flags]")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "http":
		runHTTP(os.Args[2:])
	case "fake-opa":
		runFakeOPA(os.Args[2:])
	default:
		fmt.Fprintln(os.Stderr, "unknown mode:", os.Args[1])
		os.Exit(2)
	}
}

func runFakeOPA(args []string) {
	fs := flag.NewFlagSet("fake-opa", flag.ExitOnError)
	addr := fs.String("addr", "127.0.0.1:8181", "listen address")
	_ = fs.Parse(args)
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"result":true}`))
	})
	fmt.Println("fake OPA listening on", *addr)
	if err := http.ListenAndServe(*addr, nil); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

type result struct {
	Label           string         `json:"label"`
	Workers         int            `json:"workers"`
	Requests        int64          `json:"requests"`
	Seconds         float64        `json:"seconds"`
	RPS             float64        `json:"rps"`
	P50ms           float64        `json:"p50_ms"`
	P90ms           float64        `json:"p90_ms"`
	P99ms           float64        `json:"p99_ms"`
	MaxMs           float64        `json:"max_ms"`
	Codes           map[string]int `json:"codes"`
	TransportErrors int64          `json:"transport_errors"`
}

func runHTTP(args []string) {
	fs := flag.NewFlagSet("http", flag.ExitOnError)
	url := fs.String("url", "", "target URL")
	method := fs.String("method", "POST", "HTTP method")
	body := fs.String("body", "", "body template ({{uuid}}, {{seq}})")
	workers := fs.Int("c", 8, "concurrent workers")
	dur := fs.Duration("d", 10*time.Second, "duration (ignored when -n > 0)")
	total := fs.Int64("n", 0, "total requests instead of a duration")
	label := fs.String("label", "", "label for the result line")
	asJSON := fs.Bool("json", false, "also print a JSON result line")
	timeout := fs.Duration("timeout", 30*time.Second, "per-request timeout")
	maxErr := fs.Int64("max-transport-errors", 200, "abort the run after this many connection-level errors (a dead target would otherwise exhaust local ports)")
	var hs headers
	fs.Var(&hs, "H", "header 'Name: value' (repeatable)")
	_ = fs.Parse(args)
	if *url == "" {
		fmt.Fprintln(os.Stderr, "-url required")
		os.Exit(2)
	}

	client := &http.Client{Timeout: *timeout, Transport: &http.Transport{
		MaxIdleConns: *workers * 2, MaxIdleConnsPerHost: *workers * 2, IdleConnTimeout: 30 * time.Second,
	}}
	var seq, issued, transportErrs atomic.Int64
	deadline := time.Now().Add(*dur)
	var mu sync.Mutex
	codes := map[string]int{}
	latencies := make([]float64, 0, 1<<16)

	var wg sync.WaitGroup
	start := time.Now()
	for w := 0; w < *workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			local := make([]float64, 0, 4096)
			localCodes := map[string]int{}
			for {
				if transportErrs.Load() >= *maxErr {
					break
				}
				if *total > 0 {
					if issued.Add(1) > *total {
						break
					}
				} else if time.Now().After(deadline) {
					break
				}
				n := seq.Add(1)
				b := freshUUIDs(*body)
				b = strings.ReplaceAll(b, "{{seq}}", fmt.Sprint(n))
				req, _ := http.NewRequest(*method, *url, bytes.NewBufferString(b))
				req.Header.Set("Content-Type", "application/json")
				for _, h := range hs {
					if k, v, ok := strings.Cut(h, ":"); ok {
						req.Header.Set(strings.TrimSpace(k), strings.TrimSpace(freshUUIDs(v)))
					}
				}
				t0 := time.Now()
				resp, err := client.Do(req)
				ms := float64(time.Since(t0).Microseconds()) / 1000
				if err != nil {
					transportErrs.Add(1)
					localCodes["ERR"]++
					continue
				}
				_, _ = io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				local = append(local, ms)
				localCodes[fmt.Sprint(resp.StatusCode)]++
			}
			mu.Lock()
			latencies = append(latencies, local...)
			for k, v := range localCodes {
				codes[k] += v
			}
			mu.Unlock()
		}()
	}
	wg.Wait()
	elapsed := time.Since(start).Seconds()

	sort.Float64s(latencies)
	pct := func(p float64) float64 {
		if len(latencies) == 0 {
			return 0
		}
		i := int(p * float64(len(latencies)-1))
		return latencies[i]
	}
	var count int64
	for _, v := range codes {
		count += int64(v)
	}
	if transportErrs.Load() >= *maxErr {
		fmt.Fprintf(os.Stderr, "aborted: %d transport errors (is the target down?)\n", transportErrs.Load())
	}
	r := result{Label: *label, Workers: *workers, Requests: count, Seconds: elapsed, RPS: float64(count) / elapsed,
		P50ms: pct(0.50), P90ms: pct(0.90), P99ms: pct(0.99), Codes: codes, TransportErrors: transportErrs.Load()}
	if len(latencies) > 0 {
		r.MaxMs = latencies[len(latencies)-1]
	}
	fmt.Printf("%-34s c=%-3d %7d req %6.1fs %8.1f rps  p50=%7.1fms p90=%7.1fms p99=%7.1fms max=%7.1fms codes=%v\n",
		r.Label, r.Workers, r.Requests, r.Seconds, r.RPS, r.P50ms, r.P90ms, r.P99ms, r.MaxMs, r.Codes)
	if *asJSON {
		j, _ := json.Marshal(r)
		fmt.Println("JSON " + string(j))
	}
}

// freshUUIDs replaces every {{uuid}} with its own new UUID (a batch template carries one
// placeholder per item, and each item needs a distinct id).
func freshUUIDs(t string) string {
	for strings.Contains(t, "{{uuid}}") {
		t = strings.Replace(t, "{{uuid}}", uuid(), 1)
	}
	return t
}

func uuid() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
