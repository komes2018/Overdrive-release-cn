package com.overdrive.app.remoteaccess;

/** Applies retry cooldown without suppressing a fresh return or a manual new lock cycle. */
public final class ProximityCooldownPolicy {
    private ProximityCooldownPolicy() {}

    public static boolean blocks(String action, String lastAction, long now, long lastAttempt,
            long cooldownMs, boolean lastCommandConfirmed, int currentDriverLock) {
        if (!valid(action) || now < 0 || cooldownMs <= 0 || lastAttempt < -1) return true;
        if (lastAttempt == -1) return false;
        if (now < lastAttempt) return true;
        if (now - lastAttempt >= cooldownMs) return false;
        if (!valid(lastAction)) return true;
        if (!action.equals(lastAction)) return false;
        int opposite = "unlock".equals(action) ? 2 : 1;
        return !lastCommandConfirmed || currentDriverLock != opposite;
    }

    private static boolean valid(String action) {
        return "lock".equals(action) || "unlock".equals(action);
    }
}
