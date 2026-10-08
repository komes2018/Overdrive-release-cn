package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// Config is the subset of the wg-quick format that matters for a userspace
// client: one [Interface] and one or more [Peer] sections. Keys that only make
// sense for a kernel interface (Table, PreUp, PostDown, SaveConfig, FwMark, ...)
// are accepted and ignored so that configs exported by OPNsense, pfSense,
// MikroTik, wg-easy or the WireGuard apps can be pasted unchanged.
type Config struct {
	PrivateKey string // hex
	Addresses  []netip.Prefix
	DNS        []netip.Addr
	MTU        int
	Peers      []Peer
}

type Peer struct {
	PublicKey    string // hex
	PresharedKey string // hex, optional
	Endpoint     string // host:port as written in the config
	AllowedIPs   []netip.Prefix
	Keepalive    int
}

// DefaultKeepalive is applied when a peer has no PersistentKeepalive. The car
// sits behind carrier-grade NAT; without keepalives the UDP mapping expires and
// messages from the broker (vehicle control) never arrive.
const DefaultKeepalive = 25

const defaultMTU = 1280

func ParseConfig(r io.Reader) (*Config, error) {
	cfg := &Config{}
	var peer *Peer
	section := ""
	sc := bufio.NewScanner(r)
	lineNo := 0
	for sc.Scan() {
		lineNo++
		line := sc.Text()
		if i := strings.IndexAny(line, "#;"); i >= 0 {
			line = line[:i]
		}
		line = strings.Trim(line, " \t\r")
		if line == "" {
			continue
		}
		// Comments may contain anything; the rest must be printable ASCII.
		// The app validates pasted configs with the same rule, so both sides
		// accept exactly the same input.
		for _, ch := range line {
			if ch != '\t' && (ch < 0x20 || ch > 0x7e) {
				return nil, fmt.Errorf("line %d: invalid character", lineNo)
			}
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			switch section {
			case "interface":
			case "peer":
				cfg.Peers = append(cfg.Peers, Peer{Keepalive: -1})
				peer = &cfg.Peers[len(cfg.Peers)-1]
			default:
				return nil, fmt.Errorf("line %d: unknown section [%s]", lineNo, section)
			}
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq < 0 {
			return nil, fmt.Errorf("line %d: expected key = value", lineNo)
		}
		key := strings.ToLower(strings.Trim(line[:eq], " \t"))
		val := strings.Trim(line[eq+1:], " \t")
		var err error
		switch section {
		case "interface":
			err = cfg.setInterface(key, val)
		case "peer":
			err = peer.set(key, val)
		default:
			err = fmt.Errorf("%s outside of a section", key)
		}
		if err != nil {
			return nil, fmt.Errorf("line %d: %w", lineNo, err)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return cfg, cfg.validate()
}

func (c *Config) setInterface(key, val string) error {
	switch key {
	case "privatekey":
		k, err := keyToHex(val)
		if err != nil {
			return fmt.Errorf("PrivateKey: %w", err)
		}
		c.PrivateKey = k
	case "address":
		for _, s := range splitList(val) {
			p, err := parsePrefixOrAddr(s)
			if err != nil {
				return fmt.Errorf("Address: %w", err)
			}
			c.Addresses = append(c.Addresses, p)
		}
	case "dns":
		for _, s := range splitList(val) {
			// wg-quick allows search domains in DNS=; they are irrelevant here.
			a, err := netip.ParseAddr(s)
			if err != nil {
				if strings.Contains(s, "%") {
					return fmt.Errorf("DNS: zone not allowed in %q", s)
				}
				continue
			}
			if a.Zone() != "" {
				return fmt.Errorf("DNS: zone not allowed in %q", s)
			}
			c.DNS = append(c.DNS, a)
		}
	case "mtu":
		n, err := atoiASCII(val)
		if err != nil || n < 576 || n > 9000 {
			return fmt.Errorf("MTU: invalid value %q", val)
		}
		c.MTU = n
	case "listenport", "table", "fwmark", "saveconfig",
		"preup", "postup", "predown", "postdown":
		// Kernel/wg-quick only.
	default:
		return fmt.Errorf("unknown Interface key %q", shortKey(key))
	}
	return nil
}

func (p *Peer) set(key, val string) error {
	switch key {
	case "publickey":
		k, err := keyToHex(val)
		if err != nil {
			return fmt.Errorf("PublicKey: %w", err)
		}
		p.PublicKey = k
	case "presharedkey":
		k, err := keyToHex(val)
		if err != nil {
			return fmt.Errorf("PresharedKey: %w", err)
		}
		p.PresharedKey = k
	case "endpoint":
		if _, _, err := splitEndpoint(val); err != nil {
			return fmt.Errorf("Endpoint: %w", err)
		}
		p.Endpoint = val
	case "allowedips":
		for _, s := range splitList(val) {
			pfx, err := parsePrefixOrAddr(s)
			if err != nil {
				return fmt.Errorf("AllowedIPs: %w", err)
			}
			p.AllowedIPs = append(p.AllowedIPs, pfx.Masked())
		}
	case "persistentkeepalive":
		if strings.EqualFold(val, "off") {
			p.Keepalive = 0
			return nil
		}
		n, err := atoiASCII(val)
		if err != nil || n < 0 || n > 65535 {
			return fmt.Errorf("PersistentKeepalive: invalid value %q", val)
		}
		p.Keepalive = n
	default:
		return fmt.Errorf("unknown Peer key %q", shortKey(key))
	}
	return nil
}

func (c *Config) validate() error {
	if c.PrivateKey == "" {
		return fmt.Errorf("[Interface] PrivateKey is missing")
	}
	if len(c.Addresses) == 0 {
		return fmt.Errorf("[Interface] Address is missing")
	}
	if len(c.Peers) == 0 {
		return fmt.Errorf("no [Peer] section")
	}
	hasEndpoint := false
	for i := range c.Peers {
		p := &c.Peers[i]
		if p.PublicKey == "" {
			return fmt.Errorf("peer %d: PublicKey is missing", i+1)
		}
		if len(p.AllowedIPs) == 0 {
			return fmt.Errorf("peer %d: AllowedIPs is missing", i+1)
		}
		if p.Endpoint != "" {
			hasEndpoint = true
		}
		if p.Keepalive < 0 {
			p.Keepalive = DefaultKeepalive
		}
	}
	if !hasEndpoint {
		return fmt.Errorf("no peer has an Endpoint; the car cannot be reached from outside, it has to dial out")
	}
	if c.MTU == 0 {
		c.MTU = defaultMTU
	}
	return nil
}

// Routes returns the union of all AllowedIPs.
func (c *Config) Routes() []netip.Prefix {
	var out []netip.Prefix
	for _, p := range c.Peers {
		out = append(out, p.AllowedIPs...)
	}
	return out
}

// UAPI renders the device configuration for wireguard-go's IpcSet. Endpoints
// must already be resolved to IP:port; resolved maps peer index -> addr.
func (c *Config) UAPI(resolved map[int]netip.AddrPort) string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", c.PrivateKey)
	b.WriteString("replace_peers=true\n")
	for i, p := range c.Peers {
		fmt.Fprintf(&b, "public_key=%s\n", p.PublicKey)
		if p.PresharedKey != "" {
			fmt.Fprintf(&b, "preshared_key=%s\n", p.PresharedKey)
		}
		if ep, ok := resolved[i]; ok {
			fmt.Fprintf(&b, "endpoint=%s\n", ep)
		}
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.Keepalive)
		b.WriteString("replace_allowed_ips=true\n")
		for _, a := range p.AllowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String()
}

func keyToHex(s string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil || len(raw) != 32 {
		return "", fmt.Errorf("not a WireGuard key (expected 44 characters of base64)")
	}
	return hex.EncodeToString(raw), nil
}

// shortKey keeps error messages free of key material: a line like
// "PrivateKey abc...=" (missing separator) would otherwise echo the key.
func shortKey(k string) string {
	if i := strings.IndexAny(k, " \t"); i >= 0 {
		k = k[:i]
	}
	if len(k) > 24 {
		k = k[:24] + "…"
	}
	return k
}

func atoiASCII(s string) (int, error) {
	if s == "" || len(s) > 9 || strings.Trim(s, "0123456789") != "" {
		return 0, fmt.Errorf("not a number: %q", s)
	}
	return strconv.Atoi(s)
}

func splitList(s string) []string {
	var out []string
	for _, f := range strings.Split(s, ",") {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

func parsePrefixOrAddr(s string) (netip.Prefix, error) {
	if strings.Contains(s, "/") {
		return netip.ParsePrefix(s)
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, err
	}
	if a.Zone() != "" {
		return netip.Prefix{}, fmt.Errorf("zone not allowed in %q", s)
	}
	return netip.PrefixFrom(a, a.BitLen()), nil
}

func splitEndpoint(s string) (host string, port uint16, err error) {
	h, p, err := net.SplitHostPort(s)
	if err != nil {
		return "", 0, err
	}
	if len(p) == 0 || len(p) > 5 || p[0] == '0' || strings.Trim(p, "0123456789") != "" {
		return "", 0, fmt.Errorf("invalid port %q", p)
	}
	n, err := strconv.ParseUint(p, 10, 16)
	if err != nil {
		return "", 0, fmt.Errorf("invalid port %q", p)
	}
	if h == "" {
		return "", 0, fmt.Errorf("missing host")
	}
	if strings.HasPrefix(s, "[") {
		a, err := netip.ParseAddr(h)
		if err != nil || !a.Is6() || a.Zone() != "" {
			return "", 0, fmt.Errorf("only IPv6 addresses go in brackets: %q", s)
		}
	}
	return h, uint16(n), nil
}
