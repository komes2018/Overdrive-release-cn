package com.overdrive.app.remoteaccess;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Pure DI5 gate/actuation arithmetic for {@code RemoteAccessApiHandler.performDecision} and
 * {@code relayActuationCheck}, which cannot run on the JVM. Precedent: {@link ProximityCooldownPolicy},
 * {@link BluetoothProximityDecisionGuard}. Nothing here reads a clock, a setting or the SDK.
 */
public final class DiLink5GateActuationPolicy {
    /** Readback cadence of the daemon confirmation loop (today's 350 ms, all rails). */
    public static final long CONFIRMATION_POLL_MS = 350L;
    /** Confirmation windows per actuation rail: typed ~1 Hz, OTA agreeing pairs, carsvc ≤ 6 s dumps. */
    public static final long TYPED_CONFIRMATION_WINDOW_MS = 4_000L;
    public static final long OTA_CONFIRMATION_WINDOW_MS = 8_000L;
    public static final long CARSVC_CONFIRMATION_WINDOW_MS = 12_000L;
    /**
     * Q14: a dispatch needs at least this much lease left when the write path confirms slower than
     * the typed rail ({@code ota}, {@code carsvc}); the typed path keeps today's behaviour exactly.
     */
    public static final long MIN_ACTUATION_LEASE_MS = 1_500L;
    /**
     * Case (C): the daemon's carsvc evidence may authorise a write only while this fresh (the same
     * bound as {@code DiLink5DoorLockControl.DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS}).
     */
    public static final long CARSVC_EVIDENCE_WRITE_MAX_AGE_MS = 6_000L;

    /** Outcome of the daemon confirmation loop. */
    public enum Confirmation { CONFIRMED, UNCONFIRMED }

    /** Injected sleep so the loop runs on the JVM with a fake clock. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private DiLink5GateActuationPolicy() {}

    /**
     * Rail detail appended to {@code recordAction} messages: {@code ""} for no evidence or the
     * default {@code typed-cabin} rail (so the default-path texts stay byte-identical to today and
     * the 60 s same-message dedup keeps matching), {@code " (rail=<tag>)"} for any other rail.
     * Never contains an age: a varying number would defeat the dedup and flood the event list.
     */
    public static String detailSuffix(BydDataCollector.DiLink5LockEvidence evidence) {
        if (evidence == null || evidence.rail == null
                || evidence.rail == DiLink5LockRail.TYPED_CABIN) {
            return "";
        }
        return " (rail=" + evidence.rail.tag() + ")";
    }

    /** Log-only description (daemon log, never a {@code recordAction} message): rail, state, age. */
    public static String describe(BydDataCollector.DiLink5LockEvidence evidence, long nowElapsedMs) {
        if (evidence == null || !evidence.known()) return "rail=none state=0 ageMs=-1";
        return "rail=" + evidence.rail.tag() + " state=" + evidence.state
                + " ageMs=" + (nowElapsedMs - evidence.observedAtElapsedMs);
    }

    /** App-domain target of a scope action: {@code unlock} → 1, {@code lock} → 2 (performDecision's convention). */
    public static int targetOf(String action) {
        return "unlock".equals(action) ? BydDataCollector.DOOR_STATE_UNLOCK : BydDataCollector.DOOR_STATE_LOCK;
    }

    /** Q12 windows: 4 s typed (or no rail), 8 s ota, 12 s carsvc. */
    public static long confirmationWindowMs(DiLink5LockRail rail) {
        if (rail == null) return TYPED_CONFIRMATION_WINDOW_MS;
        switch (rail) {
            case OTA: return OTA_CONFIRMATION_WINDOW_MS;
            case CARSVC: return CARSVC_CONFIRMATION_WINDOW_MS;
            default: return TYPED_CONFIRMATION_WINDOW_MS;
        }
    }

    /**
     * The rail the actuator confirms on: {@code ota} whenever it holds fresh valid evidence (the
     * app can read that rail back directly), otherwise the gate's selected rail; null when the
     * selected evidence is unknown (the numeric gate blocks that case before dispatch anyway).
     */
    public static DiLink5LockRail preferredActuationRail(BydDataCollector.DiLink5LockEvidence selected,
                                                         BydDataCollector.DiLink5LockEvidence otaEvidence) {
        if (otaEvidence != null && otaEvidence.known() && otaEvidence.rail == DiLink5LockRail.OTA) {
            return DiLink5LockRail.OTA;
        }
        return selected == null || !selected.known() ? null : selected.rail;
    }

