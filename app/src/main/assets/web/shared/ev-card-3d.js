/**
 * Overdrive — EV-card 3D body shell.
 *
 * Mounts a tiny three.js scene inside the sidebar's EV-card and renders the
 * user's selected vehicle (GLB) tinted in their selected paint colour. Runs
 * on every page, but loads the THREE/GLTFLoader/DRACOLoader bundle lazily
 * AFTER the rest of the page has rendered — first paint of every other UI
 * surface is unaffected.
 *
 * Why GLB and not a pre-rendered PNG:
 *   - Real geometry, real material shading; tints to any user hex without
 *     baking colour combos.
 *   - Light auto-rotation makes the sidebar card feel alive next to the
 *     animated battery + charge overlays.
 *
 * Cost / safety:
 *   - Total vendor JS ~600 KB; cached after first hit. Loaded on demand
 *     so non-EV pages aren't slowed down.
 *   - One WebGL context per page, sized 280×130 CSS px (very small).
 *   - Render loop pauses when the document is hidden OR the canvas is
 *     off-screen (IntersectionObserver). On page unload the context is
 *     forcibly disposed so navigation never leaks contexts.
 *
 * Public API:
 *   var card = new OverdriveEvCard3D(canvasElement);
 *   card.setModel('seal');         // model id from manifest
 *   card.setColor('#1A1A1E');      // any #RRGGBB
 *   card.dispose();                // call before navigating away
 *
 * Asset resolution order:
 *   1. ../shared/models/<file>.glb   — bundled inside the APK
 *   2. /api/models/download + cache  — daemon-managed (production)
 *   3. manifest.baseUrl + <file>     — direct GitHub release (preview)
 *
 * ES5-friendly enough to run on Chrome 58 (BYD WebView floor) — written
 * with var / function expressions, no const / let / arrow functions.
 */
