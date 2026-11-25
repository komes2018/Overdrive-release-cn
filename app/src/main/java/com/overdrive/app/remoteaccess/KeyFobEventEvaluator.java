package com.overdrive.app.remoteaccess;

import java.util.HashSet;
import org.json.JSONObject;

/**
 * Event policy for the vehicle's physical fob CAN stream. A healthy receiver
 * preserves a historical Away edge during silence; silence never proves Near.
 * A lock that OverDrive itself confirmed with confirmed cabin absence may seed
 * the Away edge; a lone detection inside the following quiet period is the key
 * still beside the car, not an arrival.
 * This class does not own a thread, timer, receiver or vehicle command.
 */
public final class KeyFobEventEvaluator {
    public static final long FRESH_MS = 5000;
    public static final long POST_LOCK_QUIET_MS = 15_000;

    public static final class Observation {
        public final long revision, serial, generation, eventSequence, eventElapsed, scopeElapsed;
        public final String instance, reason;
        public final int mask, power, driverLock;
        public final boolean active, registered, tableRegistered;

        private Observation(JSONObject value) {
            revision = integer(value, "revision", 0, Long.MAX_VALUE);
            serial = integer(value, "observationSequence", 1, Long.MAX_VALUE);
            generation = integer(value, "sourceGeneration", 0, Long.MAX_VALUE);
            eventSequence = integer(value, "eventSequence", 0, Long.MAX_VALUE);
            eventElapsed = integer(value, "eventObservedAtElapsedMs", -1, Long.MAX_VALUE);
            mask = (int) integer(value, "rawMask", -1, 255);
            Object id = value.opt("instanceId");
            if (!(id instanceof String) || ((String) id).isEmpty() || ((String) id).length() > 120)
                throw new IllegalArgumentException("Invalid key fob source instance");
            instance = (String) id;
            JSONObject runtime = value.optJSONObject("runtime"), scope = value.optJSONObject("scope");
            if (runtime == null || scope == null)
                throw new IllegalArgumentException("Key fob runtime and scope are required");
            active = bool(runtime, "active");
            registered = bool(runtime, "registered");
            tableRegistered = bool(runtime, "tableRegistered");
            reason = runtime.opt("reason") instanceof String ? runtime.optString("reason") : "source_unavailable";
            power = (int) integer(scope, "power", -1, 3);
            driverLock = (int) integer(scope, "driverLock", -1, 2);
            scopeElapsed = integer(scope, "observedAtElapsedMs", -1, Long.MAX_VALUE);
            if (eventSequence == 0 && (eventElapsed != -1 || mask != -1))
                throw new IllegalArgumentException("A key fob value requires a native event");
            if (eventSequence > 0 && eventElapsed < 0)
                throw new IllegalArgumentException("A key fob event requires a timestamp and mask");
        }

        public static Observation parse(JSONObject value) { return new Observation(value); }
    }

    public static final class Snapshot {
        public final boolean arrival, departure, departureKnown, armed, lockArmed, lockEnabled, active, fresh, registered, tableRegistered;
        public final ProximityEvaluator.Event event;
        public final String state, lastKnownState, reason, newness;
        public final long eventSequence, sourceGeneration, lastEventElapsed, lastValidElapsed;
        public final int lastMask;

        private Snapshot(KeyFobEventEvaluator owner, long now, ProximityEvaluator.Event event, String newness) {
            boolean healthy = owner.healthy(now);
            this.event = event;
            arrival = event == ProximityEvaluator.Event.ARRIVAL;
            departure = event == ProximityEvaluator.Event.DEPARTURE;
            armed = healthy && owner.armed;
            lockArmed = healthy && owner.allowLock && owner.lastNear;
            lockEnabled = owner.allowLock;
            departureKnown = owner.allowsLock(now);
            active = healthy;
            registered = healthy && owner.registered;
            tableRegistered = healthy && owner.tableRegistered;
            lastMask = owner.lastMask;
            lastEventElapsed = owner.lastEventElapsed;
            lastValidElapsed = owner.lastValidElapsed;
            eventSequence = owner.eventSequence;
            sourceGeneration = owner.generation;
            fresh = healthy && ageFresh(now, lastValidElapsed);
            state = fresh ? owner.lastKnownState : "UNKNOWN";
            lastKnownState = healthy ? owner.lastKnownState : "UNKNOWN";
            reason = !healthy ? owner.reason : !"listening".equals(owner.reason) ? owner.reason
                    : !fresh ? "waiting_for_fresh_event"
                    : owner.lastNear ? "fob_detected" : "fob_absent";
            this.newness = newness;
        }