    /** Lease left on the original observation at {@code now} (may be negative). */
    public static long remainingLeaseMs(long observed, long freshnessMs, long now) {
        return observed + freshnessMs - now;
    }

    /**
     * The proximity scope for a dispatch, carrying the actuation rail's evidence (tag, app-domain
     * state, SDK read time, raw) when it is attachable (known state, rail, read time &gt; 0);
     * otherwise the plain 5-field scope, which the app treats as today's typed path.
     */
    public static DiLink5ProximityCommandGuard.Scope gateScope(
            String token, String action, String modeGeneration, long revision, long expiresAt,
            BydDataCollector.DiLink5LockEvidence actuationEvidence) {
        if (actuationEvidence == null || !actuationEvidence.known() || actuationEvidence.rail == null
                || actuationEvidence.observedAtElapsedMs <= 0L) {
            return new DiLink5ProximityCommandGuard.Scope(token, action, modeGeneration, revision, expiresAt);
        }
        return new DiLink5ProximityCommandGuard.Scope(token, action, modeGeneration, revision, expiresAt,
                actuationEvidence.rail.tag(), actuationEvidence.state,
                actuationEvidence.observedAtElapsedMs, actuationEvidence.raw);
    }

    /**
     * Q14 (planner deviation from the design's "all rails"): only an {@code ota} or {@code carsvc}
     * actuation rail is held to {@link #MIN_ACTUATION_LEASE_MS}; null and the typed rail never block
     * here, so the default path is exactly today's.
     */
    public static boolean insufficientLease(DiLink5LockRail actuationRail, long observed,
                                            long freshnessMs, long now) {
        return actuationRail != null && actuationRail != DiLink5LockRail.TYPED_CABIN
                && remainingLeaseMs(observed, freshnessMs, now) < MIN_ACTUATION_LEASE_MS;
    }

    /**
     * Legacy (non-Di5) readback wrapped as evidence stamped with its own read time, so two reads
     * {@link #CONFIRMATION_POLL_MS} apart remain two distinct readbacks exactly as today.
     */
    public static BydDataCollector.DiLink5LockEvidence legacyReadback(int state, long readAtElapsedMs) {
        return new BydDataCollector.DiLink5LockEvidence(state, readAtElapsedMs, null, Integer.MIN_VALUE, 0L);
    }

    /**
     * Q12: two DISTINCT target readbacks (different {@code observedAtElapsedMs}) within
     * {@code windowMs} confirm; the same observation seen again counts once; a valid non-target or
     * INVALID (or null) evidence resets the count. Polls every {@link #CONFIRMATION_POLL_MS}.
     */
    public static Confirmation confirmByDistinctReadbacks(
            Supplier<BydDataCollector.DiLink5LockEvidence> evidence, int target, long windowMs,
            LongSupplier clock, Sleeper sleep) throws InterruptedException {
        long deadline = clock.getAsLong() + windowMs;
        int distinct = 0;
        long lastCountedAt = Long.MIN_VALUE;
        while (clock.getAsLong() < deadline) {
            BydDataCollector.DiLink5LockEvidence current = evidence.get();
            if (current != null && current.state == target) {
                if (current.observedAtElapsedMs != lastCountedAt) {
                    lastCountedAt = current.observedAtElapsedMs;
                    if (++distinct >= 2) return Confirmation.CONFIRMED;
                }
            } else {
                distinct = 0;
                lastCountedAt = Long.MIN_VALUE;
            }
            sleep.sleep(CONFIRMATION_POLL_MS);
        }
        return Confirmation.UNCONFIRMED;
    }