(function (root) {
    'use strict';

    // Idempotency guard. Two surfaces (app-shell.js sidebar mount + map
    // sprite renderer) both lazily inject this script on first need.
    // Without this guard a race between them would run the IIFE twice,
    // double-load three.min.js (which complains "Multiple instances of
    // Three.js"), and double-define OverdriveEvCard3D. Bail if we've
    // already initialised.
    if (root.OverdriveEvCard3D) return;

    var VENDOR = '../shared/vendor/';
    var SCRIPTS = [VENDOR + 'three.min.js',
                   VENDOR + 'GLTFLoader.js',
                   VENDOR + 'DRACOLoader.js'];

    // Vendor-load state lives on `window` so a second include of this
    // module (caught by the idempotency guard above, but defensive
    // anyway) wouldn't kick off a parallel vendor download.
    if (!root.__overdriveEvCard3dVendorPromise) {
        root.__overdriveEvCard3dVendorPromise = null;
    }

    function loadVendor() {
        if (root.__overdriveEvCard3dVendorPromise) return root.__overdriveEvCard3dVendorPromise;
        // Some other surface (vehicle-control.html bundles three.js
        // directly via <script> tags) may have THREE on the page already
        // — adopt those globals instead of reloading.
        if (typeof root.THREE !== 'undefined'
                && typeof root.THREE.GLTFLoader === 'function'
                && typeof root.THREE.DRACOLoader === 'function') {
            root.__overdriveEvCard3dVendorPromise = Promise.resolve();
            return root.__overdriveEvCard3dVendorPromise;
        }
        root.__overdriveEvCard3dVendorPromise = new Promise(function (resolve, reject) {
            // Already fully loaded by another page (vehicle-control.html
            // bundles all three eagerly). Verify EACH dependency, not just
            // THREE+GLTFLoader — older snapshots of this code only checked
            // GLTFLoader and missed the DRACOLoader being absent.
            if (typeof window.THREE !== 'undefined'
                    && typeof window.THREE.GLTFLoader === 'function'
                    && typeof window.THREE.DRACOLoader === 'function') {
                return resolve();
            }

            // Load strictly serially. async=false on dynamically-inserted
            // scripts is unreliable on Chrome 58 (BYD WebView floor), so
            // we chain via onload — the next <script> doesn't enter the
            // DOM until the previous one has executed its top-level code
            // that registers THREE.GLTFLoader / THREE.DRACOLoader.
            //
            // Browser-extension defence: many extensions (file-uploaders,
            // testing harnesses) inject `module` / `exports` / `define`
            // globals into the page. three.min.js's UMD wrapper sniffs
            // those FIRST and exports to `exports.THREE` instead of
            // `window.THREE`, leaving GLTFLoader.js's top-level
            // `class extends THREE.Loader` to throw because window.THREE
            // is still undefined. Strip those globals before each load,
            // restoring after, so three.min.js falls through to the
            // global-window branch.
            function loadOne(src) {
                return new Promise(function (res, rej) {
                    var savedExports = window.exports;
                    var savedModule  = window.module;
                    var savedDefine  = window.define;
                    try {
                        if ('exports' in window) try { delete window.exports; } catch (e) { window.exports = undefined; }
                        if ('module'  in window) try { delete window.module;  } catch (e) { window.module  = undefined; }
                        if ('define'  in window) try { delete window.define;  } catch (e) { window.define  = undefined; }
                    } catch (e) {}
                    var s = document.createElement('script');
                    s.src = src;
                    s.async = false;
                    s.onload = function () {
                        // Restore in case other code on the page expects
                        // these globals back.
                        if (savedExports !== undefined) window.exports = savedExports;
                        if (savedModule  !== undefined) window.module  = savedModule;
                        if (savedDefine  !== undefined) window.define  = savedDefine;
                        res();
                    };
                    s.onerror = function () {
                        if (savedExports !== undefined) window.exports = savedExports;
                        if (savedModule  !== undefined) window.module  = savedModule;
                        if (savedDefine  !== undefined) window.define  = savedDefine;
                        rej(new Error('failed to load ' + src));
                    };
                    document.head.appendChild(s);
                });
            }

            var chain = Promise.resolve();
            SCRIPTS.forEach(function (src) {
                chain = chain.then(function () { return loadOne(src); });
            });
            chain.then(function () {
                // Final assertion — fail loud rather than silently throw
                // "T.GLTFLoader is not a constructor" later.
                if (typeof window.THREE === 'undefined'
                        || typeof window.THREE.GLTFLoader !== 'function'
                        || typeof window.THREE.DRACOLoader !== 'function') {
                    var have = window.THREE
                        ? ('THREE keys: ' + Object.keys(window.THREE).slice(0, 6).join(','))
                        : 'window.THREE undefined';
                    return reject(new Error('Vendor load completed but ' + have));
                }
                resolve();
            }, reject);
        });
        return root.__overdriveEvCard3dVendorPromise;
    }

    // Minimal ModelStore. The real one in vehicle-control.js handles
    // download progress UI; we don't need that here — just resolve the URL.
    function resolveModelUrl(modelId, manifest, cb) {
        function findEntry() {
            if (!manifest || !manifest.models) return null;
            for (var i = 0; i < manifest.models.length; i++) {
                if (manifest.models[i].id === modelId) return manifest.models[i];
            }
            return null;
        }
        var entry = findEntry();
        if (!entry) { cb(null, 'unknown model: ' + modelId); return; }

        // Bundled GLBs always resolve through the relative path.
        if (entry.bundled) { cb('../shared/models/' + entry.file); return; }

        // Try the daemon's resolver first. If we're on a real BYD device,
        // /api/models/download exists and will fetch + persist; if we're
        // on the preview-server, the endpoint 404s and we fall through to
        // the GitHub baseUrl.
        var xhr = new XMLHttpRequest();
        try {
            xhr.open('POST', '/api/models/download?id=' + encodeURIComponent(modelId), true);
            xhr.timeout = 4000;
            xhr.onload = function () {
                // Daemon ack OR cached. Poll status briefly; if it's not
                // ready quickly we fall back to baseUrl since the sidebar
                // shouldn't block on a long fetch.
                pollDaemonStatus(modelId, entry, function (ok) {
                    if (ok) cb('../shared/models/' + entry.file);
                    else cb(githubUrl(entry, manifest));
                });
            };
            xhr.onerror = function () { cb(githubUrl(entry, manifest)); };
            xhr.ontimeout = function () { cb(githubUrl(entry, manifest)); };
            xhr.send();
        } catch (e) { cb(githubUrl(entry, manifest)); }
    }

    function githubUrl(entry, manifest) {
        var base = manifest && manifest.baseUrl ? manifest.baseUrl : '';
        return base + entry.file;
    }

    function pollDaemonStatus(modelId, entry, cb) {
        var attempts = 0;
        // 60s budget at 500ms intervals. Slow LTE + a 2-3MB GLB cold
        // download from GitHub release-assets can easily eat 15-30s, and
        // we'd rather wait for the daemon's local cache than have the
        // sidebar fall back to a direct GitHub fetch (which then also
        // bypasses SHA-256 verification).
        var maxAttempts = 120;
        function tick() {
            attempts++;
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/status?id=' + encodeURIComponent(modelId), true);
            xhr.timeout = 1500;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try {
                        var s = JSON.parse(xhr.responseText);
                        if (s.state === 'done' || s.downloaded === true) {
                            return cb(true);
                        }
                        if (s.state === 'error') return cb(false);
                    } catch (e) {}
                }
                if (attempts >= maxAttempts) return cb(false);
                setTimeout(tick, 500);
            };
            xhr.onerror = function () { cb(false); };
            xhr.ontimeout = function () { cb(false); };
            xhr.send();
        }
        tick();
    }

    // Cached manifest, shared across instances on the same page. The
    // promise cache (not just the resolved value) means concurrent calls
    // on first load also share the in-flight fetch instead of firing
    // duplicate XHRs.
    var manifestCache = null;
    var manifestPromise = null;
    function loadManifest() {
        if (manifestCache) return Promise.resolve(manifestCache);
        if (manifestPromise) return manifestPromise;
        manifestPromise = new Promise(function (resolve) {
            function fallbackManifest() {
                return {
                    version: 0,
                    'default': 'seal',
                    models: [{ id: 'seal', name: 'BYD Seal', file: 'seal.glb', bundled: true }]
                };
            }
            function fetchBundledManifest() {
                var localXhr = new XMLHttpRequest();
                localXhr.open('GET', '../shared/models/manifest.json', true);
                localXhr.timeout = 4000;
                localXhr.onload = function () {
                    if (localXhr.status >= 200 && localXhr.status < 300) {
                        try { manifestCache = JSON.parse(localXhr.responseText); }
                        catch (e) { manifestCache = fallbackManifest(); }
                        resolve(manifestCache);
                        return;
                    }
                    manifestCache = fallbackManifest();
                    resolve(manifestCache);
                };
                localXhr.onerror = function () {
                    manifestCache = fallbackManifest();
                    resolve(manifestCache);
                };
                localXhr.ontimeout = function () {
                    manifestCache = fallbackManifest();
                    resolve(manifestCache);
                };
                localXhr.send();
            }

            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/manifest', true);
            xhr.timeout = 4000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { manifestCache = JSON.parse(xhr.responseText); }
                    catch (e) { manifestCache = null; }
                    if (manifestCache) {
                        resolve(manifestCache);
                        return;
                    }
                }
                fetchBundledManifest();
            };
            xhr.onerror = function () { fetchBundledManifest(); };
            xhr.ontimeout = function () { fetchBundledManifest(); };
            xhr.send();
        });
        return manifestPromise;
    }

    /**
     * @param {HTMLCanvasElement} canvasEl
     * @param {{ view?: 'side'|'top'|'three-quarter' }} [opts]
     *   view='side' (default) — camera looks down the +Z axis at the GLB
     *     rotated π/2 around Y, so the car fills the canvas in side
     *     profile with the front pointing left. Used by the sidebar
     *     EV-card.
     *   view='top'  — camera looks straight down -Y so the canvas shows
     *     a roof-down silhouette with the front pointing UP. Used by the
     *     Live View camera selector so FRONT/REAR/LEFT/RIGHT hotspots
     *     align with the rendered body.
     *   view='three-quarter' — slightly angled profile for dashboard
     *     hero cards: keeps side readability while adding depth.
     */
    function OverdriveEvCard3D(canvasEl, opts) {
        this.canvas = canvasEl;
        this.view = 'side';
        if (opts && opts.view === 'top') this.view = 'top';
        else if (opts && opts.view === 'three-quarter') this.view = 'three-quarter';
        this.scene = null;
        this.camera = null;
        this.renderer = null;
        this.carModel = null;
        this.bodyPaintMeshes = [];
        this.activeModelId = null;
        this.pendingColor = '#E8E8EC';
        this.pendingModel = null;
        this._loadGen = 0;
        this._paused = false;
        this._disposed = false;
        this._observer = null;
        this._needsRender = false;
        this._renderScheduled = false;

        var self = this;
        loadVendor().then(function () {
            if (self._disposed) return;
            self._initThree();
            self._observeVisibility();
            // Re-apply any setModel/setColor calls that came in before
            // vendor JS was ready. Each schedules a render itself once
            // its asset is in place — no continuous loop needed.
            if (self.pendingModel) self.setModel(self.pendingModel);
            if (self.pendingColor) self.setColor(self.pendingColor);
        }).catch(function (err) {
            // Vendor download failed (offline, blocked). Canvas stays
            // empty; the battery overlay still tracks SOC over the gap.
            if (window.console) console.warn('[ev-card-3d] vendor load failed:', err);
        });

        // Dispose on navigate-away so the WebGL context is freed cleanly.
        this._beforeUnload = function () { self.dispose(); };
        window.addEventListener('beforeunload', this._beforeUnload);
    }

    // Read the rendering dimensions for the canvas. Prefer the CSS
    // client size (so the renderer follows layout), but fall back to
    // the canvas backing-buffer .width/.height when layout hasn't run
    // yet — happens when the canvas is attached but its parent is
    // display:none (camera selector inside an inactive tab) or when an
    // offscreen sprite canvas is freshly appended to body before the
    // first layout pass (ev-map-sprite renderer).
    function readCanvasSize(canvasEl, fallbackW, fallbackH) {
        var w = canvasEl.clientWidth;
        var h = canvasEl.clientHeight;
        if (!w) w = canvasEl.width;
        if (!h) h = canvasEl.height;
        if (!w) w = fallbackW;
        if (!h) h = fallbackH;
        return { w: w, h: h };
    }

    OverdriveEvCard3D.prototype._initThree = function () {
        var T = window.THREE;
        var sz = readCanvasSize(this.canvas, 280, 200);
        var w = sz.w, h = sz.h;

        this.scene = new T.Scene();
        // Camera placement depends on the view mode. Auto-fit in
        // _loadGlb scales the model to fill the canvas regardless, so
        // distance/FOV here just need to be self-consistent.
        this.camera = new T.PerspectiveCamera(26, w / h, 0.1, 100);
        if (this.view === 'top') {
            // Roof-down. Camera straight overhead, looking at origin.
            //
            // `up = (0, 0, -1)` chosen so that:
            //   - The GLB's -Z forward axis (vehicle-control.js:3039
            //     documents "the car sits at origin facing -Z") lands
            //     at the TOP of the canvas — matches where the FRONT
            //     hotspot sits in index.html.
            //   - The GLB's +X right axis lands at SCREEN RIGHT —
            //     matches the RIGHT hotspot.
            // Without an explicit `up`, lookAt(0,0,0) from (0,3,0)
            // is degenerate (parallel to the default up vector) and
            // three.js gives undefined orientation. `up` must be set
            // BEFORE lookAt() so the basis is built correctly.
            this.camera.up.set(0, 0, -1);
            this.camera.position.set(0, 3, 0);
            this.camera.lookAt(0, 0, 0);
        } else if (this.view === 'three-quarter') {
            this.camera.position.set(0, 0.45, 3.2);
            this.camera.lookAt(0, 0.05, 0);
        } else {
            // Pure side profile. Camera held close (distance 3) with
            // a narrow FOV so the GLB fills the ~196×200 canvas
            // almost edge-to-edge. Slight Y lift keeps the wheels
            // visible without a 3/4 tilt.
            this.camera.position.set(0, 0.3, 3);
            this.camera.lookAt(0, 0.0, 0);
        }

        this.renderer = new T.WebGLRenderer({
            canvas: this.canvas,
            antialias: true,
            alpha: true,
            // Conservative: fail soft if the device is out of WebGL
            // contexts; the canvas stays empty rather than crashing.
            failIfMajorPerformanceCaveat: false
        });
        // Order matters: setPixelRatio must come BEFORE setSize, since
        // three.js multiplies setSize args by the current pixel ratio.
        // Doing it the other way leaves the backing buffer at w×h
        // initially (pixelRatio defaults to 1), then a later setSize
        // call after pixelRatio=2 would land at 2w×2h — and on hidden
        // canvases (display:none parent) where w comes from the canvas
        // .width attribute, that doubles the attribute every resize.
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
        this.renderer.setSize(w, h, false);
        this.renderer.setClearColor(0x000000, 0); // transparent
        if (this.renderer.outputEncoding !== undefined) {
            this.renderer.outputEncoding = T.sRGBEncoding;
        }
        if (this.renderer.toneMapping !== undefined) {
            this.renderer.toneMapping = T.ACESFilmicToneMapping;
            this.renderer.toneMappingExposure = 1.1;
        }

        // Lights. The side-view rig (key from upper-front-right, fill
        // from lower-back-left) leaves the car's ROOF dark when viewed
        // top-down because no light is overhead. Add an explicit top
        // light for top-view mode so the roof reads.
        var hemi = new T.HemisphereLight(0xffffff, 0x222244, 1.1);
        this.scene.add(hemi);
        var key = new T.DirectionalLight(0xffffff, 1.1);
        key.position.set(5, 6, 4);
        this.scene.add(key);
        var fill = new T.DirectionalLight(0x8899bb, 0.5);
        fill.position.set(-3, 2, -2);
        this.scene.add(fill);
        if (this.view === 'top') {
            // Side-profile rig leaves the roof flat from overhead.
            // Add a dedicated overhead key so panel creases and the
            // body-paint metallic flake actually read top-down.
            var top = new T.DirectionalLight(0xffffff, 0.8);
            top.position.set(0.5, 8, 0.5);
            this.scene.add(top);
        }

        // Pause when the host element scrolls out of view OR when the
        // sidebar is hidden under a media query.
        this._handleResize();
        var self = this;
        this._onResize = function () { self._handleResize(); };
        window.addEventListener('resize', this._onResize);

        // Some mounts (e.g. the camera-selector inside an inactive
        // tab-panel with display:none) start at 0×0 and only acquire
        // dimensions when their parent becomes visible. ResizeObserver
        // catches that transition; without it the canvas would render
        // once at 0×0 (no-op) and never wake up. Falls back to a
        // requestAnimationFrame poll on browsers that lack RO (Chrome
        // <64, including older BYD WebViews).
        if (typeof ResizeObserver !== 'undefined') {
            this._resizeObserver = new ResizeObserver(function () { self._handleResize(); });
            this._resizeObserver.observe(this.canvas);
        } else {
            (function pollSize() {
                if (self._disposed) return;
                var sz = readCanvasSize(self.canvas, 0, 0);
                if (sz.w !== self._lastW || sz.h !== self._lastH) {
                    self._lastW = sz.w; self._lastH = sz.h;
                    self._handleResize();
                }
                setTimeout(pollSize, 250);
            })();
        }
    };

    OverdriveEvCard3D.prototype._handleResize = function () {
        if (!this.renderer) return;
        // Use ONLY the CSS client size for resize. The canvas-attribute
        // fallback was a layout-bootstrap convenience for _initThree;
        // applying it here would compound over time (each call doubles
        // the buffer because three.js multiplies by pixelRatio).
        var w = this.canvas.clientWidth;
        var h = this.canvas.clientHeight;
        if (!w || !h) return; // hidden — wait for visibility
        this.renderer.setSize(w, h, false);
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        // Re-fit the model to the new canvas size. The GLB may have
        // loaded against a 0×0 canvas (mounted in a hidden tab-panel)
        // and been auto-fit for the readCanvasSize fallback aspect; on
        // first reveal the real CSS size lands here and we need to
        // re-scale or the car is sized for the wrong aspect ratio and
        // appears off-screen / cropped. Cheap (just a scalar multiply
        // and re-centre against cached natural bounds).
        this._autoFit();
        this._scheduleRender();
    };

    // Auto-fit the loaded GLB to the current canvas. Runs once after
    // load AND on every resize (tab reveal, landscape rail flip, window
    // resize on the preview server). Idempotent: applies a fresh scale
    // computed from the cached natural size, never multiplying onto
    // whatever scale was in effect.
    OverdriveEvCard3D.prototype._autoFit = function () {
        if (!this.carModel || !this._naturalSize || !this.camera) return;
        var T = window.THREE;
        var canvasSz = readCanvasSize(this.canvas, 1, 1);
        var aspect = canvasSz.w / Math.max(canvasSz.h, 1);
        // Camera distance from the origin — for the side rig that's
        // its Z; for the top rig that's its Y. Fit logic is otherwise
        // identical: project the visible frustum into the model's two
        // on-screen axes.
        var camDist = (this.view === 'top')
            ? this.camera.position.y
            : this.camera.position.z;
        var fovRad = this.camera.fov * Math.PI / 180;
        var visibleH = 2 * camDist * Math.tan(fovRad / 2);
        var visibleW = visibleH * aspect;
        // On-screen extents differ per view. Side: model X is
        // canvas-width and model Y is canvas-height. Top: model X is
        // canvas-width and model Z is canvas-height (camera.up = -Z
        // makes the car's length run vertically on the canvas).
        var modelW = this._naturalSize.x;
        var modelH = (this.view === 'top') ? this._naturalSize.z : this._naturalSize.y;
        var fitFill = 0.98;
        if (this.view === 'three-quarter') fitFill = 0.90;
        var widthScale  = (visibleW * fitFill) / Math.max(modelW, 1e-6);
        var heightScale = (visibleH * fitFill) / Math.max(modelH, 1e-6);
        var s = Math.min(widthScale, heightScale);
        // Reset → apply, instead of multiplyScalar, so repeated calls
        // converge instead of compounding.
        this.carModel.scale.set(s, s, s);
        // Re-centre after scaling so the model sits on (0, 0, 0).
        // position must be reset first or the prior offset compounds
        // against a fresh box that's already centred at the new world
        // origin.
        this.carModel.position.set(0, 0, 0);
        var box = new T.Box3().setFromObject(this.carModel);
        var center = box.getCenter(new T.Vector3());
        this.carModel.position.sub(center);
        if (this.view === 'three-quarter') {
            this.carModel.position.y += this._naturalSize.y * s * 0.2;
            this.carModel.position.x -= this._naturalSize.x * s * 0.1;
        }
    };

    OverdriveEvCard3D.prototype._observeVisibility = function () {
        var self = this;
        if (typeof IntersectionObserver === 'undefined') return;
        this._observer = new IntersectionObserver(function (entries) {
            for (var i = 0; i < entries.length; i++) {
                var visible = entries[i].isIntersecting;
                var wasPaused = self._paused;
                self._paused = !visible;
                if (wasPaused && visible && self._needsRender) self._renderOnce();
            }
        }, { threshold: 0.01 });
        this._observer.observe(this.canvas);

        // Resume on tab focus.
        this._onVisibilityChange = function () {
            if (!document.hidden && self._needsRender && !self._paused) {
                self._renderOnce();
            }
        };
        document.addEventListener('visibilitychange', this._onVisibilityChange);
    };

    // Render-on-demand. The scene only re-renders when something actually
    // changes (model load, colour change, resize, visibility flip back on).
    // No continuous loop = zero idle GPU/CPU cost while the user reads
    // dashboards on a non-vehicle-control page.
    OverdriveEvCard3D.prototype._renderOnce = function () {
        if (this._disposed || !this.renderer || !this.scene || !this.camera) return;
        if (this._paused || document.hidden) {
            // Defer until visible — the visibility/IntersectionObserver
            // re-enters scheduleRender once we're back on screen.
            this._needsRender = true;
            return;
        }
        if (!this.carModel) return; // GLB not loaded yet — nothing to draw
        try {
            this.renderer.render(this.scene, this.camera);
            this._needsRender = false;
            // Drain any one-shot post-render callbacks. Callers (sprite
            // cache snapshotter) use this to grab the canvas once the
            // model + paint colour are both applied.
            if (this._afterRenderCbs && this._afterRenderCbs.length) {
                var cbs = this._afterRenderCbs;
                this._afterRenderCbs = [];
                for (var i = 0; i < cbs.length; i++) {
                    try { cbs[i](); } catch (e) {
                        if (window.console) console.warn('[ev-card-3d] afterRender cb failed:', e);
                    }
                }
            }
        } catch (e) {
            this._paused = true;
            if (window.console) console.warn('[ev-card-3d] render failed:', e);
        }
    };

    // Fire `cb` once after the next render that actually paints the GLB.
    // If a render is already pending, the callback runs at the end of
    // that frame; otherwise it queues for the next mutation that
    // triggers a render. preserveOnRebuild=false (default) drops queued
    // callbacks if setModel changes mid-flight, since the snapshot
    // would no longer match the requested (model,colour).
    OverdriveEvCard3D.prototype.onceAfterRender = function (cb) {
        if (this._disposed || typeof cb !== 'function') return;
        if (!this._afterRenderCbs) this._afterRenderCbs = [];
        this._afterRenderCbs.push(cb);
        // Make sure a render is actually scheduled so the queue drains
        // even if no upstream mutation followed.
        this._scheduleRender();
    };

    // Schedule one render on the next animation frame. Coalesces multiple
    // mutations within the same frame into a single render.
    OverdriveEvCard3D.prototype._scheduleRender = function () {
        if (this._disposed) return;
        this._needsRender = true;
        if (this._renderScheduled) return;
        var self = this;
        this._renderScheduled = true;
        requestAnimationFrame(function () {
            self._renderScheduled = false;
            if (self._needsRender) self._renderOnce();
        });
    };

    OverdriveEvCard3D.prototype.setColor = function (hexColor) {
        this.pendingColor = hexColor;
        if (!window.THREE || !this.bodyPaintMeshes) return;
        var T = window.THREE;
        // Convert sRGB → linear so the painted hex matches what vehicle-
        // control shows for the same swatch.
        var c = new T.Color(hexColor).convertSRGBToLinear();
        for (var i = 0; i < this.bodyPaintMeshes.length; i++) {
            var mat = this.bodyPaintMeshes[i].material;
            if (mat && mat.color) { mat.color.copy(c); mat.needsUpdate = true; }
        }
        this._scheduleRender();
    };

    OverdriveEvCard3D.prototype.setModel = function (modelId) {
        this.pendingModel = modelId;
        // Vendor must be FULLY loaded — three.min.js + GLTFLoader.js +
        // DRACOLoader.js — before we can call into the loader chain.
        // Just checking window.THREE isn't enough: between three.min.js
        // parsing and GLTFLoader.js parsing there's a window where
        // window.THREE exists but THREE.GLTFLoader is still undefined.
        // Hitting that window with a setModel call would throw
        // "T.GLTFLoader is not a constructor" at _loadGlb. Bail and
        // rely on the constructor's loadVendor().then() chain to replay
        // the queued pendingModel once everything is ready.
        if (!window.THREE
                || typeof window.THREE.GLTFLoader !== 'function'
                || typeof window.THREE.DRACOLoader !== 'function') {
            return;
        }
        if (!modelId || modelId === this.activeModelId) return;

        var self = this;
        var gen = ++this._loadGen;
        this.activeModelId = modelId;

        loadManifest().then(function (manifest) {
            if (gen !== self._loadGen) return;
            if (!manifest) return; // no manifest → canvas stays empty
            var modelEntry = null;
            var manifestModels = manifest.models || [];
            for (var i = 0; i < manifestModels.length; i++) {
                if (manifestModels[i].id === modelId) {
                    modelEntry = manifestModels[i];
                    break;
                }
            }
            resolveModelUrl(modelId, manifest, function (url, err) {
                if (gen !== self._loadGen || err || !url) return;
                self._loadGlb(url, gen, modelEntry);
            });
        });
    };

    OverdriveEvCard3D.prototype._loadGlb = function (url, gen, modelEntry) {
        var self = this;
        var T = window.THREE;
        var loader = new T.GLTFLoader();
        var draco = new T.DRACOLoader();
        draco.setDecoderPath(VENDOR + 'draco/');
        draco.setDecoderConfig({ type: 'js' });
        loader.setDRACOLoader(draco);

        loader.load(url, function (gltf) {
            if (gen !== self._loadGen) return;
            self._disposeCarModel();
            self.carModel = gltf.scene;
            self.bodyPaintMeshes = [];
            var paintMeshHint = modelEntry
                    && typeof modelEntry.paintMeshHint === 'string'
                ? modelEntry.paintMeshHint.toLowerCase()
                : '';

            // Same body-paint detector as vehicle-control._loadModelFromPath.
            self.carModel.traverse(function (node) {
                if (!node.isMesh) return;
                var mat = node.material;
                if (!mat || !mat.color) return;
                if (mat.transparent || mat.opacity < 0.95) return;
                if (paintMeshHint) {
                    var paintName = ((node.name || '') + ' '
                        + (mat.name || '')).toLowerCase();
                    if (paintName.indexOf(paintMeshHint) < 0) return;
                } else {
                    var col = mat.color;
                    var brightness = col.r * 0.299 + col.g * 0.587 + col.b * 0.114;
                    if (brightness < 0.08) return;       // tyres / rubber
                    if (brightness > 0.85) return;       // chrome / lights
                    var metal = mat.metalness !== undefined ? mat.metalness : 0;
                    if (metal >= 0.95) return;           // chrome
                }
                self.bodyPaintMeshes.push(node);
            });

            // Orient the GLB. The BYD GLBs are authored facing -Z
            // (matches what vehicle-control.js documents: "the car
            // sits at origin facing -Z").
            //   side view: rotate +π/2 around Y so -Z → -X, putting
            //              the front at the LEFT edge of the canvas
            //              (sidebar EV-card, ships unchanged).
            //   top view:  rotate π so the GLB's -Z forward, which
            //              the camera basis (up = -Z) projects to
            //              canvas-top, ends up at canvas-bottom
            //              instead. Used by the live-view camera
            //              selector and the map markers where the
            //              FRONT hotspot / heading=0 reads as
            //              pointing south on the canvas.
            if (self.view === 'top') {
                self.carModel.rotation.y = Math.PI;
            } else if (self.view === 'three-quarter') {
                self.carModel.rotation.y = 0.95;
            } else {
                self.carModel.rotation.y = Math.PI / 2;
            }

            // Cache the natural (unscaled) bounds so _autoFit can run
            // again on resize without compounding scale across calls.
            var preBox = new T.Box3().setFromObject(self.carModel);
            var preSize = preBox.getSize(new T.Vector3());
            self._naturalSize = { x: preSize.x, y: preSize.y, z: preSize.z };
            self._autoFit();

            self.scene.add(self.carModel);

            // Apply the queued colour now that meshes are known. setColor
            // schedules its own render so we don't double-schedule.
            self.setColor(self.pendingColor);
        }, undefined, function (err) {
            if (window.console) console.warn('[ev-card-3d] glb load failed:', url, err);
        });
    };

    OverdriveEvCard3D.prototype._disposeCarModel = function () {
        if (!this.carModel) return;
        var T = window.THREE;
        this.scene.remove(this.carModel);
        this.carModel.traverse(function (n) {
            if (n.geometry) n.geometry.dispose();
            if (n.material) {
                var mats = Array.isArray(n.material) ? n.material : [n.material];
                for (var i = 0; i < mats.length; i++) {
                    if (mats[i].map) mats[i].map.dispose();
                    mats[i].dispose();
                }
            }
        });
        this.carModel = null;
        this.bodyPaintMeshes = [];
    };

    OverdriveEvCard3D.prototype.dispose = function () {
        if (this._disposed) return;
        this._disposed = true;
        if (this._observer) this._observer.disconnect();
        if (this._resizeObserver) try { this._resizeObserver.disconnect(); } catch (e) {}
        if (this._onResize) window.removeEventListener('resize', this._onResize);
        if (this._onVisibilityChange) document.removeEventListener('visibilitychange', this._onVisibilityChange);
        if (this._beforeUnload) window.removeEventListener('beforeunload', this._beforeUnload);
        this._disposeCarModel();
        if (this.renderer) {
            try { this.renderer.dispose(); } catch (e) {}
            try {
                var ext = this.renderer.getContext().getExtension('WEBGL_lose_context');
                if (ext) ext.loseContext();
            } catch (e) {}
            this.renderer = null;
        }
        this.scene = null;
        this.camera = null;
    };

    root.OverdriveEvCard3D = OverdriveEvCard3D;
}(window));
