// Command wgproxy is a userspace WireGuard client that exposes the tunnel as a
// loopback SOCKS5 proxy. It is shipped as jniLibs/arm64-v8a/libwireguard.so and
// started by WireGuardLauncher as uid shell, the same way tailscaled is.
//
// No TUN device and no VpnService are involved: the tunnel lives in a gVisor
// netstack inside this process. Only connections made through the SOCKS port
// can use it, so the rest of the head unit is unaffected.
//
// Destinations inside the peers' AllowedIPs go through the tunnel. Everything
// else is dialled directly, so a public broker keeps working while the proxy
// is enabled. With AllowedIPs = 0.0.0.0/0 everything goes through the tunnel.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

var version = "dev"

func main() {
	configPath := flag.String("config", "", "wg-quick style config file")
	socksAddr := flag.String("socks", "127.0.0.1:8541", "loopback address for the SOCKS5 server")
	statusPath := flag.String("status", "", "write JSON status to this file every few seconds")
	bootstrap := flag.String("bootstrap-dns", "1.1.1.1:53,8.8.8.8:53,9.9.9.9:53",
		"resolvers for endpoint host names when the system resolver is unusable")
	upstream := flag.String("upstream", "", "optional loopback HTTP CONNECT proxy (sing-box) for destinations outside the tunnel")
	var exposes []exposeSpec
	flag.Func("expose", "publish a tunnel port on a loopback backend with PROXY protocol v1, <port>=<host:port> (repeatable)",
		func(v string) error {
			sp, err := parseExpose(v)
			if err == nil {
				exposes = append(exposes, sp)
			}
			return err
		})
	check := flag.Bool("check", false, "validate the config, print a JSON summary and exit")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()
	log.SetFlags(log.LstdFlags)

	if *showVersion {
		fmt.Println(version)
		return
	}
	if *configPath == "" {
		fatalf("-config is required")
	}
	f, err := os.Open(*configPath)
	if err != nil {
		fatalf("%v", err)
	}
	cfg, err := ParseConfig(f)
	f.Close()
	if *check {
		printCheck(cfg, err)
		if err != nil {
			os.Exit(2)
		}
		return
	}
	if err != nil {
		// Leave the reason where the app looks for it; otherwise a config the
		// app accepted but this parser rejects would just look like a crash.
		writeErrorStatus(*statusPath, "config: "+err.Error())
		fatalf("config: %v", err)
	}

	host, _ := splitHost(*socksAddr)
	if ip, err := netip.ParseAddr(host); err != nil || !ip.IsLoopback() {
		fatalf("-socks must be a loopback address, got %q", *socksAddr)
	}

	if *upstream != "" {
		h, _ := splitHost(*upstream)
		if ip, err := netip.ParseAddr(h); err != nil || !ip.IsLoopback() {
			fatalf("-upstream must be a loopback address, got %q", *upstream)
		}
	}

	r := newRunner(cfg, newSystemResolver(splitList(*bootstrap)), *statusPath)
	r.configPath = *configPath
	r.upstream = *upstream
	r.expose = exposes
	err = r.run(*socksAddr)
	switch {
	case errors.Is(err, errReload):
		// The launcher runs us in a loop that restarts on this exit code.
		os.Exit(exitReload)
	case err != nil:
		r.writeStatus("error", err.Error())
		fatalf("%v", err)
	}
}

// exitReload tells the launcher's start loop that the config file changed and
// the process should be started again with the new one. Any other exit code
// (SIGTERM from stop, kill -9, a fatal error) ends the loop.
const exitReload = 3

var errReload = errors.New("config changed")

type runner struct {
	cfg         *Config
	sys         *net.Resolver
	statusPath  string
	configPath  string
	upstream    string
	expose      []exposeSpec
	configStamp fileStamp

	tnet *netstack.Net
	dev  *device.Device

	mu        sync.Mutex
	endpoints map[int]netip.AddrPort
	lastErr   string
}

func newRunner(cfg *Config, sys *net.Resolver, statusPath string) *runner {
	return &runner{cfg: cfg, sys: sys, statusPath: statusPath, endpoints: map[int]netip.AddrPort{}}
}

type fileStamp struct {
	size  int64
	mtime time.Time
}

func stampOf(path string) fileStamp {
	fi, err := os.Stat(path)
	if err != nil {
		return fileStamp{}
	}
	return fileStamp{fi.Size(), fi.ModTime()}
}

func (r *runner) configChanged() bool {
	return r.configPath != "" && stampOf(r.configPath) != r.configStamp
}