    /**
     * Case (C) daemon re-validation at the app's pre-write relay check (LOW-4): the current ledger
     * evidence must still be the {@code carsvc} rail, valid, not already at the scope's target and
     * at most {@link #CARSVC_EVIDENCE_WRITE_MAX_AGE_MS} old. A new dump exists only if the
     * background poll happened to run in the interval; nothing is spawned for this check.
     */
    public static boolean carsvcEvidenceAllowsWrite(BydDataCollector.DiLink5LockEvidence evidence,
                                                    DiLink5ProximityCommandGuard.Scope scope, long now) {
        if (evidence == null || scope == null || !evidence.known()
                || evidence.rail != DiLink5LockRail.CARSVC) {
            return false;
        }
        long ageMs = now - evidence.observedAtElapsedMs;
        return evidence.state != targetOf(scope.action)
                && ageMs >= 0L && ageMs <= CARSVC_EVIDENCE_WRITE_MAX_AGE_MS;
    }

    // ── Shared door-lock actuation helpers (17 Oct): cloud fallback actuator + closure naming ──────

    /**
     * Path labels of a proximity decision's actuation, for {@code recordAction} details and logs:
     * {@code SDK} = cloud leg not sent; {@code SDK_THEN_CLOUD} = local leg accepted, cloud leg sent;
     * {@code CLOUD} = local leg failed outright, cloud leg sent.
     */
    public static final String PATH_SDK = "SDK";
    public static final String PATH_SDK_THEN_CLOUD = "SDK_THEN_CLOUD";
    public static final String PATH_CLOUD = "CLOUD";

    /**
     * Di5 immediate rule (J3): the typed cabin rail cannot read back its own write when it holds no
     * fresh valid reading at dispatch (disabled, INVALID or stale → {@code null} from
     * {@code readDiLink5GateDoorLockEvidenceFor(TYPED_CABIN)}, or an unknown state), so the cloud
     * leg goes out right after the SDK write instead of after the confirmation window. Never true
     * off Di5.
     */
    public static boolean cloudFallbackImmediate(boolean diLink5,
                                                 BydDataCollector.DiLink5LockEvidence typedEvidence) {
        return diLink5 && (typedEvidence == null || !typedEvidence.known());
    }

    /**
     * Detail suffix of the cloud-fallback outcome messages: {@code " (path=<path>)"}, plus
     * {@code " rail=<tag>"} when an actuation rail is known. Never a number (the 60 s
     * {@code recordAction} dedup matches on the exact message; ages belong in the daemon log).
     */
    public static String cloudFallbackDetail(String path, DiLink5LockRail rail) {
        if (rail == null) return " (path=" + path + ")";
        return " (path=" + path + " rail=" + rail.tag() + ")";
    }

    /**
     * Product-owner bound (17 Oct): ONE decision — local leg, first local window, cloud leg and a
     * second local window — holds the ACTION monitor for at most this long, measured from the SDK
     * dispatch. The cloud leg gets what is left after the local window that still follows it; the
     * second window is cut to what is left after the cloud leg.
     */
    public static final long DECISION_BUDGET_MS = 30_000L;
    /** A cloud leg with less budget than this is not sent ("decision budget exhausted"). */
    public static final long CLOUD_LEG_MIN_BUDGET_MS = 1_000L;

    /** Time spent since the SDK dispatch; a clock regression counts as none. */
    private static long spentMs(long dispatchedAtElapsedMs, long nowElapsedMs) {
        return Math.max(0L, nowElapsedMs - dispatchedAtElapsedMs);
    }

    /**
     * Wait bound of the ONE cloud leg: the decision budget left at {@code now}, minus
     * {@code reserveMs} for a local window that is still due after the leg (the Di5 immediate rule
     * sends the cloud command BEFORE the rail's pinned window; every other trigger reserves the rail's
     * window for its second readback), never more than the command's own cloud timeout. 0 or less
     * means no cloud leg.
     */
    public static long cloudFallbackCloudTimeoutMs(long dispatchedAtElapsedMs, long nowElapsedMs,
                                                   long reserveMs, long commandCloudTimeoutMs) {
        return Math.max(0L, Math.min(commandCloudTimeoutMs,
                DECISION_BUDGET_MS - spentMs(dispatchedAtElapsedMs, nowElapsedMs) - Math.max(0L, reserveMs)));
    }

