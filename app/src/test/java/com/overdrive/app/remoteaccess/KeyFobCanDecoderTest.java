package com.overdrive.app.remoteaccess;

import org.junit.Test;
import static org.junit.Assert.*;

public class KeyFobCanDecoderTest {
    private byte[] frame(int mask) {
        byte[] bytes = new byte[14];
        bytes[2] = 1;
        bytes[3] = (byte) 0x80;
        bytes[7] = (byte) mask;
        return bytes;
    }

    @Test public void verifiedMaskIsPayloadByteOneAfterSixByteHeader() {
        assertEquals(8, KeyFobCanDecoder.decodeMask(frame(0x89)));
        assertEquals(4, KeyFobCanDecoder.decodeMask(frame(0x47)));
        assertEquals(0, KeyFobCanDecoder.decodeMask(frame(0x03)));
        assertEquals(12, KeyFobCanDecoder.decodeMask(frame(0xff)));
    }

    @Test public void rejectsNullTruncatedAndWrongDescriptor() {
        assertEquals(-1, KeyFobCanDecoder.decodeMask(null));
        assertEquals(-1, KeyFobCanDecoder.decodeMask(new byte[5]));
        assertEquals(-1, KeyFobCanDecoder.decodeMask(java.util.Arrays.copyOf(frame(8), 13)));
        byte[] wrong = frame(8);
        wrong[3] = (byte) 0x81;
        assertEquals(-2, KeyFobCanDecoder.decodeMask(wrong));
        wrong = frame(8);
        wrong[4] = 1;
        assertEquals(-2, KeyFobCanDecoder.decodeMask(wrong));
        wrong = frame(8);
        wrong[5] = 1;
        assertEquals(-2, KeyFobCanDecoder.decodeMask(wrong));
    }
}
