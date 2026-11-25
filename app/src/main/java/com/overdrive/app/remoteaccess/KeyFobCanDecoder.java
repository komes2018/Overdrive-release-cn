package com.overdrive.app.remoteaccess;

/** Exact CAN384/0/0 envelope decoder. This signal is an event, not a distance. */
public final class KeyFobCanDecoder {
    private KeyFobCanDecoder() {}

    /** -2 means unrelated; -1 means a malformed matching frame. */
    public static int decodeMask(byte[] frame) {
        if (frame == null || frame.length < 6) return -1;
        long id = ((long) (frame[0] & 255) << 24)
                | ((long) (frame[1] & 255) << 16)
                | ((long) (frame[2] & 255) << 8) | (frame[3] & 255);
        if (id != 384 || frame[4] != 0 || frame[5] != 0) return -2;
        if (frame.length < 14) return -1;
        return frame[7] & 0x0c;
    }
}
