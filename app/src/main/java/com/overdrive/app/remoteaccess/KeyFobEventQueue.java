package com.overdrive.app.remoteaccess;

import java.util.ArrayDeque;

/**
 * Retains signal changes while the HTTP relay is busy. Repeated frames update
 * the heartbeat but cannot replace a queued Away/Near edge.
 */
final class KeyFobEventQueue {
    static final int CAPACITY = 32;

    static final class Event {
        final long generation, sequence, observedAtMs;
        final int rawMask;

        Event(long generation, long sequence, long observedAtMs, int rawMask) {
            this.generation = generation;
            this.sequence = sequence;
            this.observedAtMs = observedAtMs;
            this.rawMask = rawMask;
        }
    }

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long generation;
    private long sequence;
    private Event latest = new Event(0, 0, -1, -1);
    private boolean overflow;
    private String failure;

    synchronized void reset(long nextGeneration) {
        generation = nextGeneration;
        sequence = 0;
        events.clear();
        overflow = false;
        failure = null;
        latest = new Event(generation, 0, -1, -1);
        notifyAll();
    }

    synchronized boolean offer(long expectedGeneration, int mask, long nowMs) {
        if (generation != expectedGeneration || overflow || failure != null) return false;
        Event next = new Event(generation, ++sequence, nowMs, mask);
        if (latest.sequence == 0 || latest.rawMask != mask) {
            if (events.size() >= CAPACITY) {
                events.clear();
                overflow = true;
                notifyAll();
                return false;
            }
            events.addLast(next);
        }
        latest = next;
        notifyAll();
        return true;
    }

    synchronized boolean fail(long expectedGeneration, String reason) {
        if (generation != expectedGeneration) return false;
        failure = reason;
        notifyAll();
        return true;
    }

    synchronized Event poll() { return events.pollFirst(); }
    synchronized Event latest() { return latest; }
    synchronized boolean hasOverflow() { return overflow; }
    synchronized String failure() { return failure; }
    synchronized void await(long millis) throws InterruptedException {
        if (events.isEmpty() && !overflow && millis > 0) wait(millis);
    }
    synchronized void wake() { notifyAll(); }
}
