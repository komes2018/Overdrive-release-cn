package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class HttpResponseRangeTest {

    @Test
    public void parsesSuffixOpenEndedAndExplicitRanges() {
        long length = 122_564_765L;

        assertArrayEquals(
                new long[]{122_563_741L, 122_564_764L},
                HttpResponse.parseSingleByteRange("bytes=-1024", length));
        assertArrayEquals(
                new long[]{100L, 122_564_764L},
                HttpResponse.parseSingleByteRange("bytes=100-", length));
        assertArrayEquals(
                new long[]{100L, 199L},
                HttpResponse.parseSingleByteRange("bytes=100-199", length));
        assertArrayEquals(
                new long[]{0L, 122_564_764L},
                HttpResponse.parseSingleByteRange("bytes=-999999999", length));
    }

    @Test
    public void rejectsUnsatisfiableRangesWithRequiredContentRange() throws Exception {
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> HttpResponse.parseSingleByteRange("bytes=-0", 100));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> HttpResponse.parseSingleByteRange("bytes=100-", 100));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> HttpResponse.parseSingleByteRange("bytes=9-3", 100));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendRangeNotSatisfiable(out, 100, "Range Not Satisfiable");
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 416 Range Not Satisfiable\r\n"));
        assertTrue(response.contains("\r\nContent-Range: bytes */100\r\n"));
    }
}