        public JSONObject toJson(long now, String mode) throws Exception {
            return new JSONObject().put("active", active).put("registered", registered)
                    .put("tableRegistered", tableRegistered).put("sourceStatus", active ? "listening" : reason)
                    .put("state", state).put("lastKnownState", lastKnownState).put("reason", reason)
                    .put("lastMask", lastMask).put("lastEventAgeMs", age(now, lastEventElapsed))
                    .put("lastValidEventAgeMs", age(now, lastValidElapsed)).put("fresh", fresh)
                    .put("armed", armed).put("newness", newness).put("mode", mode)
                    .put("lockArmed", lockArmed).put("lockEnabled", lockEnabled)
                    .put("departureKnown", departureKnown)
                    .put("event", event.name())
                    .put("eventSequence", eventSequence).put("sourceGeneration", sourceGeneration)
                    .put("unlockOnly", !lockEnabled);
        }
    }

    private final ProximityEvaluator.UnlockTrigger mode;
    private long expectedRevision;
    private final boolean allowLock;
    private final HashSet<String> retiredInstances = new HashSet<>();
    private String instance = "", lastKnownState = "UNKNOWN", reason = "disabled", newness = "none";
    private long serial, generation = -1, eventSequence, lastEventElapsed = -1;
    private long lastValidElapsed = -1, heartbeatElapsed = -1, scopeElapsed = -1, arrivalEventSequence = -1;
    private long departureEventSequence = -1;
    private long quietUntilElapsed = -1;
    private int lastMask = -1, driverLock = -1;
    private boolean armed, lastNear, active, registered, tableRegistered;