    /**
     * Second LOCAL confirmation window after a cloud leg: the rail's own window, cut to what is left
     * of {@link #DECISION_BUDGET_MS} at {@code now}. 0 means no window at all.
     */
    public static long cloudFallbackSecondWindowMs(DiLink5LockRail rail, long dispatchedAtElapsedMs,
                                                   long nowElapsedMs) {
        return Math.max(0L, Math.min(confirmationWindowMs(rail),
                DECISION_BUDGET_MS - spentMs(dispatchedAtElapsedMs, nowElapsedMs)));
    }

    /** Skip reason of the duplicate suppression: the LOCAL before-state already shows the target. */
    public static final String CLOUD_LEG_SKIP_LOCAL_AT_TARGET = "local state already at target";

    // ── S2 (18 Oct): the proximity confirm job (handler's single-use "proximity-confirm" thread) ──

    /** Name of the single-use daemon thread that runs one decision's confirmation off the ACTION monitor. */
    public static final String CONFIRM_JOB_THREAD_NAME = "proximity-confirm";
    /** Recorded for a decision in the same direction as a pending confirmation job (no second dispatch). */
    public static final String CONFIRM_JOB_IN_PROGRESS_TEXT = "Command already in progress";
    /** Recorded right after an accepted SDK leg when {@code confirmWithVehicle} is off (S3). */
    public static final String CONFIRM_OFF_SENT_TEXT = "Command sent; vehicle confirmation off";

    /**
     * Status of the ONE terminal record the confirm job writes when its sequence was cut short — by
     * the opposite direction superseding it ({@code supersededBy != null}) or by an unexpected
     * failure — and no terminal outcome had been recorded yet. A cloud command that the car's cloud
     * already acknowledged stays {@code cloud_acknowledged} (never "confirmed" without a LOCAL
     * readback); an accepted local leg that was still being confirmed is {@code unconfirmed}; a
     * refused local leg whose cloud leg did not go out is {@code failed}.
     */
    public static String interruptedConfirmStatus(boolean sdkAccepted, boolean cloudAcknowledged) {
        if (cloudAcknowledged) return "cloud_acknowledged";
        return sdkAccepted ? "unconfirmed" : "failed";
    }

    /** Text of that record; {@code supersededBy} is the newer decision's action, null for a failure. */
    public static String interruptedConfirmText(boolean sdkAccepted, boolean cloudAcknowledged,
                                                String supersededBy, String sdkFailureText) {
        if (supersededBy == null) return "Local action interrupted or unavailable";
        if (cloudAcknowledged)
            return "Cloud command acknowledged; local confirmation stopped by a newer " + supersededBy + " decision";
        if (sdkAccepted) return "Command accepted; confirmation stopped by a newer " + supersededBy + " decision";
        return "Local command failed (" + sdkFailureText + "); cloud command abandoned for a newer "
                + supersededBy + " decision";
    }

    /** The job's one daemon-log line; numbers belong here, never in a recordAction message. */
    public static String confirmJobLogLine(String action, String outcome, String path, long elapsedMs,
                                           String supersededBy) {
        return "Proximity: confirm action=" + action + " outcome=" + (outcome == null ? "none" : outcome)
                + " path=" + (path == null ? PATH_SDK : path) + " ms=" + Math.max(0L, elapsedMs)
                + (supersededBy == null ? "" : " supersededBy=" + supersededBy);
    }

