package main

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/netip"
	"testing"
	"time"
)

// fakeConnectProxy accepts one HTTP CONNECT, reports the requested target and
// then echoes the stream, like sing-box's mixed inbound would after connecting.
func fakeConnectProxy(t *testing.T) (addr string, targets <-chan string) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	ch := make(chan string, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				br := bufio.NewReader(c)
				req, err := http.ReadRequest(br)
				if err != nil || req.Method != http.MethodConnect {
					return
				}
				ch <- req.Host
				_, _ = io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
				_, _ = io.Copy(c, br)
			}()
		}
	}()
	return ln.Addr().String(), ch
}

func TestUpstreamGetsHostNameUnresolved(t *testing.T) {
	proxy, targets := fakeConnectProxy(t)
	cfg := &Config{Peers: []Peer{{AllowedIPs: []netip.Prefix{netip.MustParsePrefix("192.168.13.0/24")}}}}
	r := newRunner(cfg, nil, "") // nil resolver: any local lookup would panic
	r.upstream = proxy

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := r.dial(ctx, "weather.example.invalid", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := <-targets; got != "weather.example.invalid:443" {
		t.Fatalf("CONNECT target = %q", got)
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(c, "hello\n"); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil || line != "hello\n" {
		t.Fatalf("stream after CONNECT: %q %v", line, err)
	}
}

func TestUpstreamUsedForPublicIP(t *testing.T) {
	proxy, targets := fakeConnectProxy(t)
	cfg := &Config{Peers: []Peer{{AllowedIPs: []netip.Prefix{netip.MustParsePrefix("192.168.13.0/24")}}}}
	r := newRunner(cfg, nil, "")
	r.upstream = proxy

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := r.dial(ctx, "203.0.113.10", 8883)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if got := <-targets; got != "203.0.113.10:8883" {
		t.Fatalf("CONNECT target = %q", got)
	}
}

func TestUpstreamRefusalIsAnError(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = http.ReadRequest(bufio.NewReader(c))
		_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := dialViaConnect(ctx, ln.Addr().String(), "example.invalid:1"); err == nil {
		t.Fatal("502 from upstream must fail the dial")
	}
}