func (r *runner) run(socksAddr string) error {
	if r.configPath != "" {
		r.configStamp = stampOf(r.configPath)
	}
	var addrs []netip.Addr
	for _, p := range r.cfg.Addresses {
		addrs = append(addrs, p.Addr())
	}
	tunDev, tnet, err := netstack.CreateNetTUN(addrs, r.cfg.DNS, r.cfg.MTU)
	if err != nil {
		return fmt.Errorf("netstack: %w", err)
	}
	r.tnet = tnet

	logger := device.NewLogger(device.LogLevelError, "wg: ")
	r.dev = device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	defer r.dev.Close()

	// The endpoint is often a DDNS name and mobile data may not be up yet when
	// the daemon starts after boot. Keep trying instead of exiting; the
	// launcher's watchdog would just restart us into the same situation.
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	r.writeStatus("connecting", "")
	for attempt := 0; ; attempt++ {
		err = r.resolveEndpoints(false)
		if err == nil {
			break
		}
		r.setErr(err.Error())
		r.writeStatus("connecting", err.Error())
		log.Printf("endpoint: %v", err)
		select {
		case <-stop:
			r.writeStatus("stopped", "")
			return nil
		case <-time.After(backoff(attempt)):
		}
		if r.configChanged() {
			return errReload
		}
	}
	if err := r.dev.IpcSet(r.cfg.UAPI(r.snapshotEndpoints())); err != nil {
		return fmt.Errorf("device config: %w", err)
	}
	if err := r.dev.Up(); err != nil {
		return fmt.Errorf("device up: %w", err)
	}

	ln, err := net.Listen("tcp", socksAddr)
	if err != nil {
		return fmt.Errorf("socks listen: %w", err)
	}
	defer ln.Close()
	srv := &socksServer{dial: r.dial}
	go func() {
		if err := srv.serve(ln); err != nil {
			log.Printf("socks: %v", err)
		}
	}()
	for _, sp := range r.expose {
		es := newExposeServer(sp)
		defer es.close()
		for _, a := range r.cfg.Addresses {
			l, err := r.tnet.ListenTCPAddrPort(netip.AddrPortFrom(a.Addr(), sp.Port))
			if err != nil {
				return fmt.Errorf("expose %d on %s: %w", sp.Port, a.Addr(), err)
			}
			go es.serve(l)
			log.Printf("exposing %s -> %s (PROXY v1)", netip.AddrPortFrom(a.Addr(), sp.Port), sp.Backend)
		}
	}
	log.Printf("wgproxy %s up, socks5 on %s, routes %v", version, socksAddr, r.cfg.Routes())

	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	lastReresolve := time.Now()
	for {
		select {
		case <-stop:
			r.writeStatus("stopped", "")
			return nil
		case <-tick.C:
			if r.configChanged() {
				log.Printf("config changed, reloading")
				r.writeStatus("reloading", "")
				return errReload
			}
			st := r.peerStats()
			// Same idea as wireguard-tools' reresolve-dns.sh: if a peer has
			// not completed a handshake for longer than the rekey timeout,
			// its DDNS name may point somewhere else now.
			if time.Since(lastReresolve) > 30*time.Second && st.stale() {
				lastReresolve = time.Now()
				if err := r.resolveEndpoints(true); err != nil {
					r.setErr(err.Error())
					log.Printf("re-resolve: %v", err)
				}
			}
			r.writeStatusWith(st)
		}
	}
}

func backoff(attempt int) time.Duration {
	d := time.Duration(2<<min(attempt, 4)) * time.Second // 2s .. 32s
	return min(d, 30*time.Second)
}

// resolveEndpoints resolves every peer endpoint. When update is true, peers
// whose address changed are pushed to the running device.
func (r *runner) resolveEndpoints(update bool) error {
	var errs []string
	for i, p := range r.cfg.Peers {
		if p.Endpoint == "" {
			continue
		}
		host, port, _ := splitEndpoint(p.Endpoint)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		ap, err := r.resolveEndpoint(ctx, host, port)
		cancel()
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", p.Endpoint, err))
			continue
		}
		r.mu.Lock()
		old, had := r.endpoints[i]
		r.endpoints[i] = ap
		r.mu.Unlock()
		if update && had && old != ap {
			log.Printf("endpoint %s moved %s -> %s", p.Endpoint, old, ap)
			uapi := fmt.Sprintf("public_key=%s\nupdate_only=true\nendpoint=%s\n", p.PublicKey, ap)
			if err := r.dev.IpcSet(uapi); err != nil {
				errs = append(errs, err.Error())
			}
		}
	}
	if len(errs) > 0 {
		return errors.New(strings.Join(errs, "; "))
	}
	r.setErr("")
	return nil
}