    /**
     * The cloud leg's re-validation verdict (product decision 17 Oct): {@code null} = send, else the
     * skip reason that ends up in the recorded outcome. Exactly these checks and nothing more — the
     * 5 s observation lease is deliberately NOT re-applied: it proved the observation fresh for the
     * FIRST (local) dispatch, and the cloud leg continues that same decision. In order: master on at
     * the same settings revision; the {@code cloudFallback} switch still on; Learning mode off; the
     * decision not revoked by a newer signal (the sources' revocation-only variants
     * {@code BluetoothProximityDecisionGuard.allowsIgnoringObservationAge},
     * {@code KeyFobEventEvaluator.decisionStillCurrent}, {@code KeyFobPresenceEvaluator.decisionStillCurrent}
     * behind {@code ActionDecision.current()}); the vehicle still off; for a LOCK the six closures still
     * closed; the LOCAL before-state not already at the target (duplicate suppression); at least
     * {@link #CLOUD_LEG_MIN_BUDGET_MS} of the decision budget left for the leg. Pure: the handler
     * gathers every input from the same local reads the gate made; cloud state is never an input.
     *
     * @param doors {@code BydDataCollector.readAllDoorOpenStates()} read for a lock (null when that
     *              read failed); ignored for an unlock
     */
    public static String cloudLegSkipReason(boolean settingsCurrent, boolean switchOn, boolean dryRun,
            boolean decisionCurrent, String revokedReason, int powerLevel, boolean lock, int[] doors,
            int localLockState, int target, long cloudTimeoutMs) {
        if (!settingsCurrent) return "settings changed";
        if (!switchOn) return "cloud fallback switched off";
        if (dryRun) return "Learning mode";
        if (!decisionCurrent) return revokedReason == null ? "decision revoked" : revokedReason;
        if (powerLevel != 0) return "vehicle no longer off";
        if (lock) {
            if (doors == null || doors.length < 6) return "door and lid state unavailable";
            String block = doorBlockReason(doors);
            if (block != null) return block;
        }
        if (localLockState == target) return CLOUD_LEG_SKIP_LOCAL_AT_TARGET;
        if (cloudTimeoutMs < CLOUD_LEG_MIN_BUDGET_MS) return "decision budget exhausted";
        return null;
    }

    /**
     * Short text of a cloud leg's result for the outcome messages, keyed by the router outcome name
     * ({@code VehicleCommandRouter.Outcome}). {@code cloudOnlineState} is the BYD cloud's reported
     * {@code onlineState} ({@code online|offline|unknown}); it only labels an unreachable car and
     * never gates or confirms anything.
     */
    public static String cloudLegSummary(String outcomeName, String displayMessage, boolean timedOut,
                                         String cloudOnlineState) {
        String message = displayMessage == null ? "" : displayMessage;
        if ("AUTH_REQUIRED".equals(outcomeName)) return "cloud not configured";
        if ("NOT_SUPPORTED".equals(outcomeName)) return "cloud leg not available for this command";
        if ("BLOCKED_DRIVING".equals(outcomeName)) return "blocked: vehicle not confirmed parked";
        if ("RATE_LIMITED".equals(outcomeName)) return "rate limited: " + message;
        if ("SUCCESS".equals(outcomeName)) return "acknowledged";
        if (timedOut) return "timeout";
        if ("offline".equals(cloudOnlineState)) return "car offline in BYD cloud";
        return "unreachable: " + message;
    }

    /** Closure names by index of {@code BydDataCollector.readAllDoorOpenStates()} (RHD-normalised). */
    private static final String[] DOOR_NAMES = {
            "front-left door", "front-right door", "rear-left door", "rear-right door", "hood", "trunk"};

    /**
     * P3: the closure block names what blocks. {@code doors} is
     * {@code BydDataCollector.readAllDoorOpenStates()} ({@code [lf, rf, lr, rr, hood, trunk, fuelCap]};
     * each 1=open, 0=closed, -1=unknown; at least six entries, already RHD-normalised by the
     * collector). Same predicate as the old loop: any of the first six entries {@code != 0} blocks;
     * index 6 (fuel cap) is ignored exactly as before. Returns {@code null} when all six are closed,
     * else {@code "Close all doors, hood and trunk: <name> open|unknown, …"} in index order (1 = open,
     * anything else = unknown).
     */
    public static String doorBlockReason(int[] doors) {
        java.util.List<String> items = new java.util.ArrayList<>();
        for (int i = 0; i < DOOR_NAMES.length; i++) {
            if (doors[i] == 0) continue;
            items.add(DOOR_NAMES[i] + (doors[i] == 1 ? " open" : " unknown"));
        }
        if (items.isEmpty()) return null;
        return "Close all doors, hood and trunk: " + String.join(", ", items);
    }

    // ── P5 (17 Oct): honest dashboard Lock/Unlock — the verdict table of VehicleControlApiHandler ──

    /** Response {@code path} labels of the dashboard Lock/Unlock (the router's {@code pathString()} words). */
    public static final String MANUAL_PATH_LOCAL = "local";
    public static final String MANUAL_PATH_LOCAL_THEN_CLOUD = "local-then-cloud";
    /** Confirmation suffixes appended to the i18n base text (J11: no new i18n keys; English). */
    public static final String MANUAL_SUFFIX_CONFIRMED = " · confirmed by the car";
    public static final String MANUAL_SUFFIX_UNCONFIRMED = " · not confirmed by the car";
    public static final String MANUAL_SUFFIX_CLOUD_ACKNOWLEDGED = " · cloud acknowledged; not confirmed by the car";

