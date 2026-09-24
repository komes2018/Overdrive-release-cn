package com.overdrive.app.byd

import com.overdrive.app.monitor.ChargingStateData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSvcTelemetryTest {
    @Test
    fun parsesOnlyRequestedProperties() {
        val text = """
            lastEvent:Property:0x21406006, int32Values: [73]
            lastEvent:Property:0x21403a0a, int32Values: [4]
            lastEvent:Property:0x21604420, floatValues: [62.8]
        """.trimIndent()

        assertEquals(73, CarSvcTelemetry.findInText(text, 0x21406006)?.toInt())
        assertEquals(4, CarSvcTelemetry.parseGearFromText(text))
        assertEquals(62.8, CarSvcTelemetry.parseSocFromText(text), 0.001)
        assertNull(CarSvcTelemetry.findInText(text, 0x2140461e))
    }

    @Test
    fun shiftModeZeroIsPark() {
        val text = "lastEvent:Property:0x21406407, int32Values: [0]"
        assertEquals(1, CarSvcTelemetry.parseGearFromText(text))
    }

    @Test
    fun powerModeLineComesOnlyFromTheCurrentRowOfThePowerMuteSection() {
        val dump = """
            some unrelated line
            Power Mute State :
              previous : 2=PowerMode StartUp
              current : 4=PowerMode Standby
        """.trimIndent()
        assertEquals(
            "current : 4=PowerMode Standby",
            CarSvcTelemetry.parsePowerModeLineFromText(dump)
        )

        // Some builds print the value on the section header itself.
        val headerInline = "Power Mute State : 10=PowerMode DisPlay on"
        assertEquals(
            headerInline,
            CarSvcTelemetry.parsePowerModeLineFromText(headerInline)
        )

        // No section, no line — indeterminate, never a fabricated OFF.
        assertNull(CarSvcTelemetry.parsePowerModeLineFromText(null))
        assertNull(CarSvcTelemetry.parsePowerModeLineFromText("no section here"))
        // A "current" row beyond the section scan window is not trusted.
        assertNull(
            CarSvcTelemetry.parsePowerModeLineFromText(
                "Power Mute State :\n a\n b\n c\n d\n current : 4=PowerMode Standby"
            )
        )
    }

    @Test
    fun acceptsUpstreamStatusValuesAndFindsNamedOdometerIds() {
        val text = """
            Property:0x21601234, Property name:STATISTIC_TOTAL_MILEAGE
            lastEvent:Property:0x21601234,status: 0,floatValues: [54321.0]
            lastEvent:Property:0x21406006,status: 1,int32Values: [88]
        """.trimIndent()

        assertEquals(88, CarSvcTelemetry.findInText(text, 0x21406006)?.toInt())
        assertEquals(0x21601234,
            CarSvcTelemetry.findPropertyIdByName(text, "STATISTIC_TOTAL_MILEAGE"))
        assertEquals(54321, CarSvcTelemetry.parseTotalMileageFromText(text))
    }

    @Test
    fun chargingRequiresActiveStatePhysicalGunAndNoDrivingEvidence() {
        val charging = CarSvcTelemetry.parseChargingObservation(
            """
                lastEvent:Property:0x21403407,status: 0,int32Values: [1]
                lastEvent:Property:0x2140461c,status: 0,int32Values: [2]
                lastEvent:Property:0x21603408,status: 0,floatValues: [7.2]
                lastEvent:Property:0x21406407,status: 0,int32Values: [1]
            """.trimIndent()
        )
        assertTrue(charging.charging)
        assertEquals(2, charging.gunState)
        assertEquals(1, charging.bmsState)
        assertEquals(7.2, charging.powerKw, 0.001)

        val regen = CarSvcTelemetry.parseChargingObservation(
            """
                lastEvent:Property:0x21403407,status: 0,int32Values: [0]
                lastEvent:Property:0x2140461c,status: 0,int32Values: [2]
                lastEvent:Property:0x21406407,status: 0,int32Values: [4]
                lastEvent:Property:0x21406006,status: 0,int32Values: [45]
            """.trimIndent()
        )
        assertFalse(regen.charging)
        assertEquals(1, regen.gunState)
        assertTrue(regen.powerKw.isNaN())

        val stoppedInDrive = CarSvcTelemetry.parseChargingObservation(
            """
                lastEvent:Property:0x21403407,status: 0,int32Values: [1]
                lastEvent:Property:0x2140461c,status: 0,int32Values: [2]
                lastEvent:Property:0x21406407,status: 0,int32Values: [4]
                lastEvent:Property:0x21406006,status: 0,int32Values: [0]
            """.trimIndent()
        )
        assertFalse(stoppedInDrive.charging)
        assertEquals(
            ChargingStateData.CHARGING_BATTERY_STATE_IDLE,
            stoppedInDrive.bmsState
        )

        val missingGun = CarSvcTelemetry.parseChargingObservation(
            """
                lastEvent:Property:0x2140461c,status: 0,int32Values: [2]
                lastEvent:Property:0x21406407,status: 0,int32Values: [1]
            """.trimIndent()
        )
        assertFalse(missingGun.charging)
        assertEquals(BydVehicleData.UNAVAILABLE, missingGun.bmsState)
    }

    @Test
    fun liveFallbacksOverrideHeldPrimaryValues() {
        CarSvcTelemetry.clearForTest()
        val builder = BydVehicleData.Builder()
        CarSvcTelemetry.applyTextTo(
            builder,
            """
                lastEvent:Property:0x21406006,status: 0,int32Values: [88]
                lastEvent:Property:0x2140461e,status: 0,floatValues: [12.1]
            """.trimIndent()
        )
        CarSvcTelemetry.applyTextTo(
            builder,
            """
                lastEvent:Property:0x21604601,status: 0,floatValues: [23.6]
                lastEvent:Property:0x2140460c,status: 0,floatValues: [13.4]
            """.trimIndent()
        )

        val data = builder.build()
        assertEquals(24.0, data.speedKmh, 0.001)
        assertEquals(13.4, data.voltage12v, 0.001)
    }

    @Test
    fun liveDynamicsNeverTurnHeldValuesIntoFreshObservations() {
        CarSvcTelemetry.clearForTest()
        CarSvcTelemetry.applyTextTo(
            BydVehicleData.Builder(),
            """
                lastEvent:Property:0x21406006,status: 0,int32Values: [88]
                lastEvent:Property:0x21400d00,status: 0,int32Values: [41]
                lastEvent:Property:0x21400d01,status: 0,int32Values: [17]
                lastEvent:Property:0x21406407,status: 0,int32Values: [4]
            """.trimIndent()
        )

        assertEquals(-1, CarSvcTelemetry.currentResolvedSpeedKmh(null))
        assertEquals(-1, CarSvcTelemetry.currentAccelPercent(null))
        assertEquals(-1, CarSvcTelemetry.currentBrakePercent(null))
        assertEquals(-1, CarSvcTelemetry.parseGearFromText(null))
    }

    @Test
    fun dynamicObservationKeepsTheSourceAge() {
        val observation = CarSvcTelemetry.dynamicObservation(
            """
                lastEvent:Property:0x21406006,status: 0,int32Values: [42]
                lastEvent:Property:0x21400d00,status: 0,int32Values: [17]
                lastEvent:Property:0x21400d01,status: 0,int32Values: [9]
                lastEvent:Property:0x21406407,status: 0,int32Values: [2]
            """.trimIndent(),
            1_234L
        )

        assertNotNull(observation)
        assertEquals(42, observation!!.speedKmh)
        assertEquals(17, observation.accelPercent)
        assertEquals(9, observation.brakePercent)
        assertEquals(2, observation.gear)
        assertEquals(1_234L, observation.ageMs)
    }

    @Test
    fun turnAndPartialArraysMergeWithoutLosingSdkValues() {
        CarSvcTelemetry.clearForTest()
        val builder = BydVehicleData.Builder()
            .leftTurnState(1)
            .rightTurnState(1)
            .hazard(true)
            .lightKnownMask(BydVehicleData.LIGHT_KNOWN_NONE)
            .doorLockStatus(intArrayOf(2, 2, 2, 2, 1, 1, 2))
            .tyrePressure(intArrayOf(240, 241, 242, 243))
        CarSvcTelemetry.applyTextTo(
            builder,
            """
                lastEvent:Property:0x21404716,status: 0,int32Values: [0]
                lastEvent:Property:0x2140506e,status: 0,int32Values: [1]
                lastEvent:Property:0x2160801d,status: 0,floatValues: [456]
            """.trimIndent()
        )

        val data = builder.build()
        assertEquals(0, data.leftTurnState)
        assertEquals(0, data.rightTurnState)
        assertFalse(data.hazard)
        assertTrue(data.isLightKnown(BydVehicleData.LIGHT_KNOWN_TURN_HAZARD))
        assertEquals(1, data.doorLockStatus[0])
        assertEquals(1, data.doorLockStatus[4])
        assertEquals(314, data.tyrePressure[0])
        assertEquals(241, data.tyrePressure[1])
    }
}
