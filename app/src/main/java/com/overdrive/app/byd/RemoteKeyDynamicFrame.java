package com.overdrive.app.byd;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Clean-room builder for the experimental <b>dynamic 73-byte</b> remote-key frame — an
 * alternative to {@link RemoteKeyControl}'s fixed 26-byte frame for firmware that only accepts
 * the longer format. Pure data transform: no I/O, no Android, no vehicle manager.
 *
 * <p>The layout is documented in {@code docs/manager-study/vehicle-controls.md} (row
 * "Local non-CANFD"). The template bytes and the CRC / DES parameters are protocol data; this
 * class is an original implementation of that layout, not a copy of any decompiled method.
 *
 * <p>Frame (0-based offsets, 73 bytes), built from the template then overwritten:
 * <ul>
 *   <li>byte 0 = {@code 0x5E}</li>
 *   <li>bytes 1..16  &larr; {@code seed33[17..32]} (16 bytes)</li>
 *   <li>bytes 23..39 &larr; {@code key17[0..16]} (17 bytes)</li>
 *   <li>bytes 46..49 &larr; {@code epochSeconds}, big-endian</li>
 *   <li>bytes 50..65 &larr; {@code uuid16} iff non-null and exactly 16 bytes, else template</li>
 *   <li>byte 66 = command code</li>
 *   <li>bytes 70..71 = CRC16/CCITT (poly {@code 0x1021}, init {@code 0xFFFF}, no reflection)
 *       over bytes 17..69 inclusive, big-endian — computed <i>before</i> DES</li>
 *   <li>DES/ECB/NoPadding with key {@code seed33[1..8]}, applied in place to the seven 8-byte
 *       blocks at offsets {@code 17, 25, 33, 41, 49, 57, 65} (bytes 17..72). The final block
 *       covers the command byte and the CRC.</li>
 * </ul>
 */
public final class RemoteKeyDynamicFrame {

    static final int FRAME_LENGTH = 73;
    static final int SEED_LENGTH = 33;
    static final int KEY_LENGTH = 17;
    static final int CODE_OFFSET = 66;

    /**
     * Protocol template `r`. Layout documented in docs/manager-study/vehicle-controls.md; the
     * builder overwrites byte 0 ({@code 0x5E}) and the dynamic fields, so the remaining template
     * bytes (17-21 = {@code 27 08 02 14 FE}, 40-41 = {@code 01 03}, byte 72 = {@code 01}, and the
     * default UUID region) are what reach the controller unchanged.
     */
    private static final byte[] TEMPLATE = {
            0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x27, 0x08, 0x02, 0x14, (byte) 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x03, 0x00, 0x00, 0x00, 0x00, 0x67, 0x6E, 0x34, (byte) 0xB3, 0x6F, 0x52, (byte) 0xDE,
            0x4A, (byte) 0xEF, 0x28, 0x4B, (byte) 0xC3, (byte) 0xB3, 0x0A, 0x48, 0x16, 0x13,
            (byte) 0xED, (byte) 0xD6, (byte) 0x82, 0x16, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01
    };

    private static final int[] DES_BLOCK_OFFSETS = {17, 25, 33, 41, 49, 57, 65};
    private static final int CRC_FROM = 17;
    private static final int CRC_TO_INCLUSIVE = 69;
    private static final int CRC_HI_OFFSET = 70;
    private static final int CRC_LO_OFFSET = 71;

    private RemoteKeyDynamicFrame() {}

    /**
     * Build the 73-byte dynamic frame. {@code seed33} and {@code key17} must already be
     * normalized to their exact lengths (see {@link #normalizeBuffer}); the write site refuses
     * missing/short buffers before calling this, so a wrong length here is a programming error.
     *
     * @param seed33        33-byte seed buffer (feature {@code 1034/0x99000005})
     * @param key17         17-byte key buffer (feature {@code 1027/0x9900021a})
     * @param epochSeconds  Unix epoch seconds, stored big-endian at bytes 46..49
     * @param uuid16OrNull  optional 16-byte UUID for bytes 50..65; null/other length keeps template
     * @param code          command code written at byte 66
     * @return the finished 73-byte frame
     */
    public static byte[] build(byte[] seed33, byte[] key17, long epochSeconds,
            byte[] uuid16OrNull, int code) {
        if (seed33 == null || seed33.length != SEED_LENGTH) {
            throw new IllegalArgumentException("seed must be " + SEED_LENGTH + " bytes");
        }
        if (key17 == null || key17.length != KEY_LENGTH) {
            throw new IllegalArgumentException("key must be " + KEY_LENGTH + " bytes");
        }
        byte[] frame = TEMPLATE.clone();
        frame[0] = 0x5E;
        System.arraycopy(seed33, 17, frame, 1, 16);
        System.arraycopy(key17, 0, frame, 23, 17);
        frame[46] = (byte) (epochSeconds >> 24);
        frame[47] = (byte) (epochSeconds >> 16);
        frame[48] = (byte) (epochSeconds >> 8);
        frame[49] = (byte) epochSeconds;
        if (uuid16OrNull != null && uuid16OrNull.length == 16) {
            System.arraycopy(uuid16OrNull, 0, frame, 50, 16);
        }
        frame[CODE_OFFSET] = (byte) code;

        int crc = crc16(frame, CRC_FROM, CRC_TO_INCLUSIVE);
        frame[CRC_HI_OFFSET] = (byte) (crc >> 8);
        frame[CRC_LO_OFFSET] = (byte) crc;

        encryptBlocks(frame, Arrays.copyOfRange(seed33, 1, 9));
        return frame;
    }

    /**
     * Normalize a raw vendor buffer to {@code expectedLength}: an exact-length buffer is returned
     * as-is; a buffer exactly 4 bytes longer has its first 4 bytes stripped; anything else —
     * including null — returns null.
     */
    public static byte[] normalizeBuffer(byte[] raw, int expectedLength) {
        if (raw == null) {
            return null;
        }
        if (raw.length == expectedLength) {
            return raw;
        }
        if (raw.length == expectedLength + 4) {
            return Arrays.copyOfRange(raw, 4, expectedLength + 4);
        }
        return null;
    }

    /** CRC16/CCITT (poly 0x1021, init 0xFFFF, no input/output reflection). */
    static int crc16(byte[] data, int from, int toInclusive) {
        int crc = 0xFFFF;
        for (int i = from; i <= toInclusive; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static void encryptBlocks(byte[] frame, byte[] desKey) {
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(desKey, "DES"));
            for (int offset : DES_BLOCK_OFFSETS) {
                byte[] encrypted = cipher.doFinal(frame, offset, 8);
                System.arraycopy(encrypted, 0, frame, offset, 8);
            }
        } catch (GeneralSecurityException impossible) {
            // DES/ECB/NoPadding on an 8-byte block is always available on Android/JVM.
            throw new IllegalStateException("dynamic frame DES unavailable", impossible);
        }
    }
}