    /**
     * One row of the dashboard Lock/Unlock verdict table. {@code success} is true ONLY when a LOCAL
     * readback confirmed the target; a cloud acknowledgement alone is {@code cloud_acknowledged}.
     * A pass-through row ({@link #passThrough()}) reports the router result of the leg that produced
     * it exactly as today ({@code rate_limited}, {@code blocked_driving}): {@code path} and
     * {@code baseKey} are null and the handler takes path and message from that result.
     */
    public static final class DoorLockVerdict {
        /** {@code success | unconfirmed | cloud_acknowledged | failed}, or the pass-through router outcome in lower case. */
        public final String outcome;
        /** {@link #MANUAL_PATH_LOCAL} | {@link #MANUAL_PATH_LOCAL_THEN_CLOUD}; null for a pass-through row. */
        public final String path;
        /**
         * Base message key under {@code vehicle_control.} ({@code local_sent}, {@code local_failed},
         * {@code cloud_failed}, {@code local_unavailable_used_cloud}, {@code cloud_sent}); null for a
         * pass-through row.
         */
        public final String baseKey;
        /** English suffix appended to the base text ({@code " · …"}); {@code ""} for none. */
        public final String suffix;
        /** True ONLY when a LOCAL readback confirmed the target (never from rc=0 / router SUCCESS). */
        public final boolean success;

        DoorLockVerdict(String outcome, String path, String baseKey, String suffix, boolean success) {
            this.outcome = outcome;
            this.path = path;
            this.baseKey = baseKey;
            this.suffix = suffix == null ? "" : suffix;
            this.success = success;
        }

        static DoorLockVerdict passThrough(String routerOutcomeName) {
            return new DoorLockVerdict(routerOutcomeName.toLowerCase(java.util.Locale.ROOT), null, null, "", false);
        }

        /** The handler reports the producing leg's router result as today (path, message, latency). */
        public boolean passThrough() { return baseKey == null; }

        /** Response field {@code confirmation}: {@code local} when a local readback confirmed, else {@code none}. */
        public String confirmation() { return success ? "local" : "none"; }
    }

    /** Router outcomes the dashboard reports exactly as today (no readback can add anything to them). */
    private static boolean manualPassThrough(String outcomeName) {
        return "RATE_LIMITED".equals(outcomeName) || "BLOCKED_DRIVING".equals(outcomeName);
    }

