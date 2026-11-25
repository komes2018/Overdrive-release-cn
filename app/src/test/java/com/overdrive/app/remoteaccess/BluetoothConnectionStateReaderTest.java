package com.overdrive.app.remoteaccess;

import com.overdrive.app.camera.dilink5.DiLink5Platform;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual reflective native calls, with controlled binder/radio/mode boundaries. */
public class BluetoothConnectionStateReaderTest {
    private static final String ADDRESS = "00:11:22:33:44:55";
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";

    public static final class Device {
        final String address;
        Device(String address) { this.address = address; }
    }

    public interface Android29Bluetooth {
        Device[] getBondedDevices();
        int getConnectionState(Device peer);
    }

    public interface Di5Android30Bluetooth {
        Device[] getBondedDevices();
        int getConnectionState(Device peer);
    }

    public static final class NativeBluetooth implements Android29Bluetooth, Di5Android30Bluetooth {
        Device[] inventory = {new Device(ADDRESS)};
        int state, queries, inventoryReads;
        boolean failInventory, failConnection;
        Runnable onQuery = () -> {}, onInventory = () -> {};

        @Override public Device[] getBondedDevices() {
            inventoryReads++;
            onInventory.run();
            if (failInventory) throw new IllegalStateException("fixture_inventory_failure");
            return inventory;
        }

        @Override public int getConnectionState(Device peer) {
            assertEquals(ADDRESS, peer.address);
            queries++;
            onQuery.run();
            if (failConnection) throw new IllegalStateException("fixture_binder_failure");
            return state;
        }
    }

    public interface BoxedBluetooth {
        Device[] getBondedDevices();
        Integer getConnectionState(Device peer);
    }

    public static final class NullResultBluetooth implements BoxedBluetooth {
        int queries;
        @Override public Device[] getBondedDevices() { return new Device[]{new Device(ADDRESS)}; }
        @Override public Integer getConnectionState(Device peer) { queries++; return null; }
    }

    public interface Android32Bluetooth {
        Device[] getBondedDevices(BluetoothNativeCallTest.Source source);
        int getConnectionState(Device peer);
    }

    public interface Android34Bluetooth {
        void getBondedDevices(BluetoothNativeCallTest.Source source,
                BluetoothNativeCallTest.SynchronousResultReceiver<Device[]> receiver);
        void getConnectionState(Device peer, BluetoothNativeCallTest.Source source,
                BluetoothNativeCallTest.SynchronousResultReceiver<Integer> receiver);
    }

    public static final class ModernDirect implements Android32Bluetooth {
        final Environment environment;
        ModernDirect(Environment environment) { this.environment = environment; }
        @Override public Device[] getBondedDevices(BluetoothNativeCallTest.Source source) {
            assertSame(environment.source, source);
            return environment.nativeService.getBondedDevices();
        }
        @Override public int getConnectionState(Device peer) {
            return environment.nativeService.getConnectionState(peer);
        }
    }

    public static final class ModernReceiver implements Android34Bluetooth {
        final Environment environment;
        boolean reply = true;
        RuntimeException error;
        ModernReceiver(Environment environment) { this.environment = environment; }
        @Override public void getBondedDevices(BluetoothNativeCallTest.Source source,
                BluetoothNativeCallTest.SynchronousResultReceiver<Device[]> receiver) {
            assertSame(environment.source, source);
            receiver.send(environment.nativeService.getBondedDevices());
        }
        @Override public void getConnectionState(Device peer, BluetoothNativeCallTest.Source source,
                BluetoothNativeCallTest.SynchronousResultReceiver<Integer> receiver) {
            assertSame(environment.source, source);
            if (error != null) receiver.fail(error);
            else if (reply) receiver.send(environment.nativeService.getConnectionState(peer));
        }
    }

    private static final class Environment implements BluetoothConnectionStateReader.Environment {
        int sdk = 29, radio = 12, radioReads, modeReads;
        boolean authorized = true, alive = true, failRadio;
        Object adapter = new Object();
        Object service;
        Class<?> serviceType;
        DiLink5Platform.ActiveModeSnapshot mode = mode("default", A);
        long now = 1000;
        final NativeBluetooth nativeService = new NativeBluetooth();
        Object source = new BluetoothNativeCallTest.Source();
        Class<?> receiverType;

