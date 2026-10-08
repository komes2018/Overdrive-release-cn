package main

import (
	"net/netip"
	"strings"
	"testing"
)

// Keys below are throwaway test vectors, not real credentials.
const (
	testPriv = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
	testPub  = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
	testPSK  = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
)

func TestParseOPNsenseExport(t *testing.T) {
	in := `# exported by OPNsense
[Interface]
PrivateKey = ` + testPriv + `
Address = 10.10.10.2/32, fd00:10::2/128
DNS = 192.168.13.1, home.lan

[Peer]
PublicKey = ` + testPub + `
PresharedKey = ` + testPSK + `
Endpoint = vpn.example.org:51820
AllowedIPs = 10.10.10.0/24, 192.168.13.0/24
`
	cfg, err := ParseConfig(strings.NewReader(in))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Addresses) != 2 || cfg.Addresses[0] != netip.MustParsePrefix("10.10.10.2/32") {
		t.Errorf("addresses = %v", cfg.Addresses)
	}
	if len(cfg.DNS) != 1 || cfg.DNS[0] != netip.MustParseAddr("192.168.13.1") {
		t.Errorf("dns = %v (search domain must be ignored)", cfg.DNS)
	}
	if cfg.MTU != defaultMTU {
		t.Errorf("mtu = %d", cfg.MTU)
	}
	p := cfg.Peers[0]
	if p.Keepalive != DefaultKeepalive {
		t.Errorf("keepalive = %d, want default %d", p.Keepalive, DefaultKeepalive)
	}
	if len(p.AllowedIPs) != 2 {
		t.Errorf("allowed = %v", p.AllowedIPs)
	}
	uapi := cfg.UAPI(map[int]netip.AddrPort{0: netip.MustParseAddrPort("203.0.113.7:51820")})
	for _, want := range []string{
		"private_key=c8",
		"preshared_key=",
		"endpoint=203.0.113.7:51820",
		"persistent_keepalive_interval=25",
		"allowed_ip=192.168.13.0/24",
	} {
		if !strings.Contains(uapi, want) {
			t.Errorf("uapi missing %q:\n%s", want, uapi)
		}
	}
}

func TestParseIgnoresWgQuickOnlyKeys(t *testing.T) {
	in := `[Interface]
PrivateKey = ` + testPriv + `
Address = 10.0.0.2
ListenPort = 51820
Table = off
PostUp = iptables -A FORWARD -i %i -j ACCEPT
MTU = 1420
[Peer]
PublicKey = ` + testPub + `
Endpoint = [2001:db8::1]:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 0
`
	cfg, err := ParseConfig(strings.NewReader(in))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Addresses[0] != netip.MustParsePrefix("10.0.0.2/32") {
		t.Errorf("bare address should become /32, got %v", cfg.Addresses[0])
	}
	if cfg.MTU != 1420 {
		t.Errorf("mtu = %d", cfg.MTU)
	}
	if cfg.Peers[0].Keepalive != 0 {
		t.Errorf("explicit 0 keepalive must be kept")
	}
}

func TestParseErrors(t *testing.T) {
	cases := map[string]string{
		"missing private key": "[Interface]\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nEndpoint = a:1\nAllowedIPs = 10.0.0.0/24\n",
		"bad key":             "[Interface]\nPrivateKey = abc\n",
		"no peer":             "[Interface]\nPrivateKey = " + testPriv + "\nAddress = 10.0.0.2/32\n",
		"no endpoint":         "[Interface]\nPrivateKey = " + testPriv + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nAllowedIPs = 10.0.0.0/24\n",
		"bad endpoint":        "[Interface]\nPrivateKey = " + testPriv + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nEndpoint = nohost\nAllowedIPs = 10.0.0.0/24\n",
		"unknown key":         "[Interface]\nPrivatKey = " + testPriv + "\n",
		"control char":        "[Interface]\nPrivateKey = " + testPriv + "\x1a\n",
		"unicode digit":       "[Interface]\nMTU = \u0661\u0664\u0662\u0660\n",
		"port leading zero":   "[Interface]\nPrivateKey = " + testPriv + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nEndpoint = a.example:051820\nAllowedIPs = 10.0.0.0/24\n",
		"bracketed hostname":  "[Interface]\nPrivateKey = " + testPriv + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nEndpoint = [vpn.example]:51820\nAllowedIPs = 10.0.0.0/24\n",
		"zone id":             "[Interface]\nPrivateKey = " + testPriv + "\nAddress = fe80::1%eth0\n",
	}
	for name, in := range cases {
		if _, err := ParseConfig(strings.NewReader(in)); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestInTunnel(t *testing.T) {
	cfg := &Config{Peers: []Peer{{AllowedIPs: []netip.Prefix{netip.MustParsePrefix("192.168.13.0/24")}}}}
	r := newRunner(cfg, nil, "")
	if !r.inTunnel(netip.MustParseAddr("192.168.13.1")) {
		t.Error("broker in AllowedIPs must use the tunnel")
	}
	if r.inTunnel(netip.MustParseAddr("8.8.8.8")) {
		t.Error("public address must be dialled directly")
	}
}

func TestErrorsDoNotEchoKeys(t *testing.T) {
	// Separator missing, but the key's own trailing '=' splits the line.
	in := "[Interface]\nPrivateKey " + testPriv + "\n"
	_, err := ParseConfig(strings.NewReader(in))
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), testPriv[:20]) || strings.Contains(strings.ToLower(err.Error()), strings.ToLower(testPriv[:20])) {
		t.Fatalf("error leaks key material: %v", err)
	}
}

func TestCommentsMayContainAnything(t *testing.T) {
	in := "# Büro-Router, exportiert am 08.10.\n[Interface]\nPrivateKey = " + testPriv + " # schlüssel\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + testPub + "\nEndpoint = [2001:db8::1]:51820\nAllowedIPs = 10.0.0.0/24\n"
	if _, err := ParseConfig(strings.NewReader(in)); err != nil {
		t.Fatal(err)
	}
}
