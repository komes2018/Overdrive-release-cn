package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Minimal SOCKS5 server (RFC 1928): no authentication, CONNECT only. That is
// all the app's ProxiedSocketFactory and OkHttp need. It listens on loopback
// only; the port is not reachable from the car's LAN or the hotspot.

const (
	socksVersion = 5

	cmdConnect = 1

	atypIPv4   = 1
	atypDomain = 3
	atypIPv6   = 4

	repSuccess          = 0
	repGeneralFailure   = 1
	repNetUnreachable   = 3
	repHostUnreachable  = 4
	repConnRefused      = 5
	repCmdNotSupported  = 7
	repAtypNotSupported = 8
)

// dialFunc connects to host:port, where host is an IP literal or a name from
// the SOCKS request. Resolution is left to the dialer because the right
// resolver depends on the route (tunnel DNS, sing-box, system).
type dialFunc func(ctx context.Context, host string, port uint16) (net.Conn, error)

type socksServer struct {
	dial dialFunc
}

func (s *socksServer) serve(ln net.Listener) error {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go s.handle(c)
	}
}

func (s *socksServer) handle(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))

	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil || hdr[0] != socksVersion {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	noAuth := false
	for _, m := range methods {
		if m == 0 {
			noAuth = true
		}
	}
	if !noAuth {
		_, _ = c.Write([]byte{socksVersion, 0xff})
		return
	}
	if _, err := c.Write([]byte{socksVersion, 0}); err != nil {
		return
	}

	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil || req[0] != socksVersion {
		return
	}
	if req[1] != cmdConnect {
		reply(c, repCmdNotSupported)
		return
	}
	host, port, ok := readAddr(c, req[3])
	if !ok {
		reply(c, repAtypNotSupported)
		return
	}

	if !validHost(host) {
		reply(c, repHostUnreachable)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	remote, err := s.dial(ctx, host, port)
	if err != nil {
		log.Printf("socks: connect %s: %v", net.JoinHostPort(host, strconv.Itoa(int(port))), err)
		reply(c, errToReply(err))
		return
	}
	defer remote.Close()

	if err := reply(c, repSuccess); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	pipe(c, remote)
}

func readAddr(r io.Reader, atyp byte) (string, uint16, bool) {
	var host string
	switch atyp {
	case atypIPv4:
		var b [4]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, false
		}
		host = netip.AddrFrom4(b).String()
	case atypIPv6:
		var b [16]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, false
		}
		host = netip.AddrFrom16(b).String()
	case atypDomain:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return "", 0, false
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, false
		}
		host = string(b)
	default:
		return "", 0, false
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return "", 0, false
	}
	return host, binary.BigEndian.Uint16(p[:]), true
}

// validHost accepts IP literals and DNS names. The name may be passed on to
// sing-box in an HTTP CONNECT line, so nothing else may get through.
func validHost(h string) bool {
	if _, err := netip.ParseAddr(h); err == nil {
		return true
	}
	if h == "" || len(h) > 253 {
		return false
	}
	for _, ch := range h {
		switch {
		case ch >= 'a' && ch <= 'z', ch >= 'A' && ch <= 'Z', ch >= '0' && ch <= '9',
			ch == '.', ch == '-', ch == '_':
		default:
			return false
		}
	}
	return true
}

func reply(w io.Writer, code byte) error {
	_, err := w.Write([]byte{socksVersion, code, 0, atypIPv4, 0, 0, 0, 0, 0, 0})
	return err
}

func errToReply(err error) byte {
	var dnsErr *net.DNSError
	switch {
	case err == nil:
		return repSuccess
	case errors.Is(err, syscall.ECONNREFUSED), strings.Contains(err.Error(), "connection refused"):
		return repConnRefused
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &dnsErr):
		return repHostUnreachable
	case errors.Is(err, syscall.ENETUNREACH):
		return repNetUnreachable
	}
	return repGeneralFailure
}

func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
	}
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}

// dialViaConnect opens an HTTP CONNECT tunnel through a local proxy. Used for
// destinations outside AllowedIPs when sing-box is the car's internet path.
// HTTP CONNECT (not SOCKS) is the path ProxyHelper has always used for
// sing-box, and the host name is passed through so sing-box resolves it:
// in the states where sing-box is needed, local DNS may not work either.
func dialViaConnect(ctx context.Context, proxy, target string) (net.Conn, error) {
	c, err := direct.DialContext(ctx, "tcp", proxy)
	if err != nil {
		return nil, err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n"
	if _, err := io.WriteString(c, req); err != nil {
		c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		c.Close()
		return nil, err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		c.Close()
		return nil, fmt.Errorf("upstream proxy: CONNECT %s: %s", target, resp.Status)
	}
	if br.Buffered() > 0 {
		// Nothing may follow the response before we send; a proxy that does
		// so is broken, and dropping those bytes would corrupt the stream.
		c.Close()
		return nil, errors.New("upstream proxy: unexpected data after CONNECT response")
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}