        Environment() { service = nativeService; }
        @Override public boolean authorized() { return authorized; }
        @Override public int sdk() { return sdk; }
        @Override public DiLink5Platform.ActiveModeSnapshot committedMode() {
            modeReads++;
            return mode;
        }
        @Override public Object adapterBinding() { return adapter; }
        @Override public int radioState() {
            radioReads++;
            if (failRadio) throw new IllegalStateException("fixture_radio_failure");
            return radio;
        }
        @Override public Object service() { return service; }
        @Override public boolean serviceAlive(Object current) { return alive; }
        @Override public Class<?> serviceType() {
            return serviceType != null ? serviceType
                    : sdk == 29 ? Android29Bluetooth.class : Di5Android30Bluetooth.class;
        }
        @Override public Class<?> deviceType() { return Device.class; }
        @Override public String deviceAddress(Object value) { return ((Device) value).address; }
        @Override public long elapsedRealtime() { return now; }
        @Override public Object attributionSource() { return source; }
        @Override public Class<?> attributionType() { return BluetoothNativeCallTest.Source.class; }
        @Override public Class<?> resultReceiverType() { return receiverType; }
    }

    private static Environment modern(int sdk) {
        Environment environment = new Environment();
        environment.sdk = sdk;
        if (sdk <= 32) {
            environment.serviceType = Android32Bluetooth.class;
            environment.service = new ModernDirect(environment);
        } else {
            environment.serviceType = Android34Bluetooth.class;
            environment.service = new ModernReceiver(environment);
            environment.receiverType = BluetoothNativeCallTest.SynchronousResultReceiver.class;
        }
        return environment;
    }

