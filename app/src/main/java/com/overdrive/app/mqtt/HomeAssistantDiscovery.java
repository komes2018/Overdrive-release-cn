package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Builds Home Assistant MQTT discovery payloads for the vehicle.
 *
 * Uses the modern device-based discovery (HA 2024.11+): a single retained
 * message at {@code <prefix>/device/<node_id>/config} defines the device and all
 * of its entities ("components") at once. Each component points at its own
 * per-field state topic ({@code <base_topic>/<key>}), so change-only publishing
 * and clean entity mapping are the same mechanism — no value templates, no
 * missing-key flapping.
 *
 * GPS is exposed as a {@code device_tracker} fed by {@code <base_topic>/location}
 * (JSON attributes), plus latitude/longitude diagnostic sensors.
 */
public final class HomeAssistantDiscovery {

    private HomeAssistantDiscovery() {}

    /** HA node id / device identifier derived from the OverDrive device id. */
    public static String nodeId(String deviceId) {
        String base = "overdrive_" + (deviceId == null ? "vehicle" : deviceId);
        return base.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Retained config topic for the whole device bundle. */
    public static String deviceConfigTopic(String discoveryPrefix, String deviceId) {
        String prefix = (discoveryPrefix == null || discoveryPrefix.isEmpty()) ? "homeassistant" : discoveryPrefix;
        return prefix + "/device/" + nodeId(deviceId) + "/config";
    }

    /** The topic HA publishes its birth/online message to (for re-announce). */
    public static String statusTopic(String discoveryPrefix) {
        String prefix = (discoveryPrefix == null || discoveryPrefix.isEmpty()) ? "homeassistant" : discoveryPrefix;
        return prefix + "/status";
    }

    /** Per-field state topic for a telemetry key. */
    public static String stateTopic(String baseTopic, String key) {
        return baseTopic + "/" + key;
    }

    public static String locationTopic(String baseTopic) {
        return baseTopic + "/location";
    }

    /**
     * Build the device-bundle discovery payload.
     *
     * @param snapshot the current telemetry — only keys present here get a component,
     *                 so PHEV-only / model-specific fields don't create dead entities.
     */
    public static String buildBundle(String deviceId, String vin, String model, String swVersion,
                                     String baseTopic, JSONObject snapshot,
                                     java.util.Set<String> stickyKeys, boolean includeControls) {
        String node = nodeId(deviceId);
        String availabilityTopic = baseTopic + "/availability";

        JSONObject bundle = new JSONObject();
        try {
            // ----- device -----
            JSONObject dev = new JSONObject();
            JSONArray ids = new JSONArray();
            ids.put(node);
            if (vin != null && !vin.isEmpty()) ids.put("overdrive_vin_" + vin);
            dev.put("ids", ids);
            dev.put("name", deviceName(model, vin));
            dev.put("mf", "BYD");
            if (model != null && !model.isEmpty()) dev.put("mdl", model);
            if (swVersion != null && !swVersion.isEmpty()) dev.put("sw", swVersion);
            if (vin != null && !vin.isEmpty()) dev.put("sn", vin);
            bundle.put("dev", dev);

            // ----- origin -----
            JSONObject origin = new JSONObject();
            origin.put("name", "OverDrive");
            if (swVersion != null && !swVersion.isEmpty()) origin.put("sw", swVersion);
            origin.put("url", "https://www.overdrive.qd.je");
            bundle.put("o", origin);

            // ----- shared availability (reuses the LWT) -----
            JSONArray avail = new JSONArray();
            JSONObject a = new JSONObject();
            a.put("topic", availabilityTopic);
            a.put("payload_available", "online");
            a.put("payload_not_available", "offline");
            avail.put(a);
            bundle.put("availability", avail);

            // ----- components -----
            JSONObject cmps = new JSONObject();
            boolean hasLat = false, hasLon = false;
            Iterator<String> it = snapshot.keys();
            while (it.hasNext()) {
                String key = it.next();
                if ("lat".equals(key)) hasLat = true;
                if ("lon".equals(key)) hasLon = true;
                if (!TelemetryFieldCatalog.isDiscoverable(key)) continue;
                if (snapshot.opt(key) instanceof JSONArray) continue; // arrays aren't single entities
                cmps.put(key, component(node, baseTopic, TelemetryFieldCatalog.get(key)));
            }

            // Sticky keys: a discoverable field that populates intermittently (e.g. hv_pack_v,
            // derived from cell data + pack capacity) can be ABSENT from this particular snapshot.
            // The caller's announcedKeys set is monotonic, so once a field has been announced we
            // keep its component here even when it's momentarily missing. Otherwise a re-announce
            // fired while it's absent would drop it from the bundle, and the re-announce trigger
            // (which only fires for keys NOT yet announced) would never re-add it — leaving the
            // entity orphaned/Unavailable in HA until a full reset (notably after an HA restart).
            // The component is built from the static catalog (no live value needed), and the
            // field's retained state already lives on its own state_topic, so HA rebinds it on the
            // next connect/restart.
            if (stickyKeys != null) {
                for (String key : stickyKeys) {
                    if (cmps.has(key)) continue;
                    if (!TelemetryFieldCatalog.isDiscoverable(key)) continue;
                    cmps.put(key, component(node, baseTopic, TelemetryFieldCatalog.get(key)));
                }
            }

            // device_tracker for GPS (attributes carry lat/lon; map dot in HA)
            if (hasLat && hasLon) {
                JSONObject tracker = new JSONObject();
                tracker.put("p", "device_tracker");
                tracker.put("name", "车辆位置");
                tracker.put("object_id", node + "_location");
                tracker.put("unique_id", node + "_location");
                tracker.put("json_attributes_topic", locationTopic(baseTopic));
                tracker.put("source_type", "gps");
                tracker.put("icon", "mdi:car-connected");
                cmps.put("location", tracker);
            }

            // ----- control components (only when vehicle control is enabled) -----
            if (includeControls) {
                BydVehicleData snap = currentVehicleData();
                // The charge-stop setter is present on trims where it is a no-op. Do not
                // advertise a controllable HA entity until telemetry contains the complete
                // readback pair from a write/read-back verified backend.
                boolean verifiedChargeCap = MqttPublisherService.hasVerifiedChargeCapState(snapshot);
                int targetSoc = snapshot.optInt("target_soc", BydVehicleData.UNAVAILABLE);
                boolean hasTargetSoc = targetSoc >= BydDataCollector.SOC_TARGET_MIN
                        && targetSoc <= BydDataCollector.SOC_TARGET_MAX;
                for (VehicleControlCatalog.ControlEntity ent : VehicleControlCatalog.all()) {
                    if (VehicleControlCatalog.isGenericChargeCapControl(ent.key)
                            && !verifiedChargeCap) {
                        continue;
                    }
                    if (("target_soc".equals(ent.key) || "battery_hold".equals(ent.key))
                            && !hasTargetSoc) {
                        continue;
                    }
                    if (!ent.isAvailable(snap)) continue;
                    JSONObject comp = ent.component(baseTopic, node);
                    if (comp != null) cmps.put("ctl_" + ent.key, comp);
                }
            }

            bundle.put("cmps", cmps);
        } catch (Exception ignored) {}
        return bundle.toString();
    }

    private static JSONObject component(String node, String baseTopic, TelemetryFieldCatalog.Field f) throws Exception {
        JSONObject c = new JSONObject();
        c.put("p", f.component);
        c.put("name", f.name);
        // object_id pins the entity_id (e.g. sensor.overdrive_<device>_soc) so it stays
        // stable and ASCII even when `name` is localised — HA would otherwise derive the
        // entity_id from `name`, which for a Chinese name yields a transliterated slug
        // (bi_ya_di_..._dong_li_dian_chi_dian_liang). Existing entities keep the id they
        // were created with; this only governs newly discovered ones.
        c.put("object_id", node + "_" + f.key);
        c.put("unique_id", node + "_" + f.key);
        c.put("state_topic", stateTopic(baseTopic, f.key));
        if (f.deviceClass != null) c.put("device_class", f.deviceClass);
        if (f.stateClass != null) c.put("state_class", f.stateClass);
        if (f.unit != null) c.put("unit_of_measurement", f.unit);
        if (f.icon != null) c.put("icon", f.icon);
        if (f.diagnostic) c.put("entity_category", "diagnostic");
        if (f.isBinary()) {
            c.put("payload_on", "1");
            c.put("payload_off", "0");
        }
        return c;
    }

    private static BydVehicleData currentVehicleData() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c.isInitialized() ? c.getData() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String deviceName(String model, String vin) {
        if (model != null && !model.isEmpty()) return "比亚迪 (" + model + ")";
        if (vin != null && vin.length() >= 6) return "比亚迪 (" + vin.substring(vin.length() - 6) + ")";
        return "比亚迪汽车";
    }
}
