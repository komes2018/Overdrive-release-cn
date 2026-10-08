package com.overdrive.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import org.junit.Test;

public class WireGuardConfigTest {

    // Throwaway keys, not used anywhere.
    private static final String PRIV = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=";
    private static final String PUB = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String PSK = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=";

    private static final String OPNSENSE = "# exported by OPNsense\n"
            + "[Interface]\n"
            + "PrivateKey = " + PRIV + "\n"
            + "Address = 10.10.10.2/32, fd00:10::2/128\n"
            + "DNS = 192.168.13.1, home.lan\n"
            + "\n"
            + "[Peer]\n"
            + "PublicKey = " + PUB + "\n"
            + "PresharedKey = " + PSK + "\n"
            + "Endpoint = vpn.example.org:51820\n"
            + "AllowedIPs = 10.10.10.0/24, 192.168.13.0/24\n";

    private static void assertRejected(String text, String expectedPart) {
        try {
            WireGuardConfig.parse(text);
            fail("expected a rejection containing: " + expectedPart);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedPart));
        }
    }

    @Test
    public void opnsenseExportWithSearchDomain() {
        WireGuardConfig.Summary s = WireGuardConfig.parse(OPNSENSE);
        assertEquals(Arrays.asList("10.10.10.2/32", "fd00:10::2/128"), s.addresses);
        assertEquals(Arrays.asList("vpn.example.org:51820"), s.endpoints);
        assertEquals(Arrays.asList("10.10.10.0/24", "192.168.13.0/24"), s.routes);
        // The search domain is dropped, the resolver address is kept.
        assertEquals(Arrays.asList("192.168.13.1"), s.dns);
        assertEquals(1, s.peerCount);
    }

    @Test
    public void crlfAndBomAreAccepted() {
        String text = "﻿" + OPNSENSE.replace("\n", "\r\n");
        assertEquals(1, WireGuardConfig.parse(text).peerCount);
    }

    @Test
    public void wgQuickExtrasAreIgnoredAndBareAddressBecomesHostRoute() {
        String text = "[Interface]\n"
                + "PrivateKey = " + PRIV + "\n"
                + "Address = 10.0.0.2\n"
                + "ListenPort = 51820\n"
                + "Table = off\n"
                + "PostUp = iptables -A FORWARD -i %i -j ACCEPT\n"
                + "SaveConfig = true\n"
                + "MTU = 1420\n"
                + "[Peer]\n"
                + "PublicKey = " + PUB + "\n"
                + "Endpoint = [2001:db8::1]:51820\n"
                + "AllowedIPs = 192.168.1.5, 10.1.2.3/8, 2001:db8:0:0:0:0:0:5\n"
                + "PersistentKeepalive = off\n";
        WireGuardConfig.Summary s = WireGuardConfig.parse(text);
        assertEquals(Arrays.asList("10.0.0.2/32"), s.addresses);
        assertEquals(Arrays.asList("[2001:db8::1]:51820"), s.endpoints);
        // Host bits are cleared like the daemon does, IPv6 is shortened.
        assertEquals(Arrays.asList("192.168.1.5/32", "10.0.0.0/8", "2001:db8::5/128"), s.routes);
    }

    @Test
    public void caseInsensitiveSectionsAndKeys() {
        String text = "[interface]\nprivatekey=" + PRIV + "\naddress=10.0.0.2/24\n"
                + "[PEER]\npublickey=" + PUB + "\nendpoint=1.2.3.4:1\nallowedips=0.0.0.0/0\n";
        assertEquals(Arrays.asList("0.0.0.0/0"), WireGuardConfig.parse(text).routes);
    }

    @Test
    public void commentsAreStripped() {
        String text = "; top\n[Interface]\nPrivateKey = " + PRIV + " # my key\nAddress = 10.0.0.2/32\n"
                + "[Peer]\nPublicKey = " + PUB + "\nEndpoint = 1.2.3.4:51820 ; home\nAllowedIPs = 10.0.0.0/24\n";
        assertEquals(Arrays.asList("1.2.3.4:51820"), WireGuardConfig.parse(text).endpoints);
    }

    @Test
    public void twoPeersMergeRoutes() {
        String text = OPNSENSE + "[Peer]\nPublicKey = " + PUB + "\nAllowedIPs = 10.10.10.0/24, 172.16.0.0/12\n";
        WireGuardConfig.Summary s = WireGuardConfig.parse(text);
        assertEquals(2, s.peerCount);
        assertEquals(Arrays.asList("10.10.10.0/24", "192.168.13.0/24", "172.16.0.0/12"), s.routes);
    }

    @Test
    public void missingPrivateKey() {
        assertRejected(OPNSENSE.replace("PrivateKey = " + PRIV, ""), "PrivateKey is missing");
    }

    @Test
    public void badKeyLengthReportsTheLine() {
        assertRejected(OPNSENSE.replace(PRIV, "AAAA"), "line 3: PrivateKey is not a WireGuard key");
        assertRejected(OPNSENSE.replace("PublicKey = " + PUB, "PublicKey = " + PUB.substring(1)),
                "line 8: PublicKey is not a WireGuard key");
    }

    @Test
    public void missingAddress() {
        assertRejected(OPNSENSE.replace("Address = 10.10.10.2/32, fd00:10::2/128\n", ""), "Address is missing");
    }

    @Test
    public void noPeer() {
        String interfaceOnly = OPNSENSE.substring(0, OPNSENSE.indexOf("[Peer]"));
        assertRejected(interfaceOnly, "No [Peer] section");
    }

    @Test
    public void peerWithoutPublicKeyOrAllowedIps() {
        assertRejected(OPNSENSE.replace("PublicKey = " + PUB + "\n", ""), "Peer 1: PublicKey is missing");
        assertRejected(OPNSENSE.replace("AllowedIPs = 10.10.10.0/24, 192.168.13.0/24\n", ""),
                "Peer 1: AllowedIPs is missing");
    }

    @Test
    public void noEndpoint() {
        assertRejected(OPNSENSE.replace("Endpoint = vpn.example.org:51820\n", ""), "No peer has an Endpoint");
    }

    @Test
    public void badEndpoints() {
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org"), "line 10: Endpoint");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:0"), "invalid port");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:70000"), "invalid port");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "2001:db8::1:51820"), "brackets");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", ":51820"), "Endpoint");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "[2001:db8::1]51820"), "Endpoint");
    }

    @Test
    public void unknownKeysAndSections() {
        assertRejected(OPNSENSE.replace("DNS =", "Colour = blue\nDNS ="), "unknown Interface key");
        assertRejected(OPNSENSE.replace("Endpoint =", "Foo = 1\nEndpoint ="), "unknown Peer key");
        assertRejected(OPNSENSE + "[Extra]\n", "unknown section [extra]");
        assertRejected("PrivateKey = " + PRIV + "\n", "outside of a section");
        assertRejected(OPNSENSE + "garbage\n", "expected key = value");
    }

    @Test
    public void badValues() {
        assertRejected(OPNSENSE.replace("10.10.10.2/32", "10.10.10.300/32"), "Address");
        assertRejected(OPNSENSE.replace("10.10.10.2/32", "10.10.10.2/33"), "Address");
        assertRejected(OPNSENSE.replace("Address =", "MTU = 100\nAddress ="), "MTU");
        assertRejected(OPNSENSE.replace("Endpoint =", "PersistentKeepalive = 70000\nEndpoint ="), "PersistentKeepalive");
    }

    @Test
    public void emptyAndOversizeInput() {
        assertRejected("", "empty");
        assertRejected("  \n \r\n", "empty");
        StringBuilder big = new StringBuilder(OPNSENSE);
        while (big.length() <= WireGuardConfig.MAX_INPUT_BYTES) big.append("# padding padding padding\n");
        assertRejected(big.toString(), "too large");
    }

    @Test
    public void jsonNeverContainsKeyMaterial() {
        String json = WireGuardConfig.parse(OPNSENSE).toJson().toString();
        assertFalse(json.contains(PRIV));
        assertFalse(json.contains(PUB));
        assertFalse(json.contains(PSK));
        assertFalse(json.toLowerCase().contains("key"));
        assertTrue(json.contains("vpn.example.org:51820"));
    }

    @Test
    public void errorMessagesNeverEchoKeys() {
        try {
            WireGuardConfig.parse(OPNSENSE.replace(PRIV, PRIV.substring(0, 20)));
            fail();
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains(PRIV.substring(0, 20)));
        }
    }

    @Test
    public void controlCharacterAfterKeyIsRejected() {
        assertRejected(OPNSENSE.replace(PRIV, PRIV + "\u001a"), "line 3: invalid character");
        assertRejected(OPNSENSE.replace("Address =", "Address\u0000 ="), "invalid character");
    }

    @Test
    public void nonAsciiIsRejectedInContentButAllowedInComments() {
        assertRejected(OPNSENSE.replace("home.lan", "h\u00f6me.lan"), "line 5: invalid character");
        String text = OPNSENSE.replace("# exported by OPNsense", "# Auto f\u00fcr Z\u00fcrich \u2603")
                .replace("Address = 10.10.10.2/32, fd00:10::2/128", "Address = 10.10.10.2/32 ; B\u00fcro");
        assertEquals(1, WireGuardConfig.parse(text).peerCount);
    }

    @Test
    public void tabsAreWhitespaceButOtherControlCharsAreNotTrimmed() {
        assertEquals(1, WireGuardConfig.parse(OPNSENSE.replace("Address = ", "Address\t=\t")).peerCount);
        assertRejected(OPNSENSE.replace("[Peer]", "\u001f[Peer]"), "invalid character");
    }

    @Test
    public void numbersMustBeAsciiDigits() {
        // Arabic-indic digits for 1420 are accepted by Integer.parseInt
        assertRejected(OPNSENSE.replace("Address =", "MTU = \u0661\u0664\u0662\u0660\nAddress ="), "invalid character");
        assertRejected(OPNSENSE.replace("Address =", "MTU = +1420\nAddress ="), "MTU");
        assertRejected(OPNSENSE.replace("Address =", "MTU = -1420\nAddress ="), "MTU");
        assertRejected(OPNSENSE.replace("Endpoint =", "PersistentKeepalive = 2\u0665\nEndpoint ="), "invalid character");
        assertRejected(OPNSENSE.replace("Endpoint =", "PersistentKeepalive = -1\nEndpoint ="), "PersistentKeepalive");
        assertEquals(1, WireGuardConfig.parse(
                OPNSENSE.replace("Address =", "MTU = 01420\nAddress =")).peerCount);
    }

    @Test
    public void endpointPortHasNoLeadingZero() {
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:051820"), "invalid port");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:00080"), "invalid port");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:65536"), "invalid port");
        assertEquals(1, WireGuardConfig.parse(
                OPNSENSE.replace("vpn.example.org:51820", "vpn.example.org:65535")).peerCount);
    }

    @Test
    public void bracketsAreForIpv6LiteralsOnly() {
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "[example.com]:1"), "Endpoint");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "[1.2.3.4]:51820"), "Endpoint");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "[2001:db8::1]:051820"), "invalid port");
    }

    @Test
    public void zoneIdentifiersAreRejected() {
        assertRejected(OPNSENSE.replace("fd00:10::2/128", "fe80::2%eth0/128"), "Address");
        assertRejected(OPNSENSE.replace("192.168.13.0/24", "fe80::%eth0/64"), "AllowedIPs");
        assertRejected(OPNSENSE.replace("192.168.13.1,", "fe80::1%eth0,"), "DNS");
        assertRejected(OPNSENSE.replace("vpn.example.org:51820", "[fe80::1%eth0]:51820"), "Endpoint");
    }

    @Test
    public void keyWithoutSeparatorIsNotEchoed() {
        String text = "[Interface]\nPrivateKey " + PRIV + "\nAddress = 10.0.0.2/32\n";
        try {
            WireGuardConfig.parse(text);
            fail();
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(PRIV.substring(0, 25)));
            assertTrue(e.getMessage(), e.getMessage().contains("line 2"));
        }
    }

    @Test
    public void longUnknownKeyNamesAreCapped() {
        String longName = "A" + PRIV.substring(0, 40);
        try {
            WireGuardConfig.parse(OPNSENSE.replace("DNS =", longName + " = 1\nDNS ="));
            fail();
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(longName));
            assertTrue(e.getMessage(), e.getMessage().contains("unknown Interface key"));
        }
    }
}