    /**
     * P5 verdict (plan §2.8), pure. Inputs are the router outcome names ({@code VehicleCommandRouter.Outcome})
     * and the LOCAL confirmations of the two windows; {@code cloudOutcomeName == null} = the cloud leg
     * was not run, {@code secondLocal == null} = no second window. Rows:
     * <ul>
     * <li>SDK leg not SUCCESS and {@code RATE_LIMITED|BLOCKED_DRIVING}: pass through.</li>
     * <li>SDK SUCCESS + first window CONFIRMED → {@code success}, {@code local}, "confirmed by the car".</li>
     * <li>SDK SUCCESS + UNCONFIRMED, cloud leg not run (switch off) → {@code unconfirmed}, {@code local}.</li>
     * <li>… + cloud SUCCESS + second CONFIRMED → {@code success}, {@code local-then-cloud}.</li>
     * <li>… + cloud SUCCESS + second UNCONFIRMED → {@code cloud_acknowledged} (never success).</li>
     * <li>… + cloud AUTH_REQUIRED / NOT_SUPPORTED (nothing was sent) → {@code unconfirmed}, {@code local},
     *     "not confirmed by the car; cloud not configured" (resp. "cloud leg not available for this command").</li>
     * <li>… + cloud RATE_LIMITED / BLOCKED_DRIVING → pass through (today's outcome and message).</li>
     * <li>… + cloud FAILED / VEHICLE_UNREACHABLE → {@code failed}, {@code local-then-cloud}, base
     *     {@code cloud_failed}; the suffix adds "timeout" or "car offline in BYD cloud" when known.</li>
     * <li>SDK FAILED (or NOT_SUPPORTED) + cloud AUTH_REQUIRED / NOT_SUPPORTED / not run → {@code failed},
     *     {@code local}, base {@code local_failed} (today's text).</li>
     * <li>SDK FAILED + cloud SUCCESS + CONFIRMED → {@code success}; + UNCONFIRMED → {@code cloud_acknowledged}.</li>
     * <li>SDK FAILED + cloud FAILED → {@code failed}, {@code local-then-cloud}, base {@code cloud_failed}.</li>
     * </ul>
     * {@code cloudOnlineState} is the BYD cloud's reported {@code onlineState}; it only labels, never gates.
     */
    public static DoorLockVerdict manualDoorLockVerdict(String sdkOutcomeName, Confirmation firstLocal,
            boolean cloudFallbackOn, String cloudOutcomeName, Confirmation secondLocal,
            boolean cloudTimedOut, String cloudOnlineState) {
        boolean sdkAccepted = "SUCCESS".equals(sdkOutcomeName);
        // A LOCAL readback at the target is the only success, and it wins over every label.
        if (sdkAccepted && firstLocal == Confirmation.CONFIRMED) {
            return new DoorLockVerdict("success", MANUAL_PATH_LOCAL, "local_sent", MANUAL_SUFFIX_CONFIRMED, true);
        }
        if ("SUCCESS".equals(cloudOutcomeName)) {
            if (secondLocal == Confirmation.CONFIRMED) {
                return new DoorLockVerdict("success", MANUAL_PATH_LOCAL_THEN_CLOUD, "local_unavailable_used_cloud",
                        MANUAL_SUFFIX_CONFIRMED, true);
            }
            return new DoorLockVerdict("cloud_acknowledged", MANUAL_PATH_LOCAL_THEN_CLOUD, "cloud_sent",
                    MANUAL_SUFFIX_CLOUD_ACKNOWLEDGED, false);
        }
        if (!sdkAccepted && manualPassThrough(sdkOutcomeName)) return DoorLockVerdict.passThrough(sdkOutcomeName);
        if (cloudOutcomeName == null) {
            // Cloud leg not run: the switch is off (the only reason after an accepted leg), or nothing
            // more could be done after a refused one.
            if (!sdkAccepted) return new DoorLockVerdict("failed", MANUAL_PATH_LOCAL, "local_failed", "", false);
            return new DoorLockVerdict("unconfirmed", MANUAL_PATH_LOCAL, "local_sent",
                    cloudFallbackOn ? MANUAL_SUFFIX_UNCONFIRMED + "; cloud command not sent" : MANUAL_SUFFIX_UNCONFIRMED,
                    false);
        }
        if ("AUTH_REQUIRED".equals(cloudOutcomeName) || "NOT_SUPPORTED".equals(cloudOutcomeName)) {
            // Nothing was sent (J7: a skipped leg, not a failure).
            if (!sdkAccepted) return new DoorLockVerdict("failed", MANUAL_PATH_LOCAL, "local_failed", "", false);
            return new DoorLockVerdict("unconfirmed", MANUAL_PATH_LOCAL, "local_sent",
                    MANUAL_SUFFIX_UNCONFIRMED + "; " + cloudLegSummary(cloudOutcomeName, null, false, null), false);
        }
        if (manualPassThrough(cloudOutcomeName)) return DoorLockVerdict.passThrough(cloudOutcomeName);
        // FAILED / VEHICLE_UNREACHABLE / anything else: the cloud command went out and failed. The base
        // text already says the car could not be reached; only a timeout or the cloud's own "offline"
        // label add information (the generic "unreachable: <message>" would repeat the base text).
        String summary = cloudLegSummary(cloudOutcomeName, null, cloudTimedOut, cloudOnlineState);
        String detail = summary.startsWith("unreachable: ") ? "" : summary;
        String suffix = sdkAccepted ? MANUAL_SUFFIX_UNCONFIRMED + (detail.isEmpty() ? "" : "; " + detail)
                : detail.isEmpty() ? "" : " · " + detail;
        return new DoorLockVerdict("failed", MANUAL_PATH_LOCAL_THEN_CLOUD, "cloud_failed", suffix, false);
    }
}
