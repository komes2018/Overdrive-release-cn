package com.overdrive.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleHttpsServeStatusTest {

    @Test
    fun recognizesStandardHttpsServeRule() {
        val snapshot = TailscaleLauncher.parseHttpsServeSnapshot(
            """
            {
              "TCP": {"443": {"HTTPS": true}},
              "Web": {
                "overdrive.tailnet.ts.net:443": {
                  "Handlers": {
                    "/": {"Proxy": "http://127.0.0.1:8080"},
                    "/health": {"Text": "ok"}
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.OWNED,
            snapshot.ownership
        )
        assertEquals("overdrive.tailnet.ts.net", snapshot.domain)
    }

    @Test
    fun recognizesOnlyTheExactLegacyRuleForMigration() {
        val legacy = TailscaleLauncher.parseHttpsServeSnapshot(
            """
            {
              "TCP": {
                "443": {
                  "TCPForward": "127.0.0.1:8080",
                  "TerminateTLS": "overdrive.tailnet.ts.net",
                  "ProxyProtocol": 1
                }
              }
            }
            """.trimIndent()
        )
        val differentBackend = TailscaleLauncher.parseHttpsServeSnapshot(
            """
            {
              "TCP": {
                "443": {
                  "TCPForward": "127.0.0.1:9090",
                  "TerminateTLS": "overdrive.tailnet.ts.net",
                  "ProxyProtocol": 1
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.LEGACY_OWNED,
            legacy.ownership
        )
        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.CONFLICT,
            differentBackend.ownership
        )
    }

    @Test
    fun refusesUnrelatedOrForegroundPort443Rules() {
        val unrelatedWeb = TailscaleLauncher.parseHttpsServeSnapshot(
            """
            {
              "TCP": {"443": {"HTTPS": true}},
              "Web": {
                "overdrive.tailnet.ts.net:443": {
                  "Handlers": {"/": {"Proxy": "http://127.0.0.1:9090"}}
                }
              }
            }
            """.trimIndent()
        )
        val foreground = TailscaleLauncher.parseHttpsServeSnapshot(
            """
            {
              "Foreground": {
                "session": {
                  "TCP": {"443": {"HTTPS": true}},
                  "Web": {
                    "overdrive.tailnet.ts.net:443": {
                      "Handlers": {"/": {"Proxy": "http://127.0.0.1:8080"}}
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.CONFLICT,
            unrelatedWeb.ownership
        )
        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.CONFLICT,
            foreground.ownership
        )
    }

    @Test
    fun distinguishesFreeFromUnreadableStatus() {
        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.FREE,
            TailscaleLauncher.parseHttpsServeSnapshot("null").ownership
        )
        assertEquals(
            TailscaleLauncher.HttpsServeOwnership.UNKNOWN,
            TailscaleLauncher.parseHttpsServeSnapshot("not-json").ownership
        )
    }
}
