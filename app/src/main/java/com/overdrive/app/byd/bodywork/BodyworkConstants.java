package com.overdrive.app.byd.bodywork;

/**
 * Constants for BYD Bodywork SDK
 */
public final class BodyworkConstants {
    
    private BodyworkConstants() {}
    
    // Power levels
    public static final int POWER_LEVEL_OFF = 0;
    public static final int POWER_LEVEL_ACC = 1;
    public static final int POWER_LEVEL_ON = 2;
    
    // Door/Window states
    public static final int STATE_CLOSED = 0;
    public static final int STATE_OPEN = 1;

    // Door/lid area ids — the numbering used by getDoorState(area) and the door-event
    // callbacks. The front axis is by SEAT (driver / passenger), mapped to physical
    // left/right by the drive-side config; rear/hood/trunk/fuel-cap are fixed positions.
    public static final int AREA_FRONT_DRIVER = 1;
    public static final int AREA_FRONT_PASSENGER = 2;
    public static final int AREA_REAR_LEFT = 3;
    public static final int AREA_REAR_RIGHT = 4;
    public static final int AREA_HOOD = 5;
    public static final int AREA_TRUNK = 6;
    public static final int AREA_FUEL_CAP = 7;
    
    // Alarm states
    public static final int ALARM_OFF = 0;
    public static final int ALARM_ON = 1;
    
    // Battery voltage levels
    public static final int BATTERY_LOW = 0;
    public static final int BATTERY_NORMAL = 1;
    public static final int BATTERY_INVALID = 2;
    
    public static String powerLevelToString(int level) {
        switch (level) {
            case POWER_LEVEL_OFF: return "OFF";
            case POWER_LEVEL_ACC: return "ACC";
            case POWER_LEVEL_ON: return "ON";
            default: return "UNKNOWN(" + level + ")";
        }
    }
    
    public static String batteryLevelToString(int level) {
        switch (level) {
            case BATTERY_LOW: return "LOW";
            case BATTERY_NORMAL: return "NORMAL";
            case BATTERY_INVALID: return "INVALID";
            default: return "UNKNOWN(" + level + ")";
        }
    }
}