    public KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger mode, long expectedRevision) {
        this(mode, expectedRevision, false);
    }

    public KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger mode, long expectedRevision, boolean allowLock) {
        this.mode = mode;
        this.expectedRevision = expectedRevision;
        this.allowLock = allowLock;
    }

    /**
     * A1: a save that changed no fob rule bumped the revision; the retained evaluator follows it.
     * Presence is never cleared here. Envelopes carrying a stale revision are rejected by the
     * relay before {@link #observe}, and the revision check inside observe stays as defence in depth.
     */
    public void adoptRevision(long revision) {
        if (revision >= 0) expectedRevision = revision;
    }

    /**
     * A1: the rules this evaluator is built from or acts on — master switch, fob detection,
     * fob locking, Learning mode, the phones' unlock trigger and (19 Oct) the fob rail's own
     * {@code keyFobUnlockTrigger}, which selects this evaluator's mode — are equal in both effective
     * settings. Revision, phones, thresholds, the shared radio switch and timing windows may differ.
     */
    public static boolean canRetainAcrossSettingsChange(JSONObject oldEffective, JSONObject newEffective) {
        try {
            return oldEffective != null && newEffective != null
                    && oldEffective.optBoolean("enabled") == newEffective.optBoolean("enabled")
                    && oldEffective.optBoolean("keyFobEnabled") == newEffective.optBoolean("keyFobEnabled")
                    && oldEffective.optBoolean("keyFobLockEnabled") == newEffective.optBoolean("keyFobLockEnabled")
                    && oldEffective.optBoolean("dryRun", true) == newEffective.optBoolean("dryRun", true)
                    && oldEffective.optString("unlockTrigger", "away_to_near")
                            .equals(newEffective.optString("unlockTrigger", "away_to_near"))
                    && oldEffective.optString("keyFobUnlockTrigger", "near")
                            .equals(newEffective.optString("keyFobUnlockTrigger", "near"));
        } catch (Exception malformed) { return false; }
    }

    /**
     * A2: a lock OverDrive dispatched and confirmed was gated by confirmed cabin absence, so it is
     * equivalent to the car's own absence report. Only a healthy receiver accepts the seed; it arms
     * the Away edge and opens a {@link #POST_LOCK_QUIET_MS} window in which a lone detection is not
     * an arrival. Returns whether the seed applied.
     */
    public boolean seedAbsence(long observedElapsedMs) {
        return seedAbsence(observedElapsedMs, POST_LOCK_QUIET_MS);
    }

    /**
     * A2 with the quiet period supplied by the caller (18 Oct: the {@code postLockQuietMs} setting,
     * read live from the effective configuration); the one-argument form keeps the original
     * {@link #POST_LOCK_QUIET_MS}. A non-positive quiet period seeds the arming without a window.
     */
    public boolean seedAbsence(long observedElapsedMs, long quietMs) {
        if (observedElapsedMs < 0 || !healthy(observedElapsedMs)) return false;
        lastKnownState = "AWAY";
        lastValidElapsed = observedElapsedMs;
        armed = true;
        lastNear = false;
        arrivalEventSequence = -1;
        departureEventSequence = -1;
        quietUntilElapsed = observedElapsedMs + Math.max(0L, quietMs);
        return true;
    }

    public Snapshot observe(Observation value, long now, boolean enabled) {
        if (value.scopeElapsed > now)
            throw new IllegalArgumentException("Key fob scope timestamp is in the future");
        if (!instance.equals(value.instance)) {
            if (retiredInstances.contains(value.instance))
                throw new IllegalArgumentException("Retired key fob source");
            if (!instance.isEmpty()) retiredInstances.add(instance);
            if (retiredInstances.size() > 64)
                throw new IllegalArgumentException("Too many key fob source instances");
            clearPresence();
            instance = value.instance;
            serial = 0;
            generation = -1;
            eventSequence = 0;
        }
        if (value.serial <= serial || value.generation < generation)
            throw new IllegalArgumentException("Key fob observation was already consumed");
        if (value.generation != generation) {
            clearPresence();
            generation = value.generation;
            eventSequence = 0;
            lastMask = -1;
            lastEventElapsed = -1;
        }
        if (value.eventSequence < eventSequence)
            throw new IllegalArgumentException("Key fob event was already consumed");
        if (value.eventSequence == eventSequence && eventSequence > 0
                && (value.mask != lastMask || value.eventElapsed != lastEventElapsed))
            throw new IllegalArgumentException("Key fob event changed without a new sequence");
        // If app heartbeats stopped, an old Away seed cannot span that gap.
        if (heartbeatElapsed >= 0 && now - heartbeatElapsed > FRESH_MS) clearPresence();
        serial = value.serial;
        heartbeatElapsed = now;
        scopeElapsed = value.scopeElapsed;
        driverLock = value.driverLock;
        registered = value.registered;
        tableRegistered = value.tableRegistered;
        boolean lockEligible = eligibleScope(0, driverLock, allowLock);
        active = enabled && value.revision == expectedRevision && value.active && registered && tableRegistered
                && value.power == 0 && lockEligible && ageFresh(now, value.scopeElapsed);
        reason = !enabled ? "disabled" : value.revision != expectedRevision ? "revoked"
                : !value.active || !registered || !tableRegistered ? value.reason
                : value.power != 0 ? "vehicle_not_off" : !lockEligible ? "vehicle_lock_ineligible"
                : !ageFresh(now, value.scopeElapsed) ? "scope_stale" : "listening";
        boolean newEvent = value.eventSequence > eventSequence;
        ProximityEvaluator.Event event = ProximityEvaluator.Event.NONE;
        if (newEvent) {
            eventSequence = value.eventSequence;
            lastMask = value.mask;
            lastEventElapsed = value.eventElapsed;
        }
        if (!active) {
            clearPresence();
        } else if (newEvent) {
            if (!ageFresh(now, value.eventElapsed)) {
                clearPresence();
                reason = "event_stale";
            } else if (value.mask == 4) {
                if (allowLock && lastNear && driverLock == 1)
                    event = ProximityEvaluator.Event.DEPARTURE;
                lastKnownState = "AWAY";
                lastValidElapsed = value.eventElapsed;
                armed = true;
                // The car itself reported absence: a seeded quiet period no longer applies.
                quietUntilElapsed = -1;
                lastNear = false;
                arrivalEventSequence = -1;
                departureEventSequence = event == ProximityEvaluator.Event.DEPARTURE
                        ? value.eventSequence : -1;
            } else if (value.mask == 8) {
                // A2: inside the post-lock quiet period a lone detection consumes the seed like an
                // unarmed detection does today; it never becomes an arrival.
                boolean quiet = quietUntilElapsed >= 0 && value.eventElapsed < quietUntilElapsed;
                // B2 (19 Oct): in away_to_near mode a first detection of a locked car with no prior
                // absence frame or seed is ignored; the reason names that, so a field log explains
                // the missed unlock (19 Oct 21:18:38: rawMask=8 armed=false, no unlock). Diagnostic only.
                boolean unarmed = !quiet && driverLock == 2 && !lastNear && !armed
                        && mode != ProximityEvaluator.UnlockTrigger.NEAR;
                lastKnownState = "NEAR";
                lastValidElapsed = value.eventElapsed;
                if (!quiet && driverLock == 2 && !lastNear && (armed || mode == ProximityEvaluator.UnlockTrigger.NEAR))
                    event = ProximityEvaluator.Event.ARRIVAL;
                armed = false;
                lastNear = true;
                quietUntilElapsed = -1;
                if (quiet) reason = "post_lock_quiet";
                else if (unarmed) reason = "unarmed_detection";
                departureEventSequence = -1;
                if (event == ProximityEvaluator.Event.ARRIVAL) arrivalEventSequence = value.eventSequence;
            } else if (value.mask != 0) {
                clearPresence();
                reason = "unknown_event";
            }
        }
        newness = newEvent ? "event" : "heartbeat";
        return new Snapshot(this, now, event, newness);
    }

    public Snapshot current(long now) {
        if (!healthy(now)) {
            clearPresence();
            if (active) reason = "collector_stale";
            active = false;
        }
        return new Snapshot(this, now, ProximityEvaluator.Event.NONE, newness);
    }

    /** Healthy event history may confirm absence; its age never generates a command. */
    public boolean allowsLock(long now) {
        return allowLock && healthy(now) && !lastNear && "AWAY".equals(lastKnownState);
    }

    public static boolean eligibleScope(int power, int driverLock, boolean allowLock) {
        return power == 0 && (driverLock == 2 || (allowLock && driverLock == 1));
    }

    /** Heartbeats and ignored mask 0 cannot replay an arrival or invalidate its token. */
    public boolean decisionStillValid(Observation value, long now) {
        return decisionStillValid(value, now, ProximityEvaluator.Event.ARRIVAL);
    }

    /** A lock token needs a real departure edge, never a disconnected or stale source. */
    public boolean decisionStillValid(Observation value, long now, ProximityEvaluator.Event event) {
        boolean actionable = event == ProximityEvaluator.Event.ARRIVAL
                ? driverLock == 2 && value.eventSequence == arrivalEventSequence && lastNear
                : event == ProximityEvaluator.Event.DEPARTURE && allowLock && driverLock == 1
                        && value.eventSequence == departureEventSequence && "AWAY".equals(lastKnownState);
        return healthy(now) && instance.equals(value.instance) && generation == value.generation
                && actionable
                && ageFresh(now, value.eventElapsed) && ageFresh(now, lastValidElapsed);
    }

    /**
     * Cloud fallback re-validation (17 Oct product decision): everything
     * {@link #decisionStillValid(Observation, long, ProximityEvaluator.Event)} checks EXCEPT the age
     * of the fob event itself (and of the last valid event, which is that same event while no newer
     * one arrived). The 5 s event age bound belongs to the first (local) dispatch; the cloud leg
     * continues that already-dispatched decision, so only revocation counts: a healthy receiver of
     * the same instance and generation, and no newer event that replaced or contradicted the token
     * (an Away edge after an arrival, a detection after a departure, the car's own lock change).
     */
    public boolean decisionStillCurrent(Observation value, long now, ProximityEvaluator.Event event) {
        boolean actionable = event == ProximityEvaluator.Event.ARRIVAL
                ? driverLock == 2 && value.eventSequence == arrivalEventSequence && lastNear
                : event == ProximityEvaluator.Event.DEPARTURE && allowLock && driverLock == 1
                        && value.eventSequence == departureEventSequence && "AWAY".equals(lastKnownState);
        return healthy(now) && instance.equals(value.instance) && generation == value.generation
                && actionable;
    }

    private boolean healthy(long now) {
        return active && ageFresh(now, heartbeatElapsed) && ageFresh(now, scopeElapsed);
    }

    private void clearPresence() {
        armed = false;
        lastNear = false;
        arrivalEventSequence = -1;
        departureEventSequence = -1;
        lastKnownState = "UNKNOWN";
        lastValidElapsed = -1;
    }

    private static boolean ageFresh(long now, long observed) {
        return observed >= 0 && observed <= now && now - observed <= FRESH_MS;
    }

    private static Object age(long now, long observed) {
        return observed < 0 || observed > now ? JSONObject.NULL : now - observed;
    }

    private static boolean bool(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof Boolean)) throw new IllegalArgumentException(key + " must be a boolean");
        return (Boolean) raw;
    }

    private static long integer(JSONObject value, String key, long min, long max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long))
            throw new IllegalArgumentException(key + " must be an integer");
        long number = ((Number) raw).longValue();
        if (number < min || number > max) throw new IllegalArgumentException(key + " is out of range");
        return number;
    }
}
