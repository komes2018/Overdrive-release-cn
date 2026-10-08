package com.overdrive.app.wireguard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validator for the wg-quick style config that wgproxy reads. It mirrors the
 * rules of tools/wireguard/config.go so a config is rejected here, with a
 * readable message, instead of failing silently inside the daemon later.
 *
 * <p>Pure Java and free of DNS lookups: endpoint host names are never resolved,
 * addresses are parsed as literals.
 */
public final class WireGuardConfig {

    /** Hard cap on the config text, in UTF-8 bytes. */
    public static final int MAX_INPUT_BYTES = 16 * 1024;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9+/]{43}=");

    private WireGuardConfig() {}

    /** What the config will do, without any key material. */
    public static final class Summary {
        public final List<String> addresses;
        public final List<String> endpoints;
        public final List<String> routes;
        public final List<String> dns;
        public final int peerCount;

        Summary(List<String> addresses, List<String> endpoints, List<String> routes,
                List<String> dns, int peerCount) {
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
            this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
            this.dns = Collections.unmodifiableList(new ArrayList<>(dns));
            this.peerCount = peerCount;
        }

        /** JSON for the UI and the web page. Never contains keys. */
        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("addresses", new JSONArray(addresses));
                o.put("endpoints", new JSONArray(endpoints));
                o.put("routes", new JSONArray(routes));
                o.put("dns", new JSONArray(dns));
                o.put("peerCount", peerCount);
                return o;
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Peer {
        boolean hasPublicKey;
        String endpoint;
        final List<String> allowedIps = new ArrayList<>();
    }

    /**
     * Parse and validate a config.
     *
     * @throws IllegalArgumentException with a short message for the user, which
     *         carries the line number when a single line is at fault
     */
    public static Summary parse(String text) {
        if (text == null) throw new IllegalArgumentException("Config is empty");
        if (text.startsWith("﻿")) text = text.substring(1);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Config is too large (max 16 KB)");
        }
        if (trimWs(text.replace("\r", "").replace("\n", "")).isEmpty()) throw new IllegalArgumentException("Config is empty");

        boolean hasPrivateKey = false;
        List<String> addresses = new ArrayList<>();
        List<String> dns = new ArrayList<>();
        List<Peer> peers = new ArrayList<>();
        Peer peer = null;
        String section = "";

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int c = indexOfComment(line);
            if (c >= 0) line = line.substring(0, c);
            line = trimWs(line);
            if (line.isEmpty()) continue;
            if (!isPlainAscii(line)) throw fail(lineNo, "invalid character");

            if (line.startsWith("[") && line.endsWith("]")) {
                section = trimWs(line.substring(1, line.length() - 1)).toLowerCase(Locale.ROOT);
                if (section.equals("peer")) {
                    peer = new Peer();
                    peers.add(peer);
                } else if (!section.equals("interface")) {
                    throw fail(lineNo, "unknown section [" + shown(section) + "]");
                }
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) throw fail(lineNo, "expected key = value");
            String rawKey = trimWs(line.substring(0, eq));
            String key = rawKey.toLowerCase(Locale.ROOT);
            String val = trimWs(line.substring(eq + 1));

            try {
                if (section.equals("interface")) {
                    switch (key) {
                        case "privatekey":
                            checkKey(val, "PrivateKey");
                            hasPrivateKey = true;
                            break;
                        case "address":
                            for (String s : splitList(val)) addresses.add(parsePrefix(s, "Address", false));
                            break;
                        case "dns":
                            for (String s : splitList(val)) {
                                if (s.indexOf('%') >= 0) {
                                    throw new IllegalArgumentException("DNS: zone identifiers are not supported");
                                }
                                // Non-IP entries are search domains, irrelevant here.
                                if (parseAddr(s) != null) dns.add(s);
                            }
                            break;
                        case "mtu":
                            checkMtu(val);
                            break;
                        case "listenport": case "table": case "fwmark": case "saveconfig":
                        case "preup": case "postup": case "predown": case "postdown":
                            break; // kernel / wg-quick only
                        default:
                            throw new IllegalArgumentException("unknown Interface key \"" + shownKey(rawKey) + "\"");
                    }
                } else if (section.equals("peer")) {
                    switch (key) {
                        case "publickey":
                            checkKey(val, "PublicKey");
                            peer.hasPublicKey = true;
                            break;
                        case "presharedkey":
                            checkKey(val, "PresharedKey");
                            break;
                        case "endpoint":
                            checkEndpoint(val);
                            peer.endpoint = val;
                            break;
                        case "allowedips":
                            for (String s : splitList(val)) peer.allowedIps.add(parsePrefix(s, "AllowedIPs", true));
                            break;
                        case "persistentkeepalive":
                            checkKeepalive(val);
                            break;
                        default:
                            throw new IllegalArgumentException("unknown Peer key \"" + shownKey(rawKey) + "\"");
                    }
                } else {
                    throw new IllegalArgumentException(shownKey(rawKey) + " is outside of a section");
                }
            } catch (IllegalArgumentException e) {
                throw fail(lineNo, e.getMessage());
            }
        }

        if (!hasPrivateKey) throw new IllegalArgumentException("[Interface] PrivateKey is missing");
        if (addresses.isEmpty()) throw new IllegalArgumentException("[Interface] Address is missing");
        if (peers.isEmpty()) throw new IllegalArgumentException("No [Peer] section");

        boolean hasEndpoint = false;
        Set<String> routes = new LinkedHashSet<>();
        Set<String> endpoints = new LinkedHashSet<>();
        for (int i = 0; i < peers.size(); i++) {
            Peer p = peers.get(i);
            if (!p.hasPublicKey) throw new IllegalArgumentException("Peer " + (i + 1) + ": PublicKey is missing");
            if (p.allowedIps.isEmpty()) throw new IllegalArgumentException("Peer " + (i + 1) + ": AllowedIPs is missing");
            if (p.endpoint != null) {
                hasEndpoint = true;
                endpoints.add(p.endpoint);
            }
            routes.addAll(p.allowedIps);
        }
        if (!hasEndpoint) {
            throw new IllegalArgumentException(
                    "No peer has an Endpoint. The car dials out, so the peer needs Endpoint = host:port");
        }
        return new Summary(addresses, new ArrayList<>(endpoints), new ArrayList<>(routes), dns, peers.size());
    }

    private static IllegalArgumentException fail(int lineNo, String msg) {
        return new IllegalArgumentException("line " + lineNo + ": " + msg);
    }

    private static int indexOfComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '#' || ch == ';') return i;
        }
        return -1;
    }

    /** Trims only space and tab, unlike String.trim() which strips all control characters. */
    private static String trimWs(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }

    private static boolean isPlainAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\t' && (ch < 0x20 || ch > 0x7E)) return false;
        }
        return true;
    }

    /** User input for an error message, capped so a pasted key cannot end up in it. */
    private static String shown(String s) {
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /** First word of a key name, capped; the rest of the left-hand side may be key material. */
    private static String shownKey(String raw) {
        int sp = 0;
        while (sp < raw.length() && raw.charAt(sp) != ' ' && raw.charAt(sp) != '\t') sp++;
        return shown(raw.substring(0, sp));
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String f : s.split(",")) {
            f = trimWs(f);
            if (!f.isEmpty()) out.add(f);
        }
        return out;
    }

    private static void checkKey(String val, String name) {
        if (!KEY.matcher(val).matches()) {
            throw new IllegalArgumentException(
                    name + " is not a WireGuard key (expected 44 characters of base64)");
        }
    }

    private static void checkMtu(String val) {
        if (val.matches("[0-9]{1,5}")) {
            int n = Integer.parseInt(val);
            if (n >= 576 && n <= 9000) return;
        }
        throw new IllegalArgumentException("MTU: invalid value \"" + shown(val) + "\"");
    }

    private static void checkKeepalive(String val) {
        if (val.equalsIgnoreCase("off")) return;
        if (val.matches("[0-9]{1,5}")) {
            int n = Integer.parseInt(val);
            if (n <= 65535) return;
        }
        throw new IllegalArgumentException("PersistentKeepalive: invalid value \"" + shown(val) + "\"");
    }

    /** host:port or [v6]:port; the host is not resolved. */
    private static void checkEndpoint(String val) {
        String host;
        String port;
        if (val.startsWith("[")) {
            int end = val.indexOf(']');
            if (end < 0 || end + 1 >= val.length() || val.charAt(end + 1) != ':') {
                throw badEndpoint();
            }
            host = val.substring(1, end);
            port = val.substring(end + 2);
            if (host.indexOf(':') < 0 || parseAddr(host) == null) throw badEndpoint();
        } else {
            int colon = val.lastIndexOf(':');
            if (colon < 0) throw badEndpoint();
            host = val.substring(0, colon);
            port = val.substring(colon + 1);
            if (host.indexOf(':') >= 0 || host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
                throw new IllegalArgumentException(
                        "Endpoint: an IPv6 address needs brackets, like [2001:db8::1]:51820");
            }
        }
        if (host.isEmpty()) throw badEndpoint();
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) throw badEndpoint();
        }
        if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("Endpoint: invalid port \"" + shown(port) + "\"");
        }
    }

    private static IllegalArgumentException badEndpoint() {
        return new IllegalArgumentException("Endpoint: expected host:port, like vpn.example.org:51820");
    }

    /**
     * Prefix ("10.0.0.2/24") or bare IP (becomes /32 or /128). With mask=true the
     * host bits are cleared, as the daemon does for AllowedIPs.
     */
    private static String parsePrefix(String s, String name, boolean mask) {
        String addrPart = s;
        int bits = -1;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            addrPart = s.substring(0, slash);
            String b = s.substring(slash + 1);
            if (!b.matches("[0-9]{1,3}") || (b.length() > 1 && b.charAt(0) == '0')) {
                throw new IllegalArgumentException(name + ": invalid prefix \"" + shown(s) + "\"");
            }
            bits = Integer.parseInt(b);
        }
        int[] a = parseAddr(addrPart);
        if (a == null) throw new IllegalArgumentException(name + ": invalid address \"" + shown(s) + "\"");
        boolean v4 = a.length == 4;
        int max = v4 ? 32 : 128;
        if (bits < 0) bits = max;
        if (bits > max) throw new IllegalArgumentException(name + ": invalid prefix \"" + shown(s) + "\"");
        if (mask) {
            int unit = v4 ? 8 : 16;
            for (int i = 0; i < a.length; i++) {
                int keep = Math.max(0, Math.min(unit, bits - i * unit));
                int m = keep == 0 ? 0 : ((1 << unit) - 1) & ~((1 << (unit - keep)) - 1);
                a[i] &= m;
            }
        }
        return format(a) + "/" + bits;
    }

    /** Parse an IP literal into 4 octets or 8 groups; null if it is not one. */
    private static int[] parseAddr(String s) {
        if (s.indexOf(':') >= 0) return parseV6(s);
        return parseV4(s);
    }

    private static int[] parseV4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (!p.matches("[0-9]{1,3}") || (p.length() > 1 && p.charAt(0) == '0')) return null;
            int n = Integer.parseInt(p);
            if (n > 255) return null;
            out[i] = n;
        }
        return out;
    }

    private static int[] parseV6(String s) {
        // A trailing dotted quad stands for the last two groups.
        int lastColon = s.lastIndexOf(':');
        String tail = s.substring(lastColon + 1);
        if (tail.indexOf('.') >= 0) {
            int[] v4 = parseV4(tail);
            if (v4 == null) return null;
            s = s.substring(0, lastColon + 1)
                    + Integer.toHexString((v4[0] << 8) | v4[1]) + ":"
                    + Integer.toHexString((v4[2] << 8) | v4[3]);
        }
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null;
        List<Integer> head = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        if (dbl >= 0) {
            if (!groups(s.substring(0, dbl), head) || !groups(s.substring(dbl + 2), rest)) return null;
            if (head.size() + rest.size() > 7) return null;
        } else {
            if (!groups(s, head) || head.size() != 8) return null;
        }
        int[] out = new int[8];
        for (int i = 0; i < head.size(); i++) out[i] = head.get(i);
        for (int i = 0; i < rest.size(); i++) out[8 - rest.size() + i] = rest.get(i);
        return out;
    }

    private static boolean groups(String s, List<Integer> out) {
        if (s.isEmpty()) return true;
        for (String g : s.split(":", -1)) {
            if (!g.matches("[0-9A-Fa-f]{1,4}")) return false;
            out.add(Integer.parseInt(g, 16));
        }
        return true;
    }

    private static String format(int[] a) {
        StringBuilder sb = new StringBuilder();
        if (a.length == 4) {
            return a[0] + "." + a[1] + "." + a[2] + "." + a[3];
        }
        // Compress the longest run of zero groups (RFC 5952).
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (a[i] != 0) { i++; continue; }
            int j = i;
            while (j < 8 && a[j] == 0) j++;
            if (j - i > bestLen) { bestStart = i; bestLen = j - i; }
            i = j;
        }
        if (bestLen < 2) bestStart = -1;
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') sb.append(':');
            sb.append(Integer.toHexString(a[i]));
        }
        return sb.toString();
    }
}