    private static DiLink5Platform.ActiveModeSnapshot mode(String name, String generation) {
        try {
            Method parser = DiLink5Platform.class.getDeclaredMethod("parseActiveModeMarker", byte[].class);
            parser.setAccessible(true);
            return (DiLink5Platform.ActiveModeSnapshot) parser.invoke(null,
                    (name + "\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static BluetoothConnectionStateReader.Snapshot read(Environment environment) {
        return BluetoothConnectionStateReader.read(environment, ADDRESS);
    }

    private static void assertUnknown(BluetoothConnectionStateReader.Snapshot proof) {
        assertFalse(proof.connectionReadKnown);
        assertFalse(proof.connected());
        assertEquals("unknown", proof.connectionState);
    }

    @Test public void android29HealthyNativeDisconnectionHasExplicitProof() {
        Environment environment = new Environment();
        BluetoothConnectionStateReader.Snapshot proof = read(environment);
        assertTrue(proof.connectionReadKnown);
        assertEquals("disconnected", proof.connectionState);
        assertTrue(proof.radioReadKnown);
        assertTrue(proof.radioOn);
        assertEquals(1000, proof.connectionObservedAtElapsedMs);
        assertEquals(A, proof.connectionModeGeneration);
        assertEquals(1, environment.nativeService.queries);
        assertEquals(2, environment.nativeService.inventoryReads);
    }

    @Test public void di5Android30UsesTheSameVerifiedDirectNativeSignature() {
        Environment environment = new Environment();
        environment.sdk = 30;
        environment.mode = mode("dilink5", A);
        environment.nativeService.state = 1;
        BluetoothConnectionStateReader.Snapshot proof = read(environment);
        assertTrue(proof.connectionReadKnown);
        assertTrue(proof.connected());
        assertEquals(1, environment.nativeService.queries);
    }

    @Test public void android31And32ReadAttributedInventoryWithoutSelectingDi5() {
        for (int sdk : new int[]{31, 32}) {
            for (int state : new int[]{0, 1, 3}) {
                Environment environment = modern(sdk);
                environment.nativeService.state = state;
                BluetoothConnectionStateReader.Snapshot proof = read(environment);
                assertTrue(proof.connectionReadKnown);
                assertEquals(state != 0, proof.connected());
                assertEquals(A, proof.connectionModeGeneration);
                assertEquals(2, environment.nativeService.inventoryReads);
            }
        }
    }

    @Test public void android33And34UseTheNativeReceiverReplyForConnectionEvidence() {
        for (int sdk : new int[]{33, 34}) {
            for (int state : new int[]{0, 1, 3}) {
                Environment environment = modern(sdk);
                environment.nativeService.state = state;
                BluetoothConnectionStateReader.Snapshot proof = read(environment);
                assertTrue(proof.connectionReadKnown);
                assertEquals(state != 0, proof.connected());
                assertEquals(1, environment.nativeService.queries);
            }
        }
    }

    @Test public void modernTimeoutAndDeliveredPermissionFailureNeverBecomeAbsence() {
        Environment timeout = modern(34);
        ((ModernReceiver) timeout.service).reply = false;
        assertUnknown(read(timeout));
        Environment denied = modern(34);
        ((ModernReceiver) denied.service).error = new SecurityException("permission revoked");
        assertUnknown(read(denied));
    }

    @Test public void modernAttributionIsRequiredBeforeAnyNativeInventoryRead() {
        for (int sdk : new int[]{32, 34}) {
            Environment environment = modern(sdk);
            environment.source = null;
            assertUnknown(read(environment));
            assertEquals(0, environment.nativeService.inventoryReads);
            assertEquals(0, environment.nativeService.queries);
        }
    }

    @Test public void modernEvidenceRetainsRadioModeAdapterServiceAndConsentFences() {
        for (int sdk : new int[]{32, 34}) {
            for (int fence = 0; fence < 5; fence++) {
                Environment environment = modern(sdk);
                final int choice = fence;
                environment.nativeService.onQuery = () -> {
                    switch (choice) {
                        case 0: environment.radio = 10; break;
                        case 1: environment.mode = mode("default", B); break;
                        case 2: environment.adapter = new Object(); break;
                        case 3: environment.alive = false; break;
                        case 4: environment.authorized = false; break;
                    }
                };
                assertUnknown(read(environment));
            }
        }
    }

    @Test public void modernPairingRevocationAndInvalidNativeMasksRetireEvidence() {
        for (int sdk : new int[]{32, 34}) {
            Environment revoked = modern(sdk);
            revoked.nativeService.onQuery = () -> revoked.nativeService.inventory = new Device[0];
            assertUnknown(read(revoked));
            Environment invalid = modern(sdk);
            invalid.nativeService.state = 8;
            assertUnknown(read(invalid));
        }
    }

    @Test public void onlyKnownNativeMaskBitsCanSupplyConnectionEvidence() {
        for (int state = 0; state <= 7; state++) {
            Environment environment = new Environment();
            environment.nativeService.state = state;
            BluetoothConnectionStateReader.Snapshot proof = read(environment);
            assertTrue("mask " + state, proof.connectionReadKnown);
            assertEquals(state == 0 ? "disconnected" : "connected", proof.connectionState);
        }
        for (int state : new int[]{-1, 8, 9, 16, Integer.MAX_VALUE}) {
            Environment environment = new Environment();
            environment.nativeService.state = state;
            assertUnknown(read(environment));
        }
    }

    @Test public void nullAndDeadServiceNeverCollapseIntoDisconnected() {
        Environment environment = new Environment();
        environment.service = null;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
        environment = new Environment();
        environment.alive = false;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
    }

    @Test public void binderExceptionNeverSuppliesAbsence() {
        Environment environment = new Environment();
        environment.nativeService.failConnection = true;
        assertUnknown(read(environment));
        assertEquals(1, environment.nativeService.queries);
    }

    @Test public void nullOrFailingInventoryCannotSupplyAbsence() {
        Environment environment = new Environment();
        environment.nativeService.inventory = null;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
        environment = new Environment();
        environment.nativeService.failInventory = true;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
    }

    @Test public void nullInventoryEntryOrAddressIsNotAHealthyInventory() {
        Environment environment = new Environment();
        environment.nativeService.inventory = new Device[]{null};
        assertUnknown(read(environment));
        environment = new Environment();
        environment.nativeService.inventory = new Device[]{new Device(null)};
        assertUnknown(read(environment));
    }

    @Test public void trustedPeerMustActuallyBeInCurrentBondedInventory() {
        Environment environment = new Environment();
        environment.nativeService.inventory = new Device[]{new Device("AA:BB:CC:DD:EE:FF")};
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
    }

    @Test public void pairingRevocationDuringQueryRetiresTheResult() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.nativeService.inventory = new Device[0];
        assertUnknown(read(environment));
        assertEquals(1, environment.nativeService.queries);
    }

    @Test public void nullableBoxedNativeSignatureIsRejectedBeforeQuery() {
        Environment environment = new Environment();
        NullResultBluetooth nativeService = new NullResultBluetooth();
        environment.service = nativeService;
        environment.serviceType = BoxedBluetooth.class;
        assertUnknown(read(environment));
        assertEquals(0, nativeService.queries);
    }

    @Test public void radioOffTurningAndUnrecognizedStatesNeverQueryNativePeer() {
        for (int state : new int[]{10, 11, 13, -1, 99}) {
            Environment environment = new Environment();
            environment.radio = state;
            BluetoothConnectionStateReader.Snapshot proof = read(environment);
            assertUnknown(proof);
            assertFalse(proof.radioOn);
            assertEquals(0, environment.nativeService.queries);
        }
    }

    @Test public void radioReadFailureNeverSuppliesAbsence() {
        Environment environment = new Environment();
        environment.failRadio = true;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
    }

    @Test public void radioMustRemainExactlyOnAcrossQuery() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.radio = 10;
        BluetoothConnectionStateReader.Snapshot proof = read(environment);
        assertUnknown(proof);
        assertTrue(proof.radioReadKnown);
        assertFalse(proof.radioOn);
    }

    @Test public void committedModeGenerationChangeRetiresOriginalResult() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.mode = mode("default", B);
        BluetoothConnectionStateReader.Snapshot proof = read(environment);
        assertUnknown(proof);
        assertEquals("", proof.connectionModeGeneration);
    }

