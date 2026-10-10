package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func TestProxyV1Header(t *testing.T) {
	cases := []struct{ src, dst, want string }{
		{"10.99.0.1:51234", "10.99.0.2:8080", "PROXY TCP4 10.99.0.1 10.99.0.2 51234 8080\r\n"},
		{"[fd00::1]:40000", "[fd00::2]:8080", "PROXY TCP6 fd00::1 fd00::2 40000 8080\r\n"},
		{"[::ffff:10.99.0.1]:1", "10.99.0.2:8080", "PROXY TCP4 10.99.0.1 10.99.0.2 1 8080\r\n"},
	}
	for _, c := range cases {
		got, err := proxyV1Header(netip.MustParseAddrPort(c.src), netip.MustParseAddrPort(c.dst))
		if err != nil || got != c.want {
			t.Errorf("%s -> %s: %q %v, want %q", c.src, c.dst, got, err, c.want)
		}
		if len(got)-2 > 107 {
			t.Errorf("header too long: %d", len(got))
		}
	}
	if _, err := proxyV1Header(netip.MustParseAddrPort("10.0.0.1:1"), netip.MustParseAddrPort("[fd00::2]:8080")); err == nil {
		t.Error("mixed families accepted")
	}
}

func TestParseExpose(t *testing.T) {
	ok, err := parseExpose("8080=127.0.0.1:8080")
	if err != nil || ok.Port != 8080 || ok.Backend != "127.0.0.1:8080" {
		t.Fatalf("%+v %v", ok, err)
	}
	if ok.Raw {
		t.Errorf("expected 8080 to not be raw, got %+v", ok)
	}
	adb, err := parseExpose("5555=127.0.0.1:5555")
	if err != nil || adb.Port != 5555 || !adb.Raw {
		t.Fatalf("expected raw mode for ADB: %+v %v", adb, err)
	}
	customRaw, err := parseExpose("9000=127.0.0.1:9000,raw")
	if err != nil || customRaw.Port != 9000 || !customRaw.Raw {
		t.Fatalf("expected custom raw mode: %+v %v", customRaw, err)
	}
	if _, err := parseExpose("80=[::1]:8080"); err != nil {
		t.Errorf("v6 loopback: %v", err)
	}
	for _, bad := range []string{"", "8080", "0=127.0.0.1:1", "x=127.0.0.1:1", "8080=192.168.1.5:80",
		"8080=localhost:80", "8080=127.0.0.1", "8080=127.0.0.1:0", "70000=127.0.0.1:1"} {
		if _, err := parseExpose(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

// TestExposeEndToEnd: a home peer connects to the car's tunnel address, the
// loopback backend gets the PROXY v1 line with the peer's tunnel IP first,
// then data flows both ways. A port that was not exposed stays closed.
func TestExposeEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("e2e")
	}
	srvPriv, srvPub := genKey(t)
	cliPriv, cliPub := genKey(t)
	srvUDP := freePort(t, "udp")

	srvTun, srvNet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.99.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	srvDev := device.NewDevice(srvTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv: "))
	defer srvDev.Close()
	if err := srvDev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.99.0.2/32\n",
		hex.EncodeToString(srvPriv), srvUDP, hex.EncodeToString(cliPub))); err != nil {
		t.Fatal(err)
	}
	if err := srvDev.Up(); err != nil {
		t.Fatal(err)
	}

	// Loopback backend: records the first line, then echoes.
	backend, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	first := make(chan string, 1)
	go func() {
		for {
			c, err := backend.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				br := bufio.NewReader(c)
				line, _ := br.ReadString('\n')
				select {
				case first <- line:
				default:
				}
				_, _ = io.Copy(c, br)
			}()
		}
	}()

	dir := t.TempDir()
	conf := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.99.0.2/32\n\n[Peer]\nPublicKey = %s\nEndpoint = 127.0.0.1:%d\nAllowedIPs = 10.99.0.0/24\n",
		base64.StdEncoding.EncodeToString(cliPriv), base64.StdEncoding.EncodeToString(srvPub), srvUDP)
	cfgPath := filepath.Join(dir, "wg0.conf")
	if err := os.WriteFile(cfgPath, []byte(conf), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := ParseConfig(strings.NewReader(conf))
	if err != nil {
		t.Fatal(err)
	}
	statusPath := filepath.Join(dir, "status.json")
	r := newRunner(cfg, newSystemResolver(nil), statusPath)
	r.configPath = cfgPath
	r.expose = []exposeSpec{{Port: 8080, Backend: backend.Addr().String()}}
	done := make(chan error, 1)
	go func() { done <- r.run(fmt.Sprintf("127.0.0.1:%d", freePort(t, "tcp"))) }()

	dialCar := func(port uint16) (net.Conn, error) {
		var c net.Conn
		var err error
		for i := 0; i < 50; i++ {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			c, err = srvNet.DialContextTCPAddrPort(ctx, netip.MustParseAddrPort(fmt.Sprintf("10.99.0.2:%d", port)))
			cancel()
			if err == nil {
				return c, nil
			}
			time.Sleep(100 * time.Millisecond)
		}
		return nil, err
	}

	c, err := dialCar(8080)
	if err != nil {
		t.Fatalf("dial car: %v", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := c.Write([]byte("ping\n")); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(c)
	line, err := br.ReadString('\n')
	if err != nil || line != "ping\n" {
		t.Fatalf("echo: %q %v", line, err)
	}
	select {
	case got := <-first:
		if !strings.HasPrefix(got, "PROXY TCP4 10.99.0.1 10.99.0.2 ") || !strings.HasSuffix(got, " 8080\r\n") {
			t.Fatalf("backend first line: %q", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("backend saw no PROXY line")
	}

	// Half-close: the request side ends, the reply still arrives.
	_, _ = c.Write([]byte("tail\n"))
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
	rest, err := io.ReadAll(br)
	if err != nil || string(rest) != "tail\n" {
		t.Fatalf("after half-close: %q %v", rest, err)
	}

	// Not exposed: nothing listens on another tunnel port.
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	if c2, err := srvNet.DialContextTCPAddrPort(ctx, netip.MustParseAddrPort("10.99.0.2:8081")); err == nil {
		c2.Close()
		t.Error("unexposed port accepted a connection")
	}
	cancel()

	// Without -expose nothing is published (checked via the runner type).
	if len(newRunner(cfg, nil, "").expose) != 0 {
		t.Error("expose set by default")
	}
	b, _ := os.ReadFile(statusPath)
	if !strings.Contains(string(b), `"expose":[8080]`) {
		time.Sleep(6 * time.Second)
		b, _ = os.ReadFile(statusPath)
		if !strings.Contains(string(b), `"expose":[8080]`) {
			t.Errorf("status lacks expose: %s", b)
		}
	}

	// Stopping tears down connections and listeners.
	if err := os.WriteFile(cfgPath, []byte(conf+"\n# edited\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != errReload {
			t.Fatalf("run returned %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("run did not return")
	}
}
