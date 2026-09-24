package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HttpServerBodyLimitTest {

    @Test
    public void backupImportsGet32MiBWithoutRaisingOtherEndpoints() {
        int mib = 1024 * 1024;

        assertEquals(32 * mib, HttpServer.maxBodyBytesForRequestLine(
                "POST /api/backup/import/preview HTTP/1.1"));
        assertEquals(32 * mib, HttpServer.maxBodyBytesForRequestLine(
                "POST /api/backup/import?confirm=true HTTP/1.1"));
        assertEquals(16 * mib, HttpServer.maxBodyBytesForRequestLine(
                "POST /api/backup/import-other HTTP/1.1"));
        assertEquals(16 * mib, HttpServer.maxBodyBytesForRequestLine(
                "POST /api/settings/appearance HTTP/1.1"));
        assertEquals(72 * mib, HttpServer.maxBodyBytesForRequestLine(
                "POST /api/audio/library HTTP/1.1"));
    }
}
