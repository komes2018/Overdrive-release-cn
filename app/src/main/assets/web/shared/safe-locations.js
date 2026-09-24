/**
 * Safe Locations — SOTA Interactive Geofence Editor
 *
 * UX Flow:
 * 1. Toggle ON → map appears centered on current GPS
 * 2. A live preview circle shows the zone being configured
 * 3. Slider below map controls radius (15-500m), circle updates in real-time
 * 4. "Add This Zone" opens a themed name/radius dialog
 * 5. Saved zones appear on the map and can be edited from the list
 */

window.SafeLocations = {
    map: null,
    zones: [],
    zoneCircles: {},    // id -> L.circle (saved zones)
    zoneMarkers: {},    // id -> L.marker (saved zones)
    featureEnabled: false,
    currentGps: null,
    gpsMarker: null,
    ready: false,
    togglePending: false,
    zoneTogglePending: {},
    _writeQueue: Promise.resolve(),

    // Live editing circle
    editCircle: null,
    editRadius: 150,

    refreshTimer: null,

    icons: {
        edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>',
        trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>'
    },

    async init() {
        this.ready = await this.loadData();
        this.updateUI();
        this.refreshTimer = setInterval(() => this.refreshStatus(), 5000);
    },

    _enqueueWrite(task) {
        const run = () => task();
        const next = this._writeQueue.then(run, run);
        this._writeQueue = next.catch(() => {});
        return next;
    },

    text(key, vars, fallback) {
        const value = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key, vars) : null;
        if (value && value !== key) return String(value).trim();
        return fallback || key;
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(message, type);
    },

    async requestJson(url, options) {
        const resp = await fetch(url, options);
        let data = {};
        try {
            data = await resp.json();
        } catch (ignored) {
            data = {};
        }
        if (!resp.ok || data.success === false) {
            throw new Error(data.error || this.text('errors.generic', null, 'Something went wrong'));
        }
        return data;
    },

    async loadData() {
        try {
            const data = await this.requestJson('/api/surveillance/safe-locations');
            this.featureEnabled = data.featureEnabled || false;
            this.zones = data.zones || [];
            this.currentGps = data.hasGps ? { lat: data.lat, lng: data.lng, accuracy: data.accuracy } : null;
            this.updateStatusText(data);
            return true;
        } catch (e) {
            console.warn('Failed to load safe locations:', e);
            return false;
        }
    },

    updateStatusText(data) {
        const el = document.getElementById('safeLocStatus');
        if (!el) return;
        let stateClass = 'is-pending';
        if (data.inSafeZone) {
            el.textContent = this.text('safe_loc.in_safe_zone', {
                zone: data.currentZone,
                meters: data.nearestDistanceM
            }, 'In safe zone');
            stateClass = 'is-safe';
        } else if (data.hasGps && this.zones.length > 0) {
            el.textContent = this.text('safe_loc.outside_zone', {
                meters: data.nearestDistanceM
            }, 'Outside safe zones');
            stateClass = 'is-outside';
        } else {
            el.textContent = this.zones.length === 0
                ? this.text('safe_loc.no_zones_yet', null, 'No zones yet')
                : this.text('safe_loc.waiting_gps', null, 'Waiting for GPS...');
        }
        el.className = 'value safe-loc-status ' + stateClass;

        const gpsEl = document.getElementById('safeLocGps');
        if (gpsEl) {
            gpsEl.textContent = data.hasGps
                ? data.lat.toFixed(6) + ', ' + data.lng.toFixed(6) + ' (±' + Math.round(data.accuracy) + 'm)'
                : '--';
        }
    },

    updateUI() {
        const toggle = document.getElementById('safeLocEnabled');
        if (toggle) {
            toggle.checked = this.featureEnabled;
            toggle.disabled = !this.ready || this.togglePending;
        }

        const badge = document.getElementById('safeLocBadge');
        if (badge) {
            badge.textContent = this.featureEnabled
                ? this.text('status.on', null, 'ON')
                : this.text('status.off', null, 'OFF');
            badge.className = 'status-badge ' + (this.featureEnabled ? 'active' : 'inactive');
        }

        const content = document.getElementById('safeLocContent');
        const statusBox = document.getElementById('safeLocStatusBox');
        if (content) content.style.display = this.featureEnabled ? 'block' : 'none';
        if (statusBox) statusBox.style.display = this.featureEnabled ? 'block' : 'none';

        if (this.featureEnabled && !this.map) {
            setTimeout(() => this.initMap(), 150);
        } else if (this.featureEnabled && this.map) {
            setTimeout(() => this.map.invalidateSize(), 0);
        }

        this.renderZoneList();
    },

    initMap() {
        if (this.map) return;
        const container = document.getElementById('safeLocMap');
        if (!container) return;
        
        // Guard: Leaflet may not be loaded yet (async script)
        if (typeof L === 'undefined') {
            setTimeout(() => this.initMap(), 500);
            return;
        }

        const center = this.currentGps ? [this.currentGps.lat, this.currentGps.lng] : [31.23, 121.47];
        const zoom = this.currentGps ? 16 : 3;

        this.map = L.map('safeLocMap', {
            zoomControl: true,
            attributionControl: false
        }).setView(center, zoom);

        BYD.theme.attachMapTiles(this.map);

        // Current GPS blue dot
        if (this.currentGps) {
            this.gpsMarker = L.circleMarker([this.currentGps.lat, this.currentGps.lng], {
                radius: 7, fillColor: '#3b82f6', fillOpacity: 1,
                color: '#fff', weight: 2
            }).addTo(this.map).bindPopup(this.text('safe_loc.you_are_here', null, 'You are here'));
        }

        // Add saved zones
        this.zones.forEach(z => this.addSavedZoneToMap(z));

        // Create the live edit circle at current position
        if (this.currentGps) {
            this.createEditCircle(this.currentGps.lat, this.currentGps.lng);
        }

        // Sync slider
        const slider = document.getElementById('safeLocRadiusSlider');
        if (slider) slider.value = this.editRadius;
        this.updateRadiusLabel();
    },

    createEditCircle(lat, lng) {
        if (this.editCircle) {
            this.map.removeLayer(this.editCircle);
        }

        this.editCircle = L.circle([lat, lng], {
            radius: this.editRadius,
            color: '#f59e0b',
            fillColor: '#f59e0b',
            fillOpacity: 0.18,
            weight: 2.5,
            dashArray: '8,6',
            interactive: true
        }).addTo(this.map);

        // Fit map to show the circle
        this.map.fitBounds(this.editCircle.getBounds().pad(0.3));
    },

    updateEditCircleRadius(radius) {
        this.editRadius = parseInt(radius);
        if (this.editCircle) {
            this.editCircle.setRadius(this.editRadius);
            this.map.fitBounds(this.editCircle.getBounds().pad(0.2));
        }
        this.updateRadiusLabel();
    },

    updateRadiusLabel() {
        const el = document.getElementById('safeLocRadiusValue');
        if (el) el.textContent = this.editRadius + 'm';
    },

    addSavedZoneToMap(zone) {
        if (!this.map) return;
        const color = zone.enabled ? '#10b981' : '#6b7280';

        const circle = L.circle([zone.lat, zone.lng], {
            radius: zone.radiusM,
            color: color, fillColor: color, fillOpacity: 0.12,
            weight: 2, dashArray: zone.enabled ? null : '5,5'
        }).addTo(this.map);

        const marker = L.marker([zone.lat, zone.lng], { title: zone.name })
            .addTo(this.map);

        const popup = document.createElement('div');
        popup.className = 'safe-zone-popup';
        const popupName = document.createElement('strong');
        popupName.className = 'safe-zone-popup-name';
        popupName.textContent = zone.name;
        const popupRadius = document.createElement('span');
        popupRadius.className = 'safe-zone-popup-radius';
        popupRadius.textContent = zone.radiusM + 'm';
        popup.appendChild(popupName);
        popup.appendChild(popupRadius);
        marker.bindPopup(popup);

        this.zoneCircles[zone.id] = circle;
        this.zoneMarkers[zone.id] = marker;
    },

    removeSavedZoneFromMap(id) {
        if (!this.map) return;
        if (this.zoneCircles[id]) {
            this.map.removeLayer(this.zoneCircles[id]);
            delete this.zoneCircles[id];
        }
        if (this.zoneMarkers[id]) {
            this.map.removeLayer(this.zoneMarkers[id]);
            delete this.zoneMarkers[id];
        }
    },

    refreshSavedZoneOnMap(zone) {
        if (!this.map) return;
        this.removeSavedZoneFromMap(zone.id);
        this.addSavedZoneToMap(zone);
    },

    renderZoneList() {
        const container = document.getElementById('safeLocZoneList');
        if (!container) return;
        container.textContent = '';

        if (this.zones.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'safe-zone-empty';
            empty.textContent = this.text('safe_loc.no_zones_yet', null, 'No zones yet');
            container.appendChild(empty);
            return;
        }

        this.zones.forEach(z => {
            const row = document.createElement('div');
            row.className = 'safe-zone-row' + (z.enabled ? '' : ' is-disabled');

            const indicator = document.createElement('span');
            indicator.className = 'safe-zone-indicator ' + (z.enabled ? 'is-enabled' : 'is-disabled');
            indicator.setAttribute('aria-hidden', 'true');

            const info = document.createElement('div');
            info.className = 'safe-zone-info';
            const name = document.createElement('div');
            name.className = 'safe-zone-name';
            name.textContent = z.name;
            name.title = z.name;
            const meta = document.createElement('div');
            meta.className = 'safe-zone-meta';
            meta.textContent = z.radiusM + 'm';
            info.appendChild(name);
            info.appendChild(meta);

            const actions = document.createElement('div');
            actions.className = 'safe-zone-actions';

            const toggle = document.createElement('label');
            toggle.className = 'toggle-switch safe-zone-toggle';
            const toggleInput = document.createElement('input');
            toggleInput.type = 'checkbox';
            toggleInput.checked = z.enabled;
            toggleInput.disabled = !!this.zoneTogglePending[z.id];
            toggleInput.setAttribute('aria-label', this.text(
                'safe_loc.toggle_zone',
                {name: z.name},
                'Toggle ' + z.name
            ));
            toggleInput.addEventListener('change', () => this.toggleZone(z.id, toggleInput.checked));
            const toggleSlider = document.createElement('span');
            toggleSlider.className = 'toggle-slider';
            toggle.appendChild(toggleInput);
            toggle.appendChild(toggleSlider);

            const editLabel = this.text('common.edit', null, 'Edit');
            const editButton = document.createElement('button');
            editButton.type = 'button';
            editButton.className = 'safe-zone-icon-btn';
            editButton.title = editLabel;
            editButton.setAttribute('aria-label', editLabel + ': ' + z.name);
            editButton.innerHTML = this.icons.edit;
            editButton.addEventListener('click', () => this.editZone(z.id));

            const deleteLabel = this.text('common.delete', null, 'Delete');
            const deleteButton = document.createElement('button');
            deleteButton.type = 'button';
            deleteButton.className = 'safe-zone-icon-btn is-danger';
            deleteButton.title = deleteLabel;
            deleteButton.setAttribute('aria-label', deleteLabel + ': ' + z.name);
            deleteButton.innerHTML = this.icons.trash;
            deleteButton.addEventListener('click', () => this.deleteZone(z.id));

            actions.appendChild(toggle);
            actions.appendChild(editButton);
            actions.appendChild(deleteButton);
            row.appendChild(indicator);
            row.appendChild(info);
            row.appendChild(actions);
            container.appendChild(row);
        });
    },

    showZoneDialog(zone) {
        const isEdit = !!zone;
        const initialName = isEdit
            ? zone.name
            : this.text('safe_loc.default_name', null, 'Home');
        const initialRadius = isEdit ? zone.radiusM : this.editRadius;
        const previousFocus = document.activeElement;
        const self = this;

        return new Promise(resolve => {
            const backdrop = document.createElement('div');
            backdrop.className = 'modal-backdrop';
            backdrop.style.display = 'flex';

            const form = document.createElement('form');
            form.className = 'modal-card safe-zone-dialog';
            form.setAttribute('role', 'dialog');
            form.setAttribute('aria-modal', 'true');
            form.setAttribute('aria-labelledby', 'safeZoneDialogTitle');

            const title = document.createElement('h3');
            title.id = 'safeZoneDialogTitle';
            title.className = 'soh-modal-title';
            title.textContent = isEdit
                ? self.text('safe_loc.edit_title', null, 'Edit safe zone')
                : self.text('safe_loc.add_title', null, 'Add safe zone');

            const nameLabel = document.createElement('label');
            nameLabel.className = 'soh-modal-label';
            nameLabel.htmlFor = 'safeZoneNameInput';
            nameLabel.textContent = self.text('safe_loc.name_label', null, 'Zone name');

            const nameInput = document.createElement('input');
            nameInput.id = 'safeZoneNameInput';
            nameInput.className = 'soh-modal-input';
            nameInput.type = 'text';
            nameInput.maxLength = 80;
            nameInput.autocomplete = 'off';
            nameInput.value = initialName;

            const radiusHeader = document.createElement('div');
            radiusHeader.className = 'safe-zone-dialog-radius-header';
            const radiusLabel = document.createElement('label');
            radiusLabel.className = 'soh-modal-label';
            radiusLabel.htmlFor = 'safeZoneRadiusInput';
            radiusLabel.textContent = self.text('safe_loc.radius_label', null, 'Radius');
            const radiusValue = document.createElement('span');
            radiusValue.className = 'safe-zone-dialog-radius-value';
            radiusValue.textContent = initialRadius + 'm';
            radiusHeader.appendChild(radiusLabel);
            radiusHeader.appendChild(radiusValue);

            const radiusInput = document.createElement('input');
            radiusInput.id = 'safeZoneRadiusInput';
            radiusInput.className = 'slider safe-zone-dialog-slider';
            radiusInput.type = 'range';
            radiusInput.min = '15';
            radiusInput.max = '500';
            radiusInput.step = '5';
            radiusInput.value = String(initialRadius);
            radiusInput.addEventListener('input', function () {
                radiusValue.textContent = this.value + 'm';
            });

            const rangeLabels = document.createElement('div');
            rangeLabels.className = 'safe-zone-dialog-range-labels';
            const minLabel = document.createElement('span');
            minLabel.textContent = '15m';
            const maxLabel = document.createElement('span');
            maxLabel.textContent = '500m';
            rangeLabels.appendChild(minLabel);
            rangeLabels.appendChild(maxLabel);

            const error = document.createElement('div');
            error.className = 'safe-zone-dialog-error';
            error.setAttribute('role', 'alert');
            error.style.display = 'none';

            const actions = document.createElement('div');
            actions.className = 'soh-modal-actions';
            const cancelButton = document.createElement('button');
            cancelButton.type = 'button';
            cancelButton.className = 'btn btn-secondary';
            cancelButton.textContent = self.text('common.cancel', null, 'Cancel');
            const saveButton = document.createElement('button');
            saveButton.type = 'submit';
            saveButton.className = 'btn btn-primary';
            saveButton.textContent = isEdit
                ? self.text('common.save', null, 'Save')
                : self.text('safe_loc.add_action', null, 'Add zone');
            actions.appendChild(cancelButton);
            actions.appendChild(saveButton);

            form.appendChild(title);
            form.appendChild(nameLabel);
            form.appendChild(nameInput);
            form.appendChild(radiusHeader);
            form.appendChild(radiusInput);
            form.appendChild(rangeLabels);
            form.appendChild(error);
            form.appendChild(actions);
            backdrop.appendChild(form);

            let finished = false;
            function finish(result) {
                if (finished) return;
                finished = true;
                document.removeEventListener('keydown', onKeyDown);
                try { backdrop.remove(); } catch (ignored) {}
                try {
                    if (previousFocus && previousFocus.focus) previousFocus.focus();
                } catch (ignored) {}
                resolve(result);
            }

            function onKeyDown(event) {
                if (event.key !== 'Escape') return;
                event.preventDefault();
                finish(null);
            }

            cancelButton.addEventListener('click', () => finish(null));
            backdrop.addEventListener('click', event => {
                if (event.target === backdrop) finish(null);
            });
            form.addEventListener('submit', event => {
                event.preventDefault();
                const cleanName = nameInput.value.trim();
                if (!cleanName) {
                    error.textContent = self.text(
                        'safe_loc.name_required',
                        null,
                        'Enter a zone name'
                    );
                    error.style.display = 'block';
                    nameInput.focus();
                    return;
                }
                finish({
                    name: cleanName,
                    radiusM: Math.max(15, Math.min(500, parseInt(radiusInput.value, 10) || 150))
                });
            });

            document.addEventListener('keydown', onKeyDown);
            document.body.appendChild(backdrop);
            setTimeout(() => {
                try {
                    nameInput.focus();
                    nameInput.select();
                } catch (ignored) {}
            }, 0);
        });
    },

    // ==================== ACTIONS ====================

    async toggleFeature() {
        const toggle = document.getElementById('safeLocEnabled');
        if (!toggle || !this.ready || this.togglePending) return;
        const enabled = toggle.checked;
        const previous = this.featureEnabled;
        this.togglePending = true;
        this.updateUI();
        try {
            const data = await this._enqueueWrite(() => this.requestJson('/api/surveillance/safe-locations/toggle', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            }));
            this.featureEnabled = data.enabled == null ? enabled : data.enabled;
            this.toast(
                this.featureEnabled
                    ? this.text('safe_loc.enabled', null, 'Safe locations enabled')
                    : this.text('safe_loc.disabled', null, 'Safe locations disabled'),
                'success'
            );
        } catch (e) {
            this.featureEnabled = previous;
            toggle.checked = previous;
            this.toast(this.text('safe_loc.toggle_failed', null, 'Failed to toggle'), 'error');
        } finally {
            this.togglePending = false;
            this.updateUI();
        }
    },

    async addCurrentZone() {
        if (!this.currentGps) {
            this.toast(this.text('safe_loc.no_gps', null, 'No GPS signal'), 'error');
            return;
        }

        const values = await this.showZoneDialog(null);
        if (!values) return;

        try {
            const data = await this._enqueueWrite(() => this.requestJson('/api/surveillance/safe-locations', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name: values.name,
                    lat: this.currentGps.lat,
                    lng: this.currentGps.lng,
                    radiusM: values.radiusM
                })
            }));
            if (!data.zone) throw new Error(this.text('safe_loc.add_zone_failed', null, 'Failed to add zone'));

            this.zones.push(data.zone);
            this.addSavedZoneToMap(data.zone);
            this.updateEditCircleRadius(values.radiusM);
            const slider = document.getElementById('safeLocRadiusSlider');
            if (slider) slider.value = String(values.radiusM);
            this.renderZoneList();
            this.toast(this.text('safe_loc.zone_added', {
                name: data.zone.name,
                radius: data.zone.radiusM
            }, '"' + data.zone.name + '" added'), 'success');
        } catch (e) {
            this.toast(e.message || this.text('safe_loc.add_zone_failed', null, 'Failed to add zone'), 'error');
        }
    },

    async editZone(id) {
        const zone = this.zones.find(z => z.id === id);
        if (!zone) return;

        const values = await this.showZoneDialog(zone);
        if (!values) return;

        try {
            await this._enqueueWrite(() => this.requestJson('/api/surveillance/safe-locations', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    id: id,
                    name: values.name,
                    radiusM: values.radiusM
                })
            }));

            zone.name = values.name;
            zone.radiusM = values.radiusM;
            this.refreshSavedZoneOnMap(zone);
            this.renderZoneList();
            this.toast(this.text('safe_loc.zone_updated', {
                name: zone.name
            }, '"' + zone.name + '" updated'), 'success');
        } catch (e) {
            this.toast(e.message || this.text(
                'safe_loc.update_zone_failed',
                null,
                'Failed to update zone'
            ), 'error');
        }
    },

    async toggleZone(id, enabled) {
        const zone = this.zones.find(z => z.id === id);
        if (!zone || this.zoneTogglePending[id]) return;
        const previous = zone.enabled;
        this.zoneTogglePending[id] = true;
        zone.enabled = enabled;
        this.renderZoneList();
        try {
            await this._enqueueWrite(() => this.requestJson('/api/surveillance/safe-locations', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id, enabled })
            }));

            // Update map circle color
            if (this.zoneCircles[id]) {
                const color = enabled ? '#10b981' : '#6b7280';
                this.zoneCircles[id].setStyle({ color, fillColor: color, dashArray: enabled ? null : '5,5' });
            }
        } catch (e) {
            zone.enabled = previous;
            this.toast(e.message || this.text('safe_loc.toggle_failed', null, 'Failed to toggle'), 'error');
        } finally {
            delete this.zoneTogglePending[id];
            this.renderZoneList();
        }
    },

    async deleteZone(id) {
        const zone = this.zones.find(z => z.id === id);
        if (!zone) return;
        if (!(BYD.utils && BYD.utils.confirmDialog)) {
            this.toast(this.text('errors.generic', null, 'Something went wrong'), 'error');
            return;
        }

        const confirmed = await BYD.utils.confirmDialog({
            title: this.text('safe_loc.delete_title', null, 'Delete safe zone?'),
            body: this.text(
                'safe_loc.delete_body',
                {name: zone.name},
                'Delete "' + zone.name + '"?'
            ),
            confirmLabel: this.text('common.delete', null, 'Delete'),
            cancelLabel: this.text('common.cancel', null, 'Cancel'),
            danger: true
        });
        if (!confirmed) return;

        try {
            await this._enqueueWrite(() => this.requestJson('/api/surveillance/safe-locations', {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            }));
            this.zones = this.zones.filter(z => z.id !== id);
            this.removeSavedZoneFromMap(id);
            this.renderZoneList();
            this.toast(this.text('safe_loc.zone_deleted', null, 'Zone deleted'), 'success');
        } catch (e) {
            this.toast(e.message || this.text('errors.delete_failed', null, 'Failed to delete'), 'error');
        }
    },

    async refreshStatus() {
        if (!this.ready) {
            this.ready = await this.loadData();
            this.updateUI();
            return;
        }
        try {
            const resp = await fetch('/api/surveillance/safe-locations');
            const data = await resp.json();
            this.currentGps = data.hasGps ? { lat: data.lat, lng: data.lng, accuracy: data.accuracy } : null;

            // Update GPS marker
            if (this.map && this.currentGps) {
                if (this.gpsMarker) {
                    this.gpsMarker.setLatLng([this.currentGps.lat, this.currentGps.lng]);
                } else {
                    this.gpsMarker = L.circleMarker([this.currentGps.lat, this.currentGps.lng], {
                        radius: 7, fillColor: '#3b82f6', fillOpacity: 1, color: '#fff', weight: 2
                    }).addTo(this.map);
                }

                // Move the preview circle with GPS, or create it when the first
                // location fix arrives after the map was initialized.
                if (this.editCircle) {
                    this.editCircle.setLatLng([this.currentGps.lat, this.currentGps.lng]);
                } else {
                    this.createEditCircle(this.currentGps.lat, this.currentGps.lng);
                }
            }

            this.updateStatusText(data);
        } catch (e) { /* silent */ }
    }
};
