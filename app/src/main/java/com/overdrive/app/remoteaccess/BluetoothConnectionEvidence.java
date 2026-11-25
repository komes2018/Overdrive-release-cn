package com.overdrive.app.remoteaccess;

import org.json.JSONObject;

/** Immutable native peer-state proof; the caller fences its source and active mode. */
public final class BluetoothConnectionEvidence {
    public enum State { CONNECTED, DISCONNECTED, UNKNOWN }

    private static final String[] KEYS = {"connectionReadKnown", "connectionState",
            "radioReadKnown", "radioOn", "connectionObservedAtElapsedMs",
            "connectionModeGeneration"};
    public final State state;
    public final boolean connectionReadKnown, radioReadKnown, radioOn;
    public final long observedAtElapsedMs;
    public final String modeGeneration;

    private BluetoothConnectionEvidence(State state, boolean connectionReadKnown,
            boolean radioReadKnown, boolean radioOn, long observedAtElapsedMs,
            String modeGeneration) {
        this.state = state;
        this.connectionReadKnown = connectionReadKnown;
        this.radioReadKnown = radioReadKnown;
        this.radioOn = radioOn;
        this.observedAtElapsedMs = observedAtElapsedMs;
        this.modeGeneration = modeGeneration;
    }

    /** Old producers without any proof fields remain on the legacy RSSI path. */
    public static boolean hasAny(JSONObject sample) {
        for (String key : KEYS) if (sample.has(key)) return true;
        return false;
    }

    public static BluetoothConnectionEvidence parse(JSONObject sample) {
        boolean connectionKnown = bool(sample, "connectionReadKnown");
        boolean radioKnown = bool(sample, "radioReadKnown");
        boolean radioOn = bool(sample, "radioOn");
        Object rawState = sample.opt("connectionState");
        State state;
        if ("connected".equals(rawState)) state = State.CONNECTED;
        else if ("disconnected".equals(rawState)) state = State.DISCONNECTED;
        else if ("unknown".equals(rawState)) state = State.UNKNOWN;
        else throw new IllegalArgumentException("Invalid native connection state");
        Object rawTime = sample.opt("connectionObservedAtElapsedMs");
        if (!(rawTime instanceof Byte || rawTime instanceof Short
                || rawTime instanceof Integer || rawTime instanceof Long)
                || ((Number) rawTime).longValue() < -1)
            throw new IllegalArgumentException("Invalid native connection timestamp");
        Object rawMode = sample.opt("connectionModeGeneration");
        if (state == State.UNKNOWN && !connectionKnown
                && (!sample.has("connectionModeGeneration") || rawMode == JSONObject.NULL))
            rawMode = "";
        if (!(rawMode instanceof String) || ((String) rawMode).length() > 128)
            throw new IllegalArgumentException("Invalid native connection mode generation");
        String generation = (String) rawMode;
        if (connectionKnown != (state != State.UNKNOWN) || (!radioKnown && radioOn))
            throw new IllegalArgumentException("Contradictory native connection proof");
        if (!generation.isEmpty() && !canonicalGeneration(generation))
            throw new IllegalArgumentException("Invalid native connection mode generation");
        if (connectionKnown && (generation.isEmpty() || ((Number) rawTime).longValue() < 0))
            throw new IllegalArgumentException("A known connection requires its native scope");
        return new BluetoothConnectionEvidence(state, connectionKnown, radioKnown, radioOn,
                ((Number) rawTime).longValue(), generation);
    }

    public boolean healthyAt(long now, long staleMs) {
        return connectionReadKnown && state != State.UNKNOWN && radioReadKnown && radioOn
                && !modeGeneration.isEmpty() && observedAtElapsedMs >= 0
                && observedAtElapsedMs <= now && now - observedAtElapsedMs <= staleMs;
    }

    private static boolean bool(JSONObject sample, String key) {
        Object value = sample.opt(key);
        if (!(value instanceof Boolean))
            throw new IllegalArgumentException(key + " must be a boolean");
        return (Boolean) value;
    }

    private static boolean canonicalGeneration(String value) {
        try { return java.util.UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }
}
