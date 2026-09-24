package com.overdrive.app.launcher

import android.view.Display
import com.overdrive.app.surveillance.ClusterProjectionController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5ClusterCastTest {
    @Test
    fun trinketFirmwareRequiresTheOemSharedDisplay() {
        assertTrue(
            requiresSharedDiLink5ProjectionDisplay(
                "trinket",
                "trinket",
                "BYD-AUTO/trinket/trinket:13/build"
            )
        )
        assertTrue(
            requiresSharedDiLink5ProjectionDisplay(
                "D50F_LC", null, null
            )
        )
        assertFalse(
            requiresSharedDiLink5ProjectionDisplay(
                "sa8155p", "byd_ivi", "BYD-AUTO/byd_ivi/release"
            )
        )
    }

    @Test
    fun projectionDisplayPrefersThePhysicallyRoutedFamily() {
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "XDJAScreenProjection_1",
                    "shared_fission_bg_XDJAScreenProjection_0",
                    "shared_fission_bg_XDJAScreenProjection_1"
                ),
                preferFull = false,
                requireShared = true
            )
        )
        assertEquals(
            "shared_fission_bg_XDJAScreenProjection_0",
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "shared_fission_bg_XDJAScreenProjection_0",
                    "shared_fission_bg_XDJAScreenProjection_1"
                ),
                preferFull = true,
                requireShared = true
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "fission_bg_XDJAScreenProjection",
                    "XDJAScreenProjection_0"
                ),
                preferFull = true,
                requireShared = true
            )
        )
        assertEquals(
            "XDJAScreenProjection_1",
            selectDiLink5ProjectionDisplayName(
                listOf("XDJAScreenProjection_1"),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("fission_bg_XDJAScreenProjection", "unrelated"),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf(
                    "FISSION_BG_xdjascreenprojection"
                ),
                preferFull = false,
                requireShared = false
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("Remote_Dashboard"),
                preferFull = true
            )
        )
        assertNull(
            selectDiLink5ProjectionDisplayName(
                listOf("presentation", "unrelated"),
                preferFull = false
            )
        )
    }

    @Test
    fun aaosHdmiTopologyFailsClosedBeforeContainerCommands() {
        assertTrue(
            isClosedAaosProjectionTopology(
                "DX_BYD_AUTO",
                automotiveFeature = true,
                displayNames = listOf("內置畫面", "HDMI 螢幕")
            )
        )
        assertTrue(
            isClosedAaosProjectionTopology(
                "other_product",
                automotiveFeature = true,
                displayNames = listOf("Built-in Screen", "HDMI Screen")
            )
        )
        assertFalse(
            isClosedAaosProjectionTopology(
                "trinket",
                automotiveFeature = true,
                displayNames = listOf(
                    "Built-in Screen",
                    "shared_fission_bg_XDJAScreenProjection_0"
                )
            )
        )
        assertFalse(
            isClosedAaosProjectionTopology(
                "phone_like",
                automotiveFeature = false,
                displayNames = listOf("Built-in Screen", "HDMI Screen")
            )
        )
        assertTrue(
            isClosedAaosProjectionTopology(
                "DX_BYD_AUTO",
                automotiveFeature = true,
                displayNames = listOf(
                    "Built-in Screen", "HDMI Screen", "Remote_Dashboard"
                )
            )
        )
    }

    @Test
    fun runtimeTopologySeparatesTheTwoFieldLogFamilies() {
        assertEquals(
            DiLink5ProjectionTopology.TRINKET_DEBUG_ONLY,
            classifyDiLink5ProjectionTopology(
                product = "trinket",
                device = "trinket",
                fingerprint = "BYD-AUTO/trinket/trinket:13/build",
                automotiveFeature = false,
                fissionSingleOs = "0",
                displayNames = listOf(
                    "Built-in Screen",
                    "fission_bg_XDJAScreenProjection"
                )
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.AAOS_HDMI_CLOSED,
            classifyDiLink5ProjectionTopology(
                product = "unknown_byd_product",
                device = null,
                fingerprint = null,
                automotiveFeature = true,
                fissionSingleOs = null,
                displayNames = listOf("內置畫面", "HDMI 螢幕")
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.SHARED_FISSION,
            classifyDiLink5ProjectionTopology(
                product = "trinket",
                device = "trinket",
                fingerprint = null,
                automotiveFeature = false,
                fissionSingleOs = "0",
                displayNames = listOf(
                    "fission_bg_XDJAScreenProjection",
                    "shared_fission_bg_XDJAScreenProjection_0"
                )
            )
        )
        assertEquals(
            DiLink5ProjectionTopology.SINGLE_OS_CLOSED,
            classifyDiLink5ProjectionTopology(
                product = "byd",
                device = "byd",
                fingerprint = null,
                automotiveFeature = false,
                fissionSingleOs = "1",
                displayNames = listOf("Built-in Screen")
            )
        )
    }

    @Test
    fun pendingMarkerAloneDoesNotClaimCompositorOwnership() {
        assertFalse(
            mayOwnDiLink5ProjectionCompositor(
                pending = true, powered = false
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false, powered = true
            )
        )
        assertFalse(
            mayOwnDiLink5ProjectionCompositor(
                pending = false, powered = false
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false,
                powered = false,
                enableIndeterminate = true
            )
        )
        assertTrue(
            mayOwnDiLink5ProjectionCompositor(
                pending = false,
                powered = false,
                teardownIndeterminate = true
            )
        )
    }

    @Test
    fun indeterminateEnableOwnershipExpiresOnlyAcrossAProvenBootChange() {
        val bootA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val bootB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        assertTrue(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertTrue(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = "",
                currentBootId = bootA
            )
        )
        assertFalse(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = true,
                originBootId = bootA,
                currentBootId = bootB
            )
        )
        assertFalse(
            diLink5IndeterminateEnableMayStillComplete(
                enableIndeterminate = false,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertTrue(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = bootA,
                currentBootId = bootA
            )
        )
        assertFalse(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = bootA,
                currentBootId = bootB
            )
        )
        assertTrue(
            diLink5IndeterminateCommandMayStillComplete(
                indeterminate = true,
                originBootId = "corrupt-boot-id",
                currentBootId = bootB
            )
        )
        assertEquals(bootA, canonicalDiLink5BootIdOrNull(bootA))
        assertNull(canonicalDiLink5BootIdOrNull("corrupt-boot-id"))
        assertNull(canonicalDiLink5BootIdOrNull(bootA.uppercase()))
    }

    @Test
    fun recoveryMarkerFieldsRejectCoercionAndMalformedBootIds() {
        val boot = "11111111-1111-1111-1111-111111111111"
        val valid = JSONObject()
            .put("flag", true)
            .put("name", "pkg")
            .put("boot", boot)
        assertEquals(
            true,
            DiLink5ClusterCast.strictMarkerBoolean(
                valid, "flag", false
            )
        )
        assertEquals(
            "pkg",
            DiLink5ClusterCast.strictMarkerString(
                valid, "name", ""
            )
        )
        assertEquals(
            boot,
            DiLink5ClusterCast.strictMarkerBootId(valid, "boot")
        )

        val malformed = JSONObject()
            .put("flag", "true")
            .put("name", 7)
            .put("boot", "not-a-kernel-boot-id")
        assertNull(
            DiLink5ClusterCast.strictMarkerBoolean(
                malformed, "flag", false
            )
        )
        assertNull(
            DiLink5ClusterCast.strictMarkerString(
                malformed, "name", ""
            )
        )
        assertNull(
            DiLink5ClusterCast.strictMarkerBootId(
                malformed, "boot"
            )
        )
    }

    @Test
    fun recoveryRetriesAreBoundedAndSkipSameBootIndeterminateCommands() {
        assertEquals(
            1_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(0, false)
        )
        assertEquals(
            3_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(1, false)
        )
        assertEquals(
            8_000L,
            DiLink5ClusterCast.recoveryRetryDelayMs(2, false)
        )
        assertEquals(
            -1L,
            DiLink5ClusterCast.recoveryRetryDelayMs(3, false)
        )
        assertEquals(
            -1L,
            DiLink5ClusterCast.recoveryRetryDelayMs(0, true)
        )
    }

    @Test
    fun legacyRecoveryPropagatesTheExactSameBootCause() {
        val recovered = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertTrue(recovered.recovered)
        assertFalse(recovered.sameBootIndeterminate)

        val sameBoot = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = true,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .SAME_BOOT_INDETERMINATE
            )
        assertFalse(sameBoot.recovered)
        assertTrue(sameBoot.sameBootIndeterminate)

        val taskFailure = DiLink5ClusterCast
            .combineLegacyBootRecoveryOutcome(
                taskRecovered = false,
                gaugeRecovery =
                    ClusterProjectionController.LegacyBootRecoveryResult
                        .RECOVERED
            )
        assertFalse(taskFailure.recovered)
        assertFalse(taskFailure.sameBootIndeterminate)
    }

    @Test
    fun teardownCommandIsMarkedBeforeDispatchAndClearedAfterDefinitiveReply() {
        val events = mutableListOf<String>()
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = {
                events += "mark"
                true
            },
            shouldSend = { true },
            send = {
                events += "send"
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = {
                events += "clear"
                true
            }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.ACCEPTED,
            result
        )
        assertEquals(listOf("mark", "send", "clear"), events)
    }

    @Test
    fun indeterminateTeardownNeverClearsDurableOwnership() {
        var cleared = false
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { true },
            shouldSend = { true },
            send = {
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
            },
            clearInFlight = {
                cleared = true
                true
            }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
        assertFalse(cleared)
    }

    @Test
    fun teardownIsNotDispatchedWithoutADurableInFlightMarker() {
        var sent = false
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { false },
            shouldSend = { true },
            send = {
                sent = true
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = { true }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
        assertFalse(sent)
    }

    @Test
    fun failedTeardownMarkerClearMakesAReplyIndeterminate() {
        val result = executeDiLink5MarkedTeardownCommand(
            markInFlight = { true },
            shouldSend = { true },
            send = {
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            },
            clearInFlight = { false }
        )

        assertEquals(
            ClusterProjectionController.DiLink5CommandResult.INDETERMINATE,
            result
        )
    }

    @Test
    fun everyDefinitiveTeardownReplyClearsItsInFlightMarker() {
        listOf(
            ClusterProjectionController.DiLink5CommandResult.REJECTED,
            ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE,
            ClusterProjectionController.DiLink5CommandResult.TRANSPORT_FAILURE
        ).forEach { reply ->
            var clears = 0
            val result = executeDiLink5MarkedTeardownCommand(
                markInFlight = { true },
                shouldSend = { true },
                send = { reply },
                clearInFlight = {
                    clears++
                    true
                }
            )
            assertEquals(reply, result)
            assertEquals(1, clears)
        }
    }

    @Test
    fun onlyAcceptedOrIndeterminateEnableCarriesCleanupOwnership() {
        assertTrue(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
            )
        )
        assertTrue(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
            )
        )
        assertFalse(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.REJECTED
            )
        )
        assertFalse(
            shouldTrackDiLink5CompositorOwnership(
                ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE
            )
        )
    }

    @Test
    fun authoritativeInventoryParserIncludesHdmiAndProjectionSurfaces() {
        assertEquals(
            listOf(
                "Built-in Screen",
                "HDMI Screen",
                "shared_fission_bg_XDJAScreenProjection_0"
            ),
            parseDiLink5DisplayNames(
                listOf(
                    """DisplayDeviceInfo{"Built-in Screen": state ON}""",
                    """DisplayInfo{"HDMI Screen", displayId 1", state ON}""",
                    """DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0, displayId 3", state ON}"""
                )
            )
        )
    }

    @Test
    fun visibleProjectionDisplayMustBeLiveAndPowered() {
        assertTrue(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_ON)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(false, Display.STATE_ON)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_OFF)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(true, Display.STATE_UNKNOWN)
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(
                true, Display.STATE_DOZE_SUSPEND
            )
        )
        assertFalse(
            isUsableDiLink5ProjectionDisplay(
                true, Display.STATE_ON_SUSPEND
            )
        )
    }

    @Test
    fun dumpsysParserKeepsDisplayIdentityAndRejectsDeadEntries() {
        val refs = parseDiLink5ProjectionDisplays(
            listOf(
                """mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, layerStack 2, state ON}""",
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0", displayId 3", real 1920 x 720, layerStack 3, state ON}""",
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_1", displayId 4", real 1920 x 720, layerStack 4, state OFF}""",
                """mBaseDisplayInfo=DisplayInfo{"XDJAScreenProjection_0", displayId 5", real 1920 x 720, layerStack 5}""",
                """mBaseDisplayInfo=DisplayInfo{"XDJAScreenProjection_1", displayId 6", real 1920 x 720, layerStack 6, state ON_SUSPEND}""",
                """DisplayDeviceInfo{"shared_fission_bg_XDJAScreenProjection_0": state ON}"""
            )
        )

        assertEquals(
            listOf(
                DiLink5ProjectionDisplayRef(
                    2, "fission_bg_XDJAScreenProjection", 1_920, 720
                ),
                DiLink5ProjectionDisplayRef(
                    3, "shared_fission_bg_XDJAScreenProjection_0", 1_920, 720
                )
            ),
            refs
        )
    }

    @Test
    fun dumpsysParserAcceptsBothBydHeaderFormatsAndNeverUsesTheSquareEnvelope() {
        val refs = parseDiLink5ProjectionDisplays(
            listOf(
                // Exact field ordering from log_L3GHGKTL.
                """mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, app 1920 x 720, layerStack 2, state ON}""",
                """mOverrideDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", real 1920 x 720, largest app 1920 x 1920, smallest app 720 x 720, app 1920 x 720, layerStack 2, state ON}""",
                // Alternate Android format with displayId inside the quoted header.
                """mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0, displayId 3", real 1 x 1, app 1 x 1, layerStack 3, state ON}"""
            )
        )

        assertEquals(2, refs.size)
        assertEquals(
            DiLink5ProjectionDisplayRef(
                2, "fission_bg_XDJAScreenProjection", 1_920, 720
            ),
            refs[0]
        )
        assertEquals(
            DiLink5ProjectionDisplayRef(
                3, "shared_fission_bg_XDJAScreenProjection_0", 0, 0
            ),
            refs[1]
        )
    }

    @Test
    fun sharedShadowUsesThePlainComposedPanelGeometryWhenNeeded() {
        val plain = DiLink5ProjectionDisplayRef(
            2, "fission_bg_XDJAScreenProjection", 1_920, 720
        )
        val shared = DiLink5ProjectionDisplayRef(
            3, "shared_fission_bg_XDJAScreenProjection_0", 1, 1
        )

        assertEquals(
            1_920 to 720,
            resolveDiLink5ProjectionPanelSize(listOf(plain, shared), shared)
        )
        assertEquals(
            1_280 to 480,
            resolveDiLink5ProjectionPanelSize(
                listOf(plain),
                DiLink5ProjectionDisplayRef(
                    4, "XDJAScreenProjection_1", 1_280, 480
                )
            )
        )
    }

    @Test
    fun projectionBoundsStayVisibleAndMeetTheMinimumSize() {
        val bounds = clampDiLink5ProjectionBounds(
            1_250, 450, 1_280, 480, 1_280, 480
        )

        assertEquals(1_080, bounds!!.left)
        assertEquals(280, bounds.top)
        assertEquals(1_280, bounds.right)
        assertEquals(480, bounds.bottom)
        assertNull(clampDiLink5ProjectionBounds(10, 10, 10, 20, 1_280, 480))
    }

    @Test
    fun forceRestartIsReservedForAPositivelyKnownOffDisplayTask() {
        assertTrue(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = 42,
                currentDisplayId = 0,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = 42,
                currentDisplayId = 3,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = false,
                taskId = 42,
                currentDisplayId = 0,
                targetDisplayId = 3
            )
        )
        assertFalse(
            shouldForceRestartDiLink5Task(
                locationKnown = true,
                taskId = -1,
                currentDisplayId = -1,
                targetDisplayId = 3
            )
        )
    }
}
