package com.overdrive.app.mqtt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Declarative metadata for every telemetry field emitted by
 * {@link MqttConnectionManager#getLatestTelemetry()}.
 *
 * One table drives two things:
 *   1. Home Assistant MQTT discovery — component type, device_class, state_class,
 *      unit, icon, entity_category (diagnostic) and friendly name for each entity.
 *   2. Change detection — the per-key quantization step ("deadband") used by
 *      {@link TelemetryDiffer} to decide whether a value has meaningfully changed.
 *
 * Keys not registered here fall back to a generic diagnostic sensor with a
 * prettified name and value-type-based change detection, so nothing ever breaks
 * if the Java payload gains a field before this catalog is updated.
 */
public final class TelemetryFieldCatalog {

    /** Components. */
    public static final String SENSOR = "sensor";
    public static final String BINARY = "binary_sensor";
    /** Sentinel: present in the payload but never mapped to an HA entity (e.g. arrays, timestamps). */
    public static final String NONE = null;

    /** Time / monotonic fields: never trigger a "change" and never get an HA entity. */
    public static final Set<String> EXCLUDED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("utc", "vd_timestamp")));

    /** Immutable metadata for a single telemetry key. */
    public static final class Field {
        public final String key;
        public final String name;          // HA friendly name
        public final String component;     // SENSOR / BINARY / NONE
        public final String deviceClass;   // HA device_class or null
        public final String stateClass;    // measurement / total / total_increasing or null
        public final String unit;          // unit_of_measurement or null
        public final String icon;          // mdi:... or null
        public final boolean diagnostic;   // entity_category = diagnostic
        public final double precision;     // deadband step; <=0 means exact / type-based

        Field(String key, String name, String component, String deviceClass, String stateClass,
              String unit, String icon, boolean diagnostic, double precision) {
            this.key = key;
            this.name = name;
            this.component = component;
            this.deviceClass = deviceClass;
            this.stateClass = stateClass;
            this.unit = unit;
            this.icon = icon;
            this.diagnostic = diagnostic;
            this.precision = precision;
        }

        public boolean isBinary() { return BINARY.equals(component); }
        public boolean isDiscoverable() { return component != null; }
    }

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    private static void add(String key, String name, String component, String deviceClass,
                            String stateClass, String unit, String icon, boolean diag, double precision) {
        FIELDS.put(key, new Field(key, name, component, deviceClass, stateClass, unit, icon, diag, precision));
    }

    static {
        final String MEAS = "measurement";
        final String TOTI = "total_increasing";

        // ---------- Core driving / energy ----------
        add("soc",        "动力电池电量",     SENSOR, "battery",     MEAS, "%",    "mdi:battery",            false, 0.1);
        // "Motor Power", not "Power": this is drive-motor kW. The automation signal named
        // `power` is the IGNITION level (off/acc/on), published here as `power_level` —
        // two unrelated facts that both read as "power". See SignalMqttMap.
        add("power",      "电机功率",         SENSOR, "power",       MEAS, "kW",   "mdi:flash",              false, 0.1);
        add("target_soc", "目标电量 (SOC)",   SENSOR, "battery",     MEAS, "%",    "mdi:battery-sync",       false, 1);
        add("charge_power","充电功率",        SENSOR, "power",       MEAS, "kW",   "mdi:battery-charging",   false, 0.1);
        add("speed",      "车速",             SENSOR, "speed",       MEAS, "km/h", "mdi:speedometer",        false, 0.1);
        add("lat",        "纬度",             SENSOR, null,          MEAS, "°",    "mdi:latitude",           true,  0.00001);
        add("lon",        "经度",             SENSOR, null,          MEAS, "°",    "mdi:longitude",          true,  0.00001);
        add("elevation",  "海拔高度",         SENSOR, "distance",    MEAS, "m",    "mdi:image-filter-hdr",   true,  1);
        add("heading",    "行驶航向",         SENSOR, null,          MEAS, "°",    "mdi:compass",            true,  1);
        add("gear",       "当前挡位",         SENSOR, "enum",        null, null,   "mdi:car-shift-pattern",  false, 0);
        // 0.1 deadband, matching the finest resolution the odometer register offers. At the
        // previous 1 km step a decimal change was not a "change", so in HA change-only mode the
        // decimals only refreshed on a whole-km crossing or the heartbeat.
        add("odometer",   "总里程",           SENSOR, "distance",    TOTI, "km",   "mdi:counter",            false, 0.1);

        // ---------- Charging ----------
        add("is_charging",          "充电状态",            BINARY, "battery_charging", null, null, null,                 false, 0);
        add("is_dcfc",              "直流快充",            BINARY, null,               null, null, "mdi:ev-station",     false, 0);
        add("is_parked",            "驻车状态",            BINARY, null,               null, null, "mdi:car-brake-parking", false, 0);
        add("charging_pct",         "充电进度",            SENSOR, "battery",          MEAS, "%",  "mdi:battery-charging", false, 1);
        add("charging_eta_hours",   "充电剩余时间 (小时)", SENSOR, "duration",         null, "h",  "mdi:timer-sand",     false, 0);
        add("charging_eta_minutes", "充电剩余时间 (分钟)", SENSOR, "duration",         null, "min","mdi:timer-sand",     false, 0);
        add("charging_capacity_kwh","累计充电电量",        SENSOR, "energy",           TOTI, "kWh","mdi:battery-charging-high", false, 0.1);
        add("charging_capacity_incomplete", "充电电量 (未完成)", BINARY, null,         null, null, "mdi:alert-circle-outline", true, 0);
        add("charging_capacity_estimated",  "充电电量 (估算)",   BINARY, null,         null, null, "mdi:approximately-equal", true, 0);
        add("charging_capacity_source",     "充电电量数据源",     SENSOR, "enum",      null, null, "mdi:source-branch", true, 0);
        add("charging_v2l",         "对外放电 (V2L)",      BINARY, null,               null, null, "mdi:home-lightning-bolt", false, 0);
        // Published for the controllable charge-limit entities' state topics;
        // do not also create duplicate read-only sensor entities.
        add("charge_cap_enabled",   "充电限制状态",        NONE, null,                 null, null, null,                 true,  0);
        add("charge_cap_percent",   "充电限制百分比",      NONE, null,                 null, null, null,                 true,  0);
        add("charging_state",       "充电阶段",            SENSOR, "enum",             null, null, "mdi:battery-charging", true, 0);
        add("charger_state",        "充电机状态",          SENSOR, "enum",             null, null, "mdi:ev-station",     true,  0);
        add("charging_mode",        "充电模式",            SENSOR, "enum",             null, null, "mdi:ev-station",     true,  0);
        add("charging_gun",         "充电枪连接状态",      SENSOR, "enum",             null, null, "mdi:power-plug",     true,  0);
        add("charging_type",        "充电类型",            SENSOR, "enum",             null, null, "mdi:power-plug",     true,  0);
        add("wireless_charging_left",  "左侧无线充电",     SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);
        add("wireless_charging_right", "右侧无线充电",     SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);
        add("wireless_charging_status","无线充电状态",     SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);

        // ---------- Range / consumption / trip ----------
        add("ev_range_km",        "纯电续航里程",       SENSOR, "distance", null, "km", "mdi:map-marker-distance", false, 1);
        add("fuel_range_km",      "燃油续航里程",       SENSOR, "distance", null, "km", "mdi:gas-station",         true,  1);
        add("bodywork_range_km",  "仪表显示续航",       SENSOR, "distance", null, "km", "mdi:map-marker-distance", true,  1);
        add("ev_mileage_km",      "纯电行驶总里程",     SENSOR, "distance", TOTI, "km", "mdi:counter",             true,  1);
        add("fuel_pct",           "油箱油量",           SENSOR, null,       MEAS, "%",  "mdi:gas-station",         false, 1);
        add("trip_km",            "单次里程",           SENSOR, "distance", null, "km", "mdi:map-marker-path",     false, 0.1);
        add("trip_hours",         "单次行驶时长",       SENSOR, "duration", null, "h",  "mdi:timer",               false, 0);
        add("trip_kwh",           "单次电耗",           SENSOR, "energy",   null, "kWh","mdi:lightning-bolt",      false, 0.1);
        add("consumption_50km",   "近 50 km 平均电耗",     SENSOR, null,       MEAS, "kWh/100 km", "mdi:lightning-bolt", false, 0.1);
        add("driving_time_hours", "总行驶时间",         SENSOR, "duration", null, "h",  "mdi:timer",               true,  0);
        add("total_elec_con",     "累计总用电量",       SENSOR, "energy",   TOTI, "kWh","mdi:lightning-bolt",      true,  0.1);
        add("total_fuel_con",     "累计总耗油量",       SENSOR, null,       TOTI, "L",  "mdi:gas-station",         true,  0.1);
        add("energy_mode",        "能量模式 (EV/HEV)",  SENSOR, "enum",     null, null, "mdi:leaf",                true,  0);
        add("op_mode",            "驾驶模式",           SENSOR, "enum",     null, null, "mdi:cog",                 true,  0);

        // ---------- Temperatures ----------
        add("ext_temp",            "车外温度",         SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",        false, 0.1);
        add("batt_temp",           "动力电池温度",     SENSOR, "temperature", MEAS, "°C", "mdi:battery-heart-variant", false, 0.1);
        add("cabin_temp",          "座舱温度",         SENSOR, "temperature", MEAS, "°C", "mdi:home-thermometer",   false, 0.1);
        add("inside_temp",         "车内温度",         SENSOR, "temperature", MEAS, "°C", "mdi:home-thermometer",   true,  0.1);
        add("coolant_temp",        "冷却液温度",       SENSOR, "temperature", MEAS, "°C", "mdi:coolant-temperature",true,  0.1);
        add("bodywork_batt_temp",  "车身电池温度",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",       true,  0.1);
        add("cell_t_max",          "电芯最高温度",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-high",  true,  0.1);
        add("cell_t_min",          "电芯最低温度",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-low",   true,  0.1);
        add("cell_t_avg",          "电芯平均温度",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",       true,  0.1);
        add("cell_t_delta",        "电芯最大温差",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-lines", true,  0.1);

        // ---------- HV battery / cells / SOH ----------
        add("soh",        "电池健康度 (估算)", SENSOR, "battery", MEAS, "%",  "mdi:battery-heart",     false, 0.1);
        add("soh_oem",    "电池健康度 (原厂)", SENSOR, "battery", MEAS, "%",  "mdi:battery-heart",     true,  0.1);
        add("capacity",   "可用电池容量",      SENSOR, "energy_storage", MEAS, "kWh", "mdi:battery",   false, 0.1);
        add("capacity_ah", "电池额定容量",     SENSOR, null,      MEAS, "Ah", "mdi:battery",           true,  0.1);
        add("hv_pack_v",  "动力电池总电压",    SENSOR, "voltage", MEAS, "V",  "mdi:flash",             true,  0.1);
        add("cell_v_max", "电芯最高电压",     SENSOR, "voltage", MEAS, "V",  "mdi:flash",             true,  0.001);
        add("cell_v_min", "电芯最低电压",     SENSOR, "voltage", MEAS, "V",  "mdi:flash-outline",     true,  0.001);
        add("cell_v_delta","电芯最大压差",     SENSOR, "voltage", MEAS, "V",  "mdi:sine-wave",         true,  0.001);
        add("soc_hev",    "混动 SOC 状态",     SENSOR, "battery", MEAS, "%",  "mdi:battery-50",        true,  0.1);

        // ---------- 12V system ----------
        add("volt_12v",        "小电瓶电压",       SENSOR, "voltage", MEAS, "V", "mdi:car-battery", false, 0.1);
        add("volt_12v_level",  "小电瓶电量等级",   SENSOR, "enum",    null, null,"mdi:car-battery", true,  0);
        add("batt_12v_level",  "车身小电瓶等级",   SENSOR, "enum",    null, null,"mdi:car-battery", true,  0);

        // ---------- Drivetrain ----------
        add("motor_front_rpm",   "前电机转速",     SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("motor_rear_rpm",    "后电机转速",     SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("motor_front_torque","前电机扭矩",     SENSOR, null, MEAS, "Nm",  "mdi:engine",        true, 1);
        add("engine_rpm",        "发动机转速",     SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("accel_pct",         "油门踏板开度",   SENSOR, null, MEAS, "%",   "mdi:car-cruise-control", true, 1);
        add("brake_pct",         "刹车踏板开度",   SENSOR, null, MEAS, "%",   "mdi:car-brake-alert",    true, 1);
        add("steering_deg",      "方向盘转角",     SENSOR, null, MEAS, "°",   "mdi:steering",      true, 1);
        add("slope_deg",         "道路坡度",       SENSOR, null, MEAS, "°",   "mdi:angle-acute",   true, 0.5);

        // ---------- Tyres ----------
        add("tyre_p_fl", "左前轮胎压", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_fr", "右前轮胎压", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_rl", "左后轮胎压", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_rr", "右后轮胎压", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_t_fl", "左前轮胎温", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_fr", "右前轮胎温", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_rl", "左后轮胎温", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_rr", "右后轮胎温", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_system_state", "胎压监测系统状态", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_temp_state",   "胎温监测状态",     SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_p_state_fl",   "左前胎压状态",     SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_p_state_fr",   "右前胎压状态",     SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_p_state_rl",   "左后胎压状态",     SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_p_state_rr",   "右后胎压状态",     SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_leak_fl",      "左前胎漏气报警",   SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_leak_fr",      "右前胎漏气报警",   SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_leak_rl",      "左后胎漏气报警",   SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_leak_rr",      "右后胎漏气报警",   SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_signal_fl",    "左前胎传感器信号", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_signal_fr",    "右前胎传感器信号", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_signal_rl",    "左后胎传感器信号", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_signal_rr",    "右后胎传感器信号", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);

        // ---------- Lights (booleans) ----------
        add("light_low_beam",  "近光灯",         BINARY, "light", null, null, "mdi:car-light-dimmed", true, 0);
        add("light_high_beam", "远光灯",         BINARY, "light", null, null, "mdi:car-light-high",   true, 0);
        add("light_rear_fog",  "后雾灯",         BINARY, "light", null, null, "mdi:car-light-fog",    true, 0);
        add("light_front_fog", "前雾灯",         BINARY, "light", null, null, "mdi:car-light-fog",    true, 0);
        add("light_hazard",    "双闪危险报警灯", BINARY, "light", null, null, "mdi:car-light-alert",  true, 0);
        add("light_drl",       "日间行车灯",     BINARY, "light", null, null, "mdi:car-light-dimmed", true, 0);
        add("ambient_colour",  "氛围灯颜色",     SENSOR, null,   MEAS, null, "mdi:format-color-fill", true, 0);
        // Ambient main-switch state (1=on/0=off). Published only when the vehicle reports it —
        // see MqttConnectionManager — so this stays unavailable on a trim that cannot read it
        // rather than reporting a false "off". The controllable twin is the ambient_power switch.
        add("ambient_enabled", "氛围灯开关状态", BINARY, "light", null, null, "mdi:track-light", true, 0);
        add("light_left_turn", "左转向灯",       SENSOR, "enum", null, null, "mdi:arrow-left-bold", true, 0);
        add("light_right_turn","右转向灯",       SENSOR, "enum", null, null, "mdi:arrow-right-bold", true, 0);

        // ---------- Climate ----------
        add("ac_on",           "空调开关",      SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_cycle",        "内外循环",      SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_wind",         "出风模式",      SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_fan",          "空调风量",      SENSOR, null,   MEAS, null, "mdi:fan",             true, 0);
        add("temp_unit",       "温度单位",      SENSOR, "enum", null, null, "mdi:temperature-celsius", true, 0);
        add("climate_setpoint","主驾设定温度",  SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", false, 0.5);
        add("climate_setpoint_passenger", "副驾设定温度", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 0.5);
        // Published (normalized 1/0) for the steering_heat control switch's state topic;
        // do not also create a duplicate read-only sensor entity.
        add("steering_wheel_heat", "方向盘加热状态", NONE, null, null, null, null, true, 0);

        // ---------- Bodywork ----------
        add("wiper_state",   "雨刮状态",      SENSOR, "enum", null, null, "mdi:wiper",          true, 0);
        add("sunroof_state", "天窗状态",      SENSOR, "enum", null, null, "mdi:window-shutter", true, 0);
        add("sunroof_pos",   "天窗开启度",    SENSOR, null, MEAS, "%", "mdi:window-shutter", true, 1);
        add("sunshade_pct",  "遮阳帘开启度",  SENSOR, null,   MEAS, "%",  "mdi:blinds",         true, 1);
        add("drift_mode",    "漂移模式",      BINARY, null,   null, null, "mdi:car-sports",     true, 0);

        // ---------- Engine (PHEV) ----------
        add("engine_coolant_level", "发动机冷却液液位", SENSOR, "enum", null, null, "mdi:coolant-temperature", true, 0);
        add("oil_level",            "机油液位",         SENSOR, null,   MEAS, null, "mdi:oil-level", true, 1);
        add("engine_code",          "发动机故障码",     SENSOR, null,   null, null, "mdi:engine",    true, 0);

        // ---------- Safety / ADAS ----------
        add("speed_limit_warning",      "超速报警",          BINARY, "problem", null, null, "mdi:speedometer-slow", true, 0);
        // Child Presence Detection setting state (on/off). Published as 1/0 so the adas_cpd
        // control switch's state_topic (child_presence_detection) reflects real state, matching
        // the speed_limit_warning pattern. No "problem" device_class — CPD-on is the desired state.
        add("child_presence_detection", "后排遗留儿童检测",  BINARY, null, null, null, "mdi:car-child-seat", true, 0);
        add("emergency_alarm",          "紧急报警",          SENSOR, "enum", null, null, "mdi:alarm-light",     true, 0);
        // Ignition/accessory level (off/acc/on) — the twin of the automation `power` signal.
        // Named "Vehicle Power State" to keep it distinct from `power` (drive-motor kW) above.
        add("power_level",              "整车电源状态 (OK 挡 / OFF)", SENSOR, "enum", null, null, "mdi:power",   true, 0);
        add("mcu_status",               "车机 MCU 状态",     SENSOR, "enum", null, null, "mdi:chip",            true, 0);

        // ---------- Air quality ----------
        add("pm25_inside",  "车内 PM2.5",  SENSOR, "pm25", MEAS, "µg/m³", "mdi:air-filter", false, 1);
        add("pm25_outside", "车外 PM2.5", SENSOR, "pm25", MEAS, "µg/m³", "mdi:weather-hazy", false, 1);

        // ---------- Key / identity ----------
        add("key_battery",            "车钥匙电量",     SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_start_state",        "钥匙启动状态",   SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_missing",            "钥匙未检测到",   SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("key_bt_low_power",       "蓝牙钥匙低电量", SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_power_low",          "钥匙电量低",     SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("key_detection_reminder", "钥匙感应提醒",   SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("smart_key_warn",         "智能钥匙报警",   SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("vin",                    "车架号 (VIN)",   SENSOR, null,   null, null, "mdi:identifier",   true, 0);

        // ---------- Arrays / time: present but not mapped to entities ----------
        add("door_lock",            "车门锁状态",   NONE, null, null, null, null, true, 0);
        add("window_open",          "车窗开闭状态", NONE, null, null, null, null, true, 0);
        add("seatbelt",             "安全带状态",   NONE, null, null, null, null, true, 0);
        add("seat_heat",            "座椅加热",     NONE, null, null, null, null, true, 0);
        add("seat_cool",            "座椅通风",     NONE, null, null, null, null, true, 0);
        add("passenger_detection",  "乘员检测",     NONE, null, null, null, null, true, 0);
        add("radar_distances",      "雷达测距",     NONE, null, null, null, null, true, 0);
        add("utc",                  "UTC 时间",      NONE, null, null, null, null, true, 0);
        add("vd_timestamp",         "数据时间戳",   NONE, null, null, null, null, true, 0);
    }

    private TelemetryFieldCatalog() {}

    /** Registered field, or a generic diagnostic-sensor fallback for unknown keys. */
    public static Field get(String key) {
        Field f = FIELDS.get(key);
        if (f != null) return f;
        return new Field(key, prettify(key), SENSOR, null, null, null, null, true, 0);
    }

    /** Deadband step for change detection; <=0 means type-based / exact. */
    public static double precisionFor(String key) {
        Field f = FIELDS.get(key);
        return f != null ? f.precision : 0;
    }

    /** True if this key should produce a read-only HA sensor (registered, mappable, non-time). */
    public static boolean isDiscoverable(String key) {
        if (EXCLUDED.contains(key)) return false;
        // setting_* keys are surfaced as controllable entities (switch/select/number) via
        // VehicleControlCatalog, not as read-only sensors — skip the sensor component.
        if (key.startsWith("setting_")) return false;
        Field f = FIELDS.get(key);
        // Unknown keys: discoverable by default (generic diagnostic sensor) — arrays are
        // filtered at publish time by value type, so this stays safe.
        return f == null || f.component != null;
    }

    /**
     * True if this key's value should be published to its per-field state topic.
     * Broader than {@link #isDiscoverable}: a key can carry state (e.g. {@code setting_*}
     * read-back, or a control's state) without also generating a read-only sensor entity.
     * Only time/monotonic keys are withheld.
     */
    public static boolean isPublishable(String key) {
        return !EXCLUDED.contains(key);
    }

    /** "front_motor_rpm" -> "Front Motor Rpm" */
    static String prettify(String key) {
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : key;
    }
}
