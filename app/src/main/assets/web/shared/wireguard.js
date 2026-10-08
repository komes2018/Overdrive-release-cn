/**
 * OverDrive - WireGuard setup page.
 * Shows the tunnel status and lets the user upload or paste the config their
 * router exported. The private key is write-only: the API never returns it.
 */

var WG = {
    MAX_BYTES: 16 * 1024,
    refreshInterval: null,
    saving: false,

    init: function () {
        var self = this;
        this.refresh();
        this.refreshInterval = setInterval(function () { self.refresh(); }, 5000);
        var input = document.getElementById('wgConfig');
        if (input) input.addEventListener('input', function () { self.showError(''); });
    },

    // i18n lookup with an English fallback while the catalog is still loading.
    T: function (key, fallback, vars) {
        var v = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, vars) : null;
        if (v == null || v === key) {
            v = fallback;
            if (vars) {
                for (var name in vars) {
                    if (vars.hasOwnProperty(name)) v = v.split('{' + name + '}').join(vars[name]);
                }
            }
        }
        return v;
    },

    // ==================== STATUS ====================

    refresh: function () {
        var self = this;
        fetch('/api/wireguard')
            .then(function (resp) { return resp.json(); })
            .then(function (data) { self.render(data); })
            .catch(function () { self.render(null); });
    },

    render: function (data) {
        var dot = document.getElementById('wgDot');
        var state = document.getElementById('wgState');
        var detail = document.getElementById('wgDetail');
        var delBtn = document.getElementById('wgDeleteBtn');
        if (!dot || !state || !detail) return;

        if (!data) {
            dot.className = 'conn-dot stopped';
            state.textContent = this.T('wireguard.status_unavailable', 'Status unavailable');
            detail.textContent = '';
            return;
        }

        var configured = !!data.configured;
        var running = !!data.running;
        var summary = data.summary || null;
        var status = running ? (data.status || null) : null;
        if (delBtn) delBtn.style.display = configured ? '' : 'none';

        var cls = 'stopped';
        var text;
        var lines = [];
        if (!configured) {
            text = this.T('wireguard.status_none', 'No configuration yet');
        } else if (!running) {
            text = this.T('wireguard.status_stopped', 'Configured, tunnel not running');
        } else if (!status) {
            cls = 'reconnecting';
            text = this.T('wireguard.status_starting', 'Starting...');
        } else {
            var peer = this.bestPeer(status);
            var endpoint = (peer && peer.endpoint) ||
                (summary && summary.endpoints && summary.endpoints[0]) || '';
            if (status.state === 'connected') {
                cls = 'connected';
                text = this.T('wireguard.status_connected', 'Connected to {endpoint}', { endpoint: endpoint });
                lines.push(this.handshakeText(peer));
            } else if (status.state === 'error') {
                cls = 'disconnected';
                text = this.T('wireguard.status_error', 'Error: {error}', { error: status.error || '?' });
            } else if (status.state === 'reloading') {
                cls = 'reconnecting';
                text = this.T('wireguard.status_reloading', 'Applying the new configuration...');
            } else if (status.state === 'stopped') {
                text = this.T('wireguard.status_stopped', 'Configured, tunnel not running');
            } else {
                cls = 'reconnecting';
                text = this.T('wireguard.status_connecting', 'Connecting to {endpoint}...', { endpoint: endpoint });
            }
        }

        var addresses = (status && status.addresses && status.addresses.length) ? status.addresses
            : (summary && summary.addresses) || [];
        var routes = (status && status.routes && status.routes.length) ? status.routes
            : (summary && summary.routes) || [];
        if (configured && addresses.length) {
            lines.push(this.T('wireguard.detail_address', 'Tunnel address: {value}', { value: addresses.join(', ') }));
        }
        if (configured && routes.length) {
            lines.push(this.T('wireguard.detail_routes', 'Routes: {value}', { value: routes.join(', ') }));
        }

        dot.className = 'conn-dot ' + cls;
        state.textContent = text;
        // textContent with newlines + pre-line keeps user data out of innerHTML.
        detail.textContent = lines.join('\n');
    },

    bestPeer: function (status) {
        var peers = status.peers || [];
        var best = null;
        for (var i = 0; i < peers.length; i++) {
            if (!best || (peers[i].last_handshake || 0) > (best.last_handshake || 0)) best = peers[i];
        }
        return best;
    },

    handshakeText: function (peer) {
        var at = peer ? (peer.last_handshake || 0) : 0;
        if (at <= 0) return this.T('wireguard.handshake_none', 'No handshake yet');
        var ago = Math.max(0, Math.floor(Date.now() / 1000) - at);
        if (ago < 120) return this.T('wireguard.handshake_seconds', 'Last handshake {n}s ago', { n: ago });
        if (ago < 7200) return this.T('wireguard.handshake_minutes', 'Last handshake {n}m ago', { n: Math.floor(ago / 60) });
        return this.T('wireguard.handshake_hours', 'Last handshake {n}h ago', { n: Math.floor(ago / 3600) });
    },

    // ==================== INPUT ====================

    onFile: function (input) {
        var self = this;
        var file = input.files && input.files[0];
        if (!file) return;
        if (file.size > this.MAX_BYTES) {
            this.showError(this.T('wireguard.file_too_large', 'That file is too large for a WireGuard config'));
            input.value = '';
            return;
        }
        var reader = new FileReader();
        reader.onload = function () {
            document.getElementById('wgConfig').value = String(reader.result || '');
            self.showError('');
            input.value = '';
        };
        reader.onerror = function () {
            self.showError(self.T('wireguard.file_unreadable', 'Could not read that file'));
            input.value = '';
        };
        reader.readAsText(file);
    },

    showError: function (message) {
        var el = document.getElementById('wgError');
        if (!el) return;
        el.textContent = message || '';
        el.style.display = message ? 'block' : 'none';
    },

    toast: function (message, type) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
    },

    // ==================== ACTIONS ====================

    save: function () {
        var self = this;
        if (this.saving) return;
        var text = document.getElementById('wgConfig').value;
        if (!text.trim()) {
            this.showError(this.T('wireguard.err_empty', 'Paste or upload a config first'));
            return;
        }
        this.saving = true;
        this.showError('');
        fetch('/api/wireguard/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ config: text })
        })
            .then(function (resp) { return resp.json(); })
            .then(function (result) {
                self.saving = false;
                if (result && result.success) {
                    document.getElementById('wgConfig').value = '';
                    self.toast(self.T('wireguard.toast_saved', 'WireGuard config saved'), 'success');
                    self.refresh();
                } else {
                    self.showError((result && result.error) || self.T('errors.save_failed', 'Save failed'));
                }
            })
            .catch(function (e) {
                self.saving = false;
                self.showError(self.T('wireguard.network_error', 'Network error: {message}', { message: e.message }));
            });
    },

    remove: function () {
        var self = this;
        if (!window.confirm(this.T('wireguard.confirm_delete',
                'Delete the WireGuard config? The private key is removed and the tunnel stops.'))) return;
        fetch('/api/wireguard/config', { method: 'DELETE' })
            .then(function (resp) { return resp.json(); })
            .then(function (result) {
                if (result && result.success) {
                    self.toast(self.T('wireguard.toast_deleted', 'WireGuard config deleted'), 'success');
                    self.refresh();
                } else {
                    self.toast((result && result.error) || self.T('errors.delete_failed', 'Delete failed'), 'error');
                }
            })
            .catch(function (e) {
                self.toast(self.T('wireguard.network_error', 'Network error: {message}', { message: e.message }), 'error');
            });
    }
};