    @Test public void modeChangeWithSameGenerationAlsoRetiresResult() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.mode = mode("camera2", A);
        assertUnknown(read(environment));
    }

    @Test public void malformedMissingAndUnsupportedModeNeverQueryNativePeer() {
        Environment environment = new Environment();
        environment.mode = null;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
        environment = new Environment();
        environment.sdk = 30;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
        environment = new Environment();
        environment.sdk = 31;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
    }

    @Test public void adapterReplacementRetiresEvenAHealthyNativeResult() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.adapter = new Object();
        assertUnknown(read(environment));
    }

    @Test public void serviceReplacementOrDeathRetiresHealthyNativeResult() {
        Environment replaced = new Environment();
        replaced.nativeService.onQuery = () -> replaced.service = new NativeBluetooth();
        assertUnknown(read(replaced));
        Environment dead = new Environment();
        dead.nativeService.onQuery = () -> dead.alive = false;
        assertUnknown(read(dead));
    }

    @Test public void disabledConsentMakesZeroNativeOrRadioOrModeReads() {
        Environment environment = new Environment();
        environment.authorized = false;
        assertUnknown(read(environment));
        assertEquals(0, environment.nativeService.queries);
        assertEquals(0, environment.nativeService.inventoryReads);
        assertEquals(0, environment.radioReads);
        assertEquals(0, environment.modeReads);
    }

    @Test public void consentRevocationDuringQueryRetiresTheResult() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.authorized = false;
        assertUnknown(read(environment));
    }

    @Test public void proofTimestampIsNativeCompletionAndNotLaterFenceOrPublication() {
        Environment environment = new Environment();
        environment.nativeService.onQuery = () -> environment.now = 1007;
        environment.nativeService.onInventory = () -> {
            if (environment.nativeService.inventoryReads == 2) environment.now = 9999;
        };
        BluetoothConnectionStateReader.Snapshot proof = read(environment);
        assertTrue(proof.connectionReadKnown);
        assertEquals(1007, proof.connectionObservedAtElapsedMs);
        assertEquals(9999, environment.now);
    }

    @Test public void originalModeTupleStillFencesADeferredRssiPublication() {
        BluetoothConnectionStateReader.Snapshot proof = read(new Environment());
        assertTrue(proof.matchesCommittedMode(mode("default", A)));
        assertFalse(proof.matchesCommittedMode(mode("default", B)));
        assertFalse(proof.matchesCommittedMode(mode("camera2", A)));
        assertFalse(proof.matchesCommittedMode(null));
    }

    @Test public void invalidTrustedAddressNeverQueriesNativePeer() {
        Environment environment = new Environment();
        assertUnknown(BluetoothConnectionStateReader.read(environment, "not-a-device"));
        assertEquals(0, environment.nativeService.queries);
    }
}
