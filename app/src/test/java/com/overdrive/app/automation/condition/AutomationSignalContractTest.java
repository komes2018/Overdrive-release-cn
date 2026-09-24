package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.value.BaseValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;

import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Cross-checks the advertised condition catalog against its runtime publishers. */
public class AutomationSignalContractTest {

    /** Android logcat is unavailable in local JVM tests. */
    @BeforeClass
    public static void configureLogger() {
        com.overdrive.app.logging.DaemonLogger.Config config =
                new com.overdrive.app.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(config);
    }

    @Test
    public void everyStaticPublisherKeyParsesThroughItsConditionSchema() throws Exception {
        Conditions conditions = new Conditions();
        Set<String> publishedTypes = new HashSet<>();

        assertEventFieldsParse(conditions, publishedTypes, BydEvent.class);
        assertEventFieldsParse(conditions, publishedTypes, DoorEvent.class);

        // These two are deliberately dynamic keys rather than one static EventData instance.
        publishedTypes.add(BydEvent.VARIABLE_TYPE);
        publishedTypes.add("mqttTrigger");

        Field field = Conditions.class.getDeclaredField("conditions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, EventCondition> registered =
                (Map<String, EventCondition>) field.get(conditions);
        for (String type : registered.keySet()) {
            if (!conditions.isAdvertised(type)) continue;
            assertTrue("Condition is advertised without a publisher: " + type,
                    publishedTypes.contains(type));
        }
    }

    @Test
    public void acPowerAcceptsOnlyTheTwoRealSdkStates() {
        assertEquals("off", BydEvent.acStateToString(0));
        assertEquals("on", BydEvent.acStateToString(1));
        assertNull(BydEvent.acStateToString(2));
        assertNull(BydEvent.acStateToString(255));
        assertNull(BydEvent.acStateToString(BydVehicleData.UNAVAILABLE));
    }

    @Test
    public void steeringHeatUsesTheValidatedLiveOnOffSignal() throws Exception {
        Conditions conditions = new Conditions();
        EventCondition condition = conditions.getCondition("steeringHeat");
        assertNotNull(condition);
        assertEquals(BydEvent.STEERING_HEAT,
                condition.eventData(BydEvent.STEERING_HEAT.toJson()));
        assertEquals(AutomationCategories.CLIMATE,
                AutomationCategories.forId("steeringHeat"));
        assertEquals("steering_wheel_heat", SignalMqttMap.forId("steeringHeat"));

        assertEquals("off", BydEvent.steeringHeatStateToString(1));
        assertEquals("on", BydEvent.steeringHeatStateToString(2));
        assertNull(BydEvent.steeringHeatStateToString(0));
        assertNull(BydEvent.steeringHeatStateToString(BydVehicleData.UNAVAILABLE));

        Field field = BydEvent.class.getDeclaredField("FAST_POLL_OWNED");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<EventData> owned = (Set<EventData>) field.get(null);
        assertTrue(owned.contains(BydEvent.STEERING_HEAT));
    }

    @Test
    public void targetSocIsPublishedAndSurvivesSnapshotCopies() throws Exception {
        Conditions conditions = new Conditions();
        assertNotNull(conditions.getCondition("targetSoc"));
        assertEquals(AutomationCategories.SENSORS,
                AutomationCategories.forId("targetSoc"));

        BydVehicleData original = new BydVehicleData.Builder()
                .socTargetPercent(47)
                .build();
        BydVehicleData copied = original.toBuilder().build();

        assertEquals(47, copied.socTargetPercent);
        assertEquals(47,
                copied.toJson().getJSONObject("battery").getInt("socTargetPercent"));

        Automations.markEditorSeedActive();
        BydEvent.bydEvent(copied);
        assertEquals(47, rawState(BydEvent.TARGET_SOC));
    }

    @Test
    public void bevDoesNotPublishOrAdvertiseHybridOnlyPowertrainState() {
        assertNull(BydEvent.powertrainModeToString(
                com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_HEV, false));
        assertEquals("hev", BydEvent.powertrainModeToString(
                com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_HEV, true));
        assertTrue(AutomationCategories.isHybridOnly("powertrainMode"));
        assertTrue(AutomationCategories.isHybridOnly("powertrain_mode"));
        assertFalse(AutomationCategories.isHybridOnly("driveMode"));

        Automations.update(BydEvent.POWERTRAIN_MODE, "hev", true);
        assertEquals("hev", rawState(BydEvent.POWERTRAIN_MODE));
        Automations.expireState(BydEvent.POWERTRAIN_MODE);
        assertNull(Automations.getStateValue(BydEvent.POWERTRAIN_MODE));
    }

    @Test
    public void undocumentedEmergencyEnumIsParseCompatibleButNotAdvertised() {
        Conditions conditions = new Conditions();
        assertNotNull(conditions.getCondition("emergencyAlarm"));
        assertFalse(conditions.isAdvertised("emergencyAlarm"));
        assertFalse(conditions.toJson().toString().contains("\"id\":\"emergencyAlarm\""));
    }

    @Test
    public void reportedDynamicSignalsUseResponsiveConditionalPolls() {
        assertEquals(250L, DynamicsEvent.POLL_MS);
        assertEquals(250L, TurnSignalEvent.POLL_MS);
        assertEquals(250L, GearEvent.POLL_MS);
        assertEquals(500L, ClimateEvent.POLL_MS);
        assertEquals(500L, EnergyRegenEvent.POLL_MS);
    }

    @Test
    public void speedIsOwnedByTheFastPublisher() throws Exception {
        Field field = BydEvent.class.getDeclaredField("FAST_POLL_OWNED");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<EventData> owned = (Set<EventData>) field.get(null);

        assertTrue(owned.contains(BydEvent.SPEED_KMPH));
        assertTrue(owned.contains(BydEvent.SPEED_MPH));
    }

    @Test
    public void speedPublisherEmitsBothUnitsAndRejectsInvalidSamples() {
        Automations.markEditorSeedActive();
        BydEvent.publishSpeedKmh(100.0);
        assertEquals(100, rawState(BydEvent.SPEED_KMPH));
        assertEquals(62, rawState(BydEvent.SPEED_MPH));

        BydEvent.publishSpeedKmh(Double.NaN);
        BydEvent.publishSpeedKmh(-1.0);
        assertEquals(100, rawState(BydEvent.SPEED_KMPH));
        assertEquals(62, rawState(BydEvent.SPEED_MPH));
    }

    @Test
    public void hazardSampleDoesNotPublishIndividualIndicators() {
        Automations.markEditorSeedActive();

        BydEvent.applyTurnSample(0x1, 10_000L);
        assertEquals("on", rawState(BydEvent.TURN_LEFT));

        BydEvent.applyTurnSample(0x3, 10_250L);
        assertEquals("on", rawState(BydEvent.LIGHTS_HAZARD));
        assertEquals("off", rawState(BydEvent.TURN_LEFT));
        assertEquals("off", rawState(BydEvent.TURN_RIGHT));

        BydEvent.applyTurnSample(0x0, 11_500L);
        assertEquals("off", rawState(BydEvent.LIGHTS_HAZARD));
    }

    @Test
    public void indicatorBlinkOffPhaseIsHeldButCancellationIsPrompt() {
        Automations.markEditorSeedActive();

        BydEvent.applyTurnSample(0x1, 20_000L);
        BydEvent.applyTurnSample(0x0, 20_500L);
        assertEquals("on", rawState(BydEvent.TURN_LEFT));

        BydEvent.applyTurnSample(0x0, 21_001L);
        assertEquals("off", rawState(BydEvent.TURN_LEFT));
    }

    @Test
    public void doorCallbacksSeedDefiniteStateForTheLiveEditor() {
        Automations.markEditorSeedActive();
        DoorEvent.onDoorStateChanged(5, BodyworkConstants.STATE_CLOSED);
        assertEquals("closed", rawState(DoorEvent.DOOR_HOOD));
        assertEquals("closed", rawState(DoorEvent.DOOR_ANY));

        DoorEvent.onDoorStateChanged(5, BodyworkConstants.STATE_OPEN);
        assertEquals("open", rawState(DoorEvent.DOOR_HOOD));
        assertEquals("open", rawState(DoorEvent.DOOR_ANY));

        DoorEvent.onDoorStateChanged(5, 255);
        assertEquals("open", rawState(DoorEvent.DOOR_HOOD));
    }

    private static void assertEventFieldsParse(
            Conditions conditions, Set<String> publishedTypes, Class<?> source) throws Exception {
        for (Field field : source.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !EventData.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            EventData event = (EventData) field.get(null);
            assertNotNull(source.getSimpleName() + "." + field.getName(), event);
            publishedTypes.add(event.getType());

            EventCondition condition = conditions.getCondition(event.getType());
            assertNotNull("Publisher has no condition schema: " + event.getType(), condition);
            assertEquals("Publisher attributes do not match condition schema: " + event.toJson(),
                    event, condition.eventData(event.toJson()));
        }
    }

    private static Object rawState(EventData event) {
        Value value = Automations.getStateValue(event);
        assertNotNull(value);
        return value instanceof BaseValue<?> ? ((BaseValue<?>) value).getValue() : value.toString();
    }
}