func (r *runner) resolveEndpoint(ctx context.Context, host string, port uint16) (netip.AddrPort, error) {
	if a, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(a.Unmap(), port), nil
	}
	ips, err := r.sys.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return netip.AddrPort{}, err
	}
	// Prefer IPv4: most mobile carriers here hand out IPv4-only APNs and an
	// AAAA answer would leave the tunnel silently dead.
	best := ips[0]
	for _, ip := range ips {
		if ip.Unmap().Is4() {
			best = ip
			break
		}
	}
	return netip.AddrPortFrom(best.Unmap(), port), nil
}

func (r *runner) snapshotEndpoints() map[int]netip.AddrPort {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[int]netip.AddrPort, len(r.endpoints))
	for k, v := range r.endpoints {
		out[k] = v
	}
	return out
}

func (r *runner) inTunnel(a netip.Addr) bool {
	for _, p := range r.cfg.Routes() {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

var direct = &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}

// dial picks the route for one SOCKS request:
//   - IP inside AllowedIPs: through the tunnel.
//   - Name: the tunnel's DNS (DNS= in the config) is asked first, so names
//     that only exist at home work; an answer inside AllowedIPs goes through
//     the tunnel. Otherwise the name is handed to sing-box unresolved when it
//     is the upstream, or resolved by the system resolver and dialled directly.
//   - Anything else: sing-box if running, else direct.
func (r *runner) dial(ctx context.Context, host string, port uint16) (net.Conn, error) {
	if a, err := netip.ParseAddr(host); err == nil {
		return r.dialAddr(ctx, netip.AddrPortFrom(a.Unmap(), port))
	}
	// Ask the home DNS only while the tunnel is actually up. Otherwise every
	// public name would wait out netstack's DNS timeouts first.
	if len(r.cfg.DNS) > 0 && r.tunnelUp() {
		lctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		names, err := r.tnet.LookupContextHost(lctx, host)
		cancel()
		if err == nil {
			var lastErr error
			for _, n := range names {
				a, err := netip.ParseAddr(n)
				if err != nil || !r.inTunnel(a.Unmap()) {
					continue
				}
				c, err := r.tnet.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(a.Unmap(), port))
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			if lastErr != nil {
				return nil, lastErr
			}
		}
	}
	if r.upstream != "" {
		return dialViaConnect(ctx, r.upstream, net.JoinHostPort(host, strconv.Itoa(int(port))))
	}
	ips, err := r.sys.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for _, ip := range ips {
		c, err := r.dialAddr(ctx, netip.AddrPortFrom(ip.Unmap(), port))
		if err == nil {
			return c, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (r *runner) dialAddr(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	if r.inTunnel(dst.Addr()) {
		return r.tnet.DialContextTCPAddrPort(ctx, dst)
	}
	if r.upstream != "" {
		return dialViaConnect(ctx, r.upstream, dst.String())
	}
	return direct.DialContext(ctx, "tcp", dst.String())
}

// newSystemResolver works around a static Go binary on Android: there is no
// /etc/resolv.conf, so the pure-Go resolver would ask 127.0.0.1:53 and fail.
// Use the DNS servers Android reports via getprop where available, then the
// bootstrap list.
func newSystemResolver(bootstrap []string) *net.Resolver {
	servers := append(androidDNS(), bootstrap...)
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var lastErr error
			for _, s := range servers {
				d := net.Dialer{Timeout: 3 * time.Second}
				c, err := d.DialContext(ctx, network, s)
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func androidDNS() []string {
	var out []string
	for i := 1; i <= 4; i++ {
		b, err := exec.Command("/system/bin/getprop", "net.dns"+strconv.Itoa(i)).Output()
		if err != nil {
			break
		}
		if a, err := netip.ParseAddr(strings.TrimSpace(string(b))); err == nil && !a.IsLoopback() {
			out = append(out, netip.AddrPortFrom(a, 53).String())
		}
	}
	return out
}

func (r *runner) setErr(s string) {
	r.mu.Lock()
	r.lastErr = s
	r.mu.Unlock()
}

// tunnelUp reports whether any peer completed a handshake recently.
func (r *runner) tunnelUp() bool {
	now := time.Now().Unix()
	for _, p := range r.peerStats() {
		if p.LastHandshake > 0 && now-p.LastHandshake < 180 {
			return true
		}
	}
	return false
}

type peerStat struct {
	PublicKey     string `json:"-"`
	Endpoint      string `json:"endpoint"`
	LastHandshake int64  `json:"last_handshake"`
	RxBytes       int64  `json:"rx_bytes"`
	TxBytes       int64  `json:"tx_bytes"`
}

type peerStats []peerStat

// stale reports whether any peer with an endpoint has gone more than three
// minutes without a handshake (rekey happens every two).
func (s peerStats) stale() bool {
	now := time.Now().Unix()
	for _, p := range s {
		if p.Endpoint != "" && now-p.LastHandshake > 180 {
			return true
		}
	}
	return false
}

func (r *runner) peerStats() peerStats {
	if r.dev == nil {
		return nil
	}
	dump, err := r.dev.IpcGet()
	if err != nil {
		return nil
	}
	var out peerStats
	var cur *peerStat
	for _, line := range strings.Split(dump, "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch k {
		case "public_key":
			out = append(out, peerStat{PublicKey: v})
			cur = &out[len(out)-1]
		case "endpoint":
			if cur != nil {
				cur.Endpoint = v
			}
		case "last_handshake_time_sec":
			if cur != nil {
				cur.LastHandshake, _ = strconv.ParseInt(v, 10, 64)
			}
		case "rx_bytes":
			if cur != nil {
				cur.RxBytes, _ = strconv.ParseInt(v, 10, 64)
			}
		case "tx_bytes":
			if cur != nil {
				cur.TxBytes, _ = strconv.ParseInt(v, 10, 64)
			}
		}
	}
	return out
}

type status struct {
	State     string     `json:"state"`
	Error     string     `json:"error,omitempty"`
	Version   string     `json:"version"`
	PID       int        `json:"pid"`
	Updated   int64      `json:"updated"`
	Addresses []string   `json:"addresses"`
	Routes    []string   `json:"routes"`
	Expose    []int      `json:"expose,omitempty"`
	Peers     []peerStat `json:"peers"`
}

func (r *runner) writeStatus(state, errMsg string) {
	r.writeStatusFull(state, errMsg, r.peerStats())
}

func (r *runner) writeStatusWith(st peerStats) {
	state := "connected"
	connected := false
	now := time.Now().Unix()
	for _, p := range st {
		if p.LastHandshake > 0 && now-p.LastHandshake < 180 {
			connected = true
		}
	}
	if !connected {
		state = "connecting"
	}
	r.mu.Lock()
	e := r.lastErr
	r.mu.Unlock()
	r.writeStatusFull(state, e, st)
}

func (r *runner) writeStatusFull(state, errMsg string, st peerStats) {
	if r.statusPath == "" {
		return
	}
	s := status{
		State:   state,
		Error:   errMsg,
		Version: version,
		PID:     os.Getpid(),
		Updated: time.Now().Unix(),
		Peers:   st,
	}
	for _, a := range r.cfg.Addresses {
		s.Addresses = append(s.Addresses, a.String())
	}
	for _, p := range r.cfg.Routes() {
		s.Routes = append(s.Routes, p.String())
	}
	for _, sp := range r.expose {
		s.Expose = append(s.Expose, int(sp.Port))
	}
	if s.Peers == nil {
		s.Peers = []peerStat{}
	}
	b, _ := json.Marshal(s)
	tmp := r.statusPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err == nil {
		_ = os.Rename(tmp, r.statusPath)
	}
}

func writeErrorStatus(path, msg string) {
	if path == "" {
		return
	}
	b, _ := json.Marshal(status{State: "error", Error: msg, Version: version, PID: os.Getpid(),
		Updated: time.Now().Unix(), Addresses: []string{}, Routes: []string{}, Peers: []peerStat{}})
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err == nil {
		_ = os.Rename(tmp, path)
	}
}

type checkResult struct {
	OK        bool     `json:"ok"`
	Error     string   `json:"error,omitempty"`
	Addresses []string `json:"addresses,omitempty"`
	Endpoints []string `json:"endpoints,omitempty"`
	Routes    []string `json:"routes,omitempty"`
	DNS       []string `json:"dns,omitempty"`
}

func printCheck(cfg *Config, err error) {
	res := checkResult{OK: err == nil}
	if err != nil {
		res.Error = err.Error()
	} else {
		for _, a := range cfg.Addresses {
			res.Addresses = append(res.Addresses, a.String())
		}
		for _, p := range cfg.Peers {
			if p.Endpoint != "" {
				res.Endpoints = append(res.Endpoints, p.Endpoint)
			}
		}
		for _, p := range cfg.Routes() {
			res.Routes = append(res.Routes, p.String())
		}
		for _, d := range cfg.DNS {
			res.DNS = append(res.DNS, d.String())
		}
	}
	b, _ := json.Marshal(res)
	fmt.Println(string(b))
}

func splitHost(hostport string) (string, string) {
	h, p, err := net.SplitHostPort(hostport)
	if err != nil {
		return hostport, ""
	}
	return h, p
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "wgproxy: "+format+"\n", args...)
	os.Exit(1)
}
