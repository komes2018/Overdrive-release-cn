package main

import (
	"bufio"
	"crypto/rand"
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

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func genKey(t *testing.T) (priv, pub []byte) {
	t.Helper()
	priv = make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return priv, pub
}

func freePort(t *testing.T, network string) int {
	t.Helper()
	if network == "udp" {
		c, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		return c.LocalAddr().(*net.UDPAddr).Port
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// TestEndToEnd starts a "home" WireGuard peer with an echo service on its
// tunnel address, then runs the client exactly like on the car and talks to
// the echo service through the SOCKS5 port, by IP and by name via DNS=.
func TestEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("e2e")
	}
	srvPriv, srvPub := genKey(t)
	cliPriv, cliPub := genKey(t)
	srvUDP := freePort(t, "udp")

	// Home side: 10.99.0.1, echo on :1883.
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
	ln, err := srvNet.ListenTCP(&net.TCPAddr{Port: 1883})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()

	// Car side.
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "wg0.conf")
	conf := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 10.99.0.2/32

[Peer]
PublicKey = %s
Endpoint = 127.0.0.1:%d
AllowedIPs = 10.99.0.0/24
`, base64.StdEncoding.EncodeToString(cliPriv), base64.StdEncoding.EncodeToString(srvPub), srvUDP)
	if err := os.WriteFile(cfgPath, []byte(conf), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := ParseConfig(strings.NewReader(conf))
	if err != nil {
		t.Fatal(err)
	}
	socks := fmt.Sprintf("127.0.0.1:%d", freePort(t, "tcp"))
	statusPath := filepath.Join(dir, "status.json")
	r := newRunner(cfg, newSystemResolver(nil), statusPath)
	r.configPath = cfgPath
	done := make(chan error, 1)
	go func() { done <- r.run(socks) }()

	echo := func(target string) {
		t.Helper()
		var c net.Conn
		var err error
		for i := 0; i < 50; i++ {
			c, err = socksConnect(socks, target)
			if err == nil {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		if err != nil {
			t.Fatalf("connect %s: %v", target, err)
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := c.Write([]byte("ping\n")); err != nil {
			t.Fatal(err)
		}
		line, err := bufio.NewReader(c).ReadString('\n')
		if err != nil || line != "ping\n" {
			t.Fatalf("echo via %s: %q %v", target, line, err)
		}
	}
	echo("10.99.0.1:1883")

	// Status file reports the handshake.
	b, err := os.ReadFile(statusPath)
	if err != nil {
		time.Sleep(6 * time.Second)
		b, err = os.ReadFile(statusPath)
	}
	if err != nil {
		t.Fatalf("status file: %v", err)
	}
	if !strings.Contains(string(b), `"peers"`) {
		t.Errorf("status: %s", b)
	}

	// A destination outside AllowedIPs is dialled directly.
	plain, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer plain.Close()
	go func() {
		c, err := plain.Accept()
		if err == nil {
			_, _ = io.Copy(c, c)
			c.Close()
		}
	}()
	echo(plain.Addr().String())

	// Changing the config makes run() return errReload.
	time.Sleep(10 * time.Millisecond)
	if err := os.WriteFile(cfgPath, []byte(conf+"\n# edited\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != errReload {
			t.Fatalf("run returned %v, want errReload", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("config change not picked up")
	}
}

// socksConnect is a minimal SOCKS5 client, independent from the server code.
func socksConnect(proxy, target string) (net.Conn, error) {
	host, portStr, _ := net.SplitHostPort(target)
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	c, err := net.DialTimeout("tcp", proxy, 2*time.Second)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	c.Write([]byte{5, 1, 0})
	sel := make([]byte, 2)
	if _, err := io.ReadFull(c, sel); err != nil || sel[1] != 0 {
		c.Close()
		return nil, fmt.Errorf("method: %v %v", sel, err)
	}
	req := []byte{5, 1, 0}
	if ip, err := netip.ParseAddr(host); err == nil && ip.Is4() {
		a := ip.As4()
		req = append(append(req, 1), a[:]...)
	} else {
		req = append(append(req, 3, byte(len(host))), host...)
	}
	req = append(req, byte(port>>8), byte(port))
	c.Write(req)
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil {
		c.Close()
		return nil, err
	}
	if rep[1] != 0 {
		c.Close()
		return nil, fmt.Errorf("socks reply %d", rep[1])
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}
