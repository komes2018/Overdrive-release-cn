package com.overdrive.app.server;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Authentication middleware for HttpServer.
 *
 * Two-tier authentication:
 *
 *   Tier 1 — JWT (cookie or Authorization: Bearer): primary, all callers should use this.
 *
 *   Tier 2 — loopback safety net: requests originating from 127.0.0.1 are
 *            trusted ONLY when the request carries no tunnel-fingerprint
 *            headers (X-Forwarded-*, Cf-*, X-Real-Ip, Forwarded). This
 *            keeps developer-tools and ADB shell access working while
 *            blocking traffic relayed via cloudflared / zrok / ngrok which
 *            all forward to localhost but inject these proxy headers.
 *
 * Public paths (no auth required at all):
 * - /auth/token       - Login endpoint (must be reachable)
 * - /auth/logout      - Logout endpoint (idempotent)
 * - /login.html       - Login page UI
 * - /login            - Login alias
 * - /shared/*         - Static assets (CSS, JS, fonts, models)
 * - /favicon.ico      - Browser favicon
 *
 * Notably NOT public anymore:
 * - /status           - leaks ACC/charging/recording state, requires auth
 * - /auth/status      - leaks deviceId, requires auth
 */
public class AuthMiddleware {

    // Paths that don't require authentication
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
        "/auth/status",  // Login page needs deviceId hint before user has JWT
        "/auth/token",
        "/auth/logout",
        "/login.html",
        "/login",
        "/favicon.ico",
        // PWA install assets — the browser fetches these as part of service-
        // worker registration and manifest discovery, with no Bearer header
        // (browser-internal fetch, not auth.js-wrapped).
        "/manifest.json",
        "/sw.js"
    ));

    // Path prefixes that don't require authentication
    private static final String[] PUBLIC_PREFIXES = {
        "/shared/", // Static assets (CSS, JS, fonts, models)
        "/i18n/"    // Language files
    };

    // Cookie name for JWT
    private static final String JWT_COOKIE_NAME = "byd_session";

    /**
     * Check if request is authenticated.
     */
    public static boolean checkAuth(String path, String cookieHeader, String authHeader, OutputStream out) throws Exception {
        return checkAuth(path, cookieHeader, authHeader, out, null, false);
    }

    /**
     * Check if request is authenticated (with client address only).
     * Backwards-compat overload — assumes no tunnel headers (caller didn't pass them).
     */
    public static boolean checkAuth(String path, String cookieHeader, String authHeader,
                                     OutputStream out, java.net.SocketAddress clientAddress) throws Exception {
        return checkAuth(path, cookieHeader, authHeader, out, clientAddress, false);
    }

    /**
     * Full check with tunnel-header awareness.
     *
     * @param path Request path
     * @param cookieHeader Cookie header value
     * @param authHeader Authorization header value
     * @param out Output stream (for sending 401/redirect)
     * @param clientAddress Client socket address (for loopback Tier-2)
     * @param hasTunnelHeaders true if request carries reverse-proxy fingerprints
     *                        (X-Forwarded-*, Cf-*, X-Real-Ip, Forwarded). When
     *                        true, the loopback safety net is disabled.
     * @return true if authenticated or public path, false if should block
     */
    public static boolean checkAuth(String path, String cookieHeader, String authHeader,
                                     OutputStream out, java.net.SocketAddress clientAddress,
                                     boolean hasTunnelHeaders) throws Exception {
        // Tier 0 — public paths (login UI, static assets, login submission)
        if (isPublicPath(path)) {
            return true;
        }

        // Tier 0.5 — signed thumb token. Browsers and Web Push service
        // workers fetch /thumb/<file>?t=<jws> as a plain HTTPS GET (no
        // Authorization header is available because the fetch happens
        // inside the OS notification banner, FCM image fetch, or iOS
        // notification service). Accept the request iff the token's
        // {@code sub} claim matches the requested filename and it is not
        // expired.
        if (path.startsWith("/thumb/")) {
            String[] split = splitPathAndQuery(path);
            String pathOnly = split[0];
            String token = queryParam(split[1], "t");
            if (token != null) {
                String filename = pathOnly.substring("/thumb/".length());
                String decoded = urlDecode(filename);
                if (AuthManager.validateThumbToken(decoded, token)
                        || AuthManager.validateThumbToken(filename, token)) {
                    return true;
                }
            }
        }
        // Same mechanism for Parking Intelligence stills used as push banner
        // images: /parking/asset/<sessionId>/<file>?t=<jws>, token subject
        // "<sessionId>/<file>". A JWT-carrying page load falls through to Tier 1.
        if (path.startsWith("/parking/asset/")) {
            String[] split = splitPathAndQuery(path);
            String token = queryParam(split[1], "t");
            if (token != null) {
                String subject = split[0].substring("/parking/asset/".length());
                String decoded = urlDecode(subject);
                if (AuthManager.validateThumbToken(decoded, token)
                        || AuthManager.validateThumbToken(subject, token)) {
                    return true;
                }
            }
        }

        // Tier 1 — JWT validation. This is the primary path: WebView (cookie),
        // frontend pages (Authorization header via auth.js), native callers
        // (cookie via DaemonHttpClient).
        String jwt = jwtFromHeaders(cookieHeader, authHeader);
        if (jwt != null && !jwt.isEmpty()) {
            AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
            if (validation.valid) {
                return true;
            }
            // JWT was provided but invalid — log and fall through to Tier 2
            // (a stale cached JWT shouldn't lock out a legitimate same-device caller).
            log("JWT invalid for " + path + ": " + validation.error);
        }

        // Tier 2 — loopback safety net. Trust 127.0.0.1 / ::1 ONLY when no
        // tunnel-fingerprint headers are present. Reverse proxies (cloudflared,
        // zrok, ngrok) all forward to localhost but inject these headers — so
        // their absence is a strong signal we're talking to a same-device
        // caller. Defense in depth alongside Tier 1.
        if (!hasTunnelHeaders && clientAddress != null) {
            String addrStr = clientAddress.toString();
            boolean isLoopback = addrStr.contains("127.0.0.1") || addrStr.contains("/0:0:0:0:0:0:0:1");
            if (isLoopback) {
                return true;
            }
        }

        return handleUnauthorized(path, out,
            jwt == null || jwt.isEmpty() ? "No session token" : "Invalid session token");
    }

    /**
     * Require a valid JWT with no public-path or loopback fallback.
     * Use this for sensitive surfaces where same-device shell access must not
     * silently become browser control.
     */
    public static boolean checkJwtOnly(String path, String cookieHeader, String authHeader,
                                       OutputStream out) throws Exception {
        String jwt = jwtFromHeaders(cookieHeader, authHeader);
        if (jwt != null && !jwt.isEmpty()) {
            AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
            if (validation.valid) {
                return true;
            }
            log("JWT invalid for " + path + ": " + validation.error);
        }
        return handleUnauthorized(path, out,
            jwt == null || jwt.isEmpty() ? "No session token" : "Invalid session token");
    }

    private static String jwtFromHeaders(String cookieHeader, String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return extractJwtFromCookie(cookieHeader);
    }
    
    /**
     * Check if path is public (no auth required).
     */
    public static boolean isPublicPath(String path) {
        // Exact match
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        
        // Strip query string for matching
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        if (PUBLIC_PATHS.contains(pathOnly)) {
            return true;
        }
        
        // Prefix match
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Extract JWT from cookie header.
     */
    private static String extractJwtFromCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        
        // Parse cookies: "name1=value1; name2=value2"
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(JWT_COOKIE_NAME)) {
                return parts[1].trim();
            }
        }
        
        return null;
    }
    
    /**
     * Handle unauthorized request.
     * For API requests, returns 401 JSON.
     * For page requests, redirects to login.
     */
    private static boolean handleUnauthorized(String path, OutputStream out, String reason) throws Exception {
        log("Unauthorized: " + path + " - " + reason);
        
        // API requests get 401 JSON
        if (path.startsWith("/api/") || path.startsWith("/ws") || 
            path.startsWith("/snapshot/") || path.startsWith("/video/") ||
            path.startsWith("/thumb/") || path.startsWith("/h264/") || path.equals("/status")) {
            
            String json = "{\"error\":\"Unauthorized\",\"reason\":\"" + reason + "\",\"login\":\"/login.html\"}";
            HttpResponse.sendUnauthorized(out, json);
            return false;
        }
        
        // Page requests get redirected to login
        String redirectUrl = "/login.html?redirect=" + urlEncode(path);
        HttpResponse.sendRedirect(out, redirectUrl);
        return false;
    }
    
    /**
     * Simple URL encoding for redirect parameter.
     */
    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String[] splitPathAndQuery(String path) {
        int q = path.indexOf('?');
        if (q < 0) return new String[] { path, "" };
        return new String[] { path.substring(0, q), path.substring(q + 1) };
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (name.equals(pair.substring(0, eq))) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return null;
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
