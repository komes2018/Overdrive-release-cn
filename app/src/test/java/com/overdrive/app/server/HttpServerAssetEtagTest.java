package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HttpServerAssetEtagTest {

    @Test
    public void equalLengthCatalogChangesProduceDifferentValidators() {
        byte[] first = "{\"label\":\"one\"}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"label\":\"two\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(first.length, second.length);

        String firstEtag = HttpServer.assetContentEtag(first);
        String secondEtag = HttpServer.assetContentEtag(second);

        assertNotEquals(firstEtag, secondEtag);
        assertEquals(firstEtag, HttpServer.assetContentEtag(first));
        assertTrue(firstEtag.matches("\"[0-9a-f]{64}\""));
    }
}
