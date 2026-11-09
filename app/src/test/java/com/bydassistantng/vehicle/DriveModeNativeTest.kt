package com.bydassistantng.vehicle

import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveModeNativeTest {
    @After
    fun forgetWhatTheCarRefused() {
        NativeRoute.forgetRefusals()
    }

    private val preselect = DriveModeControl.PRESELECT_ID
    private val target = 255852712
    private val refusal = InvocationTargetException(
        SecurityException("Neither user 2000 nor current process has android.permission.BYDAUTO_SETTING_COMMON."),
    )

    /** A car whose drive mode reads as scripted (the last reading repeats) and which counts what it is asked. */
    private class ScriptedDevice(
        readings: List<Int?>,
        override val tag: String = "",
        private val answer: VehicleDispatchResult = VehicleDispatchResult.Success(),
    ) : DriveModeCommand.Device {
        private val script = ArrayDeque(readings)
        private var last: Int? = null
        var reads = 0
        val asked = ArrayList<Int>()

        override fun reportedMode(): Int? {
            reads++
            if (script.isNotEmpty()) last = script.removeFirst()
            return last
        }

        override fun ask(value: Int): VehicleDispatchResult {
            asked += value
            return answer
        }
    }

    /** The car's native service as the helper would see it: what it was asked, and the answers to give. */
    private class FakeCalls(
        private val readReply: NativeAutoservice.Reply? = NativeAutoservice.Reply(0, 1),
        private val writeReply: NativeAutoservice.Reply? = NativeAutoservice.Reply(1, 0),
    ) : NativeCalls {
        val reads = ArrayList<Pair<Int, Int>>()
        val writes = ArrayList<Triple<Int, Int, Int>>()

        override fun getInt(deviceType: Int, featureId: Int): NativeAutoservice.Reply? {
            reads += deviceType to featureId
            return readReply
        }

        override fun setInt(deviceType: Int, featureId: Int, value: Int): NativeAutoservice.Reply? {
            writes += Triple(deviceType, featureId, value)
            return writeReply
        }
    }

    /** Time that only moves when the watching waits: 25 ms at each pause. */
    private class Clock {
        var nanos = 0L
        val now: () -> Long = { nanos }
        val pause: (Long) -> Unit = { ms -> nanos += ms * 1_000_000L }
    }

    private fun carryOut(device: ScriptedDevice, value: Int, speed: Int = 0): VehicleDispatchResult {
        val clock = Clock()
        return DriveModeCommand.carryOut(device, value, speed, clock.now, clock.pause)
    }

    private fun reading(result: VehicleDispatchResult): DriveModeControl.Reading =
        checkNotNull(DriveModeControl.parseNote((result as VehicleDispatchResult.Success).note!!))

    // ── The request ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aCarAlreadyInTheModeIsSentNothing() {
        val device = ScriptedDevice(listOf(3))
        val result = carryOut(device, 3, speed = 12)

        assertTrue(device.asked.isEmpty())
        assertEquals(DriveModeControl.Reading(before = 3, now = 3, requested = 3, ms = 0, speedKmh = 12), reading(result))
    }

    @Test
    fun theModeTheCarMovesToIsReportedWithTheTimeItTook() {
        // Read before the request, then three times while watching: the third says Sport.
        val device = ScriptedDevice(listOf(1, 1, 1, 3))
        val result = carryOut(device, 3)

        assertEquals(listOf(3), device.asked)
        assertEquals(DriveModeControl.Reading(before = 1, now = 3, requested = 3, ms = 50, speedKmh = 0), reading(result))
        assertEquals(DriveModeOutcome.Changed(before = "Normal", now = "Sport"), DriveModeControl.outcomeOf(DriveMode.SPORT, result))
    }

    @Test
    fun aCarThatStaysIsWatchedForTheWholeWindowAndThenReportedAsItIs() {
        val device = ScriptedDevice(listOf(1))
        val result = carryOut(device, 3)

        assertEquals(listOf(3), device.asked)
        assertEquals(DriveModeCommand.WATCH_MS, reading(result).ms)
        assertEquals(
            DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"),
            DriveModeControl.outcomeOf(DriveMode.SPORT, result),
        )
    }

    @Test
    fun aCarThatGivesNoModeIsNotAskedAnything() {
        val device = ScriptedDevice(listOf(null))
        val result = carryOut(device, 3)

        assertTrue(device.asked.isEmpty())
        assertEquals(
            VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, "the car did not report its drive mode"),
            result,
        )
    }

    @Test
    fun aRequestTheCarRefusesIsAnswerAtOnceWithoutWatching() {
        val refused = VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "the car returned error code 7")
        val device = ScriptedDevice(listOf(1), answer = refused)
        val result = carryOut(device, 3)

        assertSame(refused, result)
        assertEquals(1, device.reads)
    }

    @Test
    fun exitHasNothingToLeaveOnTheRoadAndIsDoneWhenAnOffRoadModeIsLeft() {
        val onTheRoad = ScriptedDevice(listOf(1))
        assertTrue(carryOut(onTheRoad, 255) is VehicleDispatchResult.Success)
        assertTrue(onTheRoad.asked.isEmpty())

        val inSnow = ScriptedDevice(listOf(4, 4, 1))
        val result = carryOut(inSnow, 255)
        assertEquals(listOf(255), inSnow.asked)
        assertEquals(DriveModeOutcome.Changed(before = "Snow", now = "Normal"), DriveModeControl.outcomeOf(DriveMode.EXIT, result))
    }

    @Test
    fun theNoteSaysWhichWayTheCarWasReachedAndStillReadsAsAnAnswer() {
        val viaService = ScriptedDevice(listOf(1, 3), tag = NativeAnswer.NATIVE_TAG)
        val result = carryOut(viaService, 3) as VehicleDispatchResult.Success

        assertTrue(result.note!!.endsWith(NativeAnswer.NATIVE_TAG))
        // The tag is for the log: the answer to the model is built from the reading and has no trace of it.
        assertEquals(DriveModeOutcome.Changed(before = "Normal", now = "Sport"), DriveModeControl.outcomeOf(DriveMode.SPORT, result))
        val toModel = SetDriveModeTool.answer(DriveModeControl.outcomeOf(DriveMode.SPORT, result)).toString()
        assertFalse(toModel, toModel.contains("native"))
    }

    // ── The car's native service ────────────────────────────────────────────────────────────────────

    @Test
    fun theNativeDeviceReadsTheTargetStateOfTheSettingDevice() {
        val calls = FakeCalls(readReply = NativeAutoservice.Reply(0, 3))
        val device = DriveModeCommand.NativeDevice(calls, preselect, target)

        assertEquals(3, device.reportedMode())
        assertEquals(listOf(DriveModeControl.DEVICE_TYPE to target), calls.reads)
        assertEquals(NativeAnswer.NATIVE_TAG, device.tag)
    }

    @Test
    fun theNativeDeviceTakesTheCarsNoReadingForNoMode() {
        for (reply in listOf(NativeAutoservice.Reply(0, 65535), NativeAutoservice.Reply(0, -10011), NativeAutoservice.Reply(-1, 3), null)) {
            assertNull("$reply", DriveModeCommand.NativeDevice(FakeCalls(readReply = reply), preselect, target).reportedMode())
        }
    }

    @Test
    fun theNativeDeviceWritesTheOneFeatureAndNothingElse() {
        val calls = FakeCalls(writeReply = NativeAutoservice.Reply(1, 0))
        val device = DriveModeCommand.NativeDevice(calls, preselect, target)

        val result = device.ask(3)

        assertEquals(VehicleDispatchResult.Success("HAL returned 1${NativeAnswer.NATIVE_TAG}"), result)
        assertEquals(listOf(Triple(DriveModeControl.DEVICE_TYPE, preselect, 3)), calls.writes)
    }

    @Test
    fun theNativeDeviceWritesNothingForAValueTheCarsScreenNeverSends() {
        val calls = FakeCalls()
        val device = DriveModeCommand.NativeDevice(calls, preselect, target)

        assertThrows(IllegalStateException::class.java) { device.ask(99) }
        assertThrows(IllegalStateException::class.java) { device.ask(0) }
        assertTrue(calls.writes.isEmpty())
        // Nor to another feature, whatever the value.
        val other = DriveModeCommand.NativeDevice(calls, preselect + 1, target)
        assertThrows(IllegalStateException::class.java) { other.ask(3) }
        assertTrue(calls.writes.isEmpty())
    }

    @Test
    fun aRefusalByTheServiceIsAFailureWithItsKind() {
        val denied = DriveModeCommand.NativeDevice(FakeCalls(writeReply = NativeAutoservice.Reply(NativeAnswer.PERMISSION_DENIED, 0)), preselect, target)
        assertEquals(VehicleDispatchError.SECURITY_DENIED, (denied.ask(3) as VehicleDispatchResult.Failure).error)

        val absent = DriveModeCommand.NativeDevice(FakeCalls(writeReply = NativeAutoservice.Reply(NativeAnswer.NOT_AVAILABLE, 0)), preselect, target)
        assertEquals(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, (absent.ask(3) as VehicleDispatchResult.Failure).error)

        val silent = DriveModeCommand.NativeDevice(FakeCalls(writeReply = null), preselect, target)
        assertEquals(VehicleDispatchError.INVOCATION_FAILED, (silent.ask(3) as VehicleDispatchResult.Failure).error)
    }

    // ── Which way the helper goes ───────────────────────────────────────────────────────────────────

    private fun route(
        mode: NativeMode,
        kit: () -> DriveModeCommand.Device,
        service: () -> NativeCalls?,
        log: MutableList<String> = ArrayList(),
        work: (DriveModeCommand.Device) -> VehicleDispatchResult = { VehicleDispatchResult.Success() },
    ): VehicleDispatchResult = DriveModeCommand.viaClassOrService(mode, kit, service, preselect, target, { log += it }, work)

    @Test
    fun theDeviceClassIsUsedAndTheServiceIsNotEvenLookedForWhileTheCarAcceptsIt() {
        val kitDevice = ScriptedDevice(listOf(1))
        var used: DriveModeCommand.Device? = null

        route(NativeMode.AUTO, kit = { kitDevice }, service = { error("the service is not needed") }) { used = it; VehicleDispatchResult.Success() }

        assertSame(kitDevice, used)
        assertFalse(NativeRoute.isRefused(DriveModeCommand.SETTING_DEVICE_CLASS))
    }

    @Test
    fun aRefusalWhenTheClassIsMadeSendsTheRequestToTheServiceAndIsRemembered() {
        val calls = FakeCalls()
        val log = ArrayList<String>()
        var used: DriveModeCommand.Device? = null

        val result = route(NativeMode.AUTO, kit = { throw refusal }, service = { calls }, log = log) { used = it; VehicleDispatchResult.Success() }

        assertTrue(result is VehicleDispatchResult.Success)
        assertTrue(used is DriveModeCommand.NativeDevice)
        assertTrue(NativeRoute.isRefused(DriveModeCommand.SETTING_DEVICE_CLASS))
        assertEquals(1, log.size)
        assertTrue(log.single(), log.single().contains("BYDAUTO_SETTING_COMMON"))
    }

    @Test
    fun aCarWithoutTheSettingClassIsSpokenToDirectlyToo() {
        val calls = FakeCalls()
        var used: DriveModeCommand.Device? = null

        val result = route(NativeMode.AUTO, kit = { throw ClassNotFoundException(DriveModeCommand.SETTING_DEVICE_CLASS) }, service = { calls }) { used = it; VehicleDispatchResult.Success() }

        assertTrue(result is VehicleDispatchResult.Success)
        assertTrue(used is DriveModeCommand.NativeDevice)
    }

    @Test
    fun aRefusalWhenTheClassIsFirstReadIsTheSameAndNothingWasSentBefore() {
        // The sim's car refuses at the first call and the real one when the class is made: both arrive as the same exception.
        val calls = FakeCalls()
        val kitDevice = ScriptedDevice(listOf(1))
        val seen = ArrayList<DriveModeCommand.Device>()

        route(NativeMode.AUTO, kit = { kitDevice }, service = { calls }) { device ->
            seen += device
            if (device === kitDevice) throw refusal
            VehicleDispatchResult.Success()
        }

        assertEquals(2, seen.size)
        assertSame(kitDevice, seen[0])
        assertTrue(seen[1] is DriveModeCommand.NativeDevice)
        assertTrue(kitDevice.asked.isEmpty())
    }

    @Test
    fun onceTheClassHasBeenRefusedTheNextRequestGoesStraightToTheService() {
        NativeRoute.markRefused(DriveModeCommand.SETTING_DEVICE_CLASS)
        var kitMade = 0

        val result = route(NativeMode.AUTO, kit = { kitMade++; throw refusal }, service = { FakeCalls() })

        assertTrue(result is VehicleDispatchResult.Success)
        assertEquals(0, kitMade)
    }

    @Test
    fun whenTheNativeRouteIsForbiddenARefusalIsWordedForThePersonNotTheCarsException() {
        val result = route(NativeMode.NEVER, kit = { throw refusal }, service = { error("forbidden") })

        assertEquals(VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, DriveModeControl.REFUSED_REASON), result)
        assertFalse(NativeRoute.isRefused(DriveModeCommand.SETTING_DEVICE_CLASS))
    }

    @Test
    fun aCarWithNoServiceToSpeakToAnswersTheSameWay() {
        val result = route(NativeMode.AUTO, kit = { throw refusal }, service = { null })

        assertEquals(VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, DriveModeControl.REFUSED_REASON), result)
        assertFalse(NativeRoute.isRefused(DriveModeCommand.SETTING_DEVICE_CLASS))
    }

    @Test
    fun theTestSwitchCanSendTheRequestToTheServiceFromTheStartAndFallsBackWhereThereIsNone() {
        var kitMade = 0
        val kitDevice = ScriptedDevice(listOf(1))
        var used: DriveModeCommand.Device? = null

        route(NativeMode.ALWAYS, kit = { kitMade++; kitDevice }, service = { FakeCalls() }) { used = it; VehicleDispatchResult.Success() }
        assertTrue(used is DriveModeCommand.NativeDevice)
        assertEquals(0, kitMade)

        route(NativeMode.ALWAYS, kit = { kitMade++; kitDevice }, service = { null }) { used = it; VehicleDispatchResult.Success() }
        assertSame(kitDevice, used)
        assertEquals(1, kitMade)
    }

    @Test
    fun aFailureThatIsNotTheCarsPermissionCheckIsNeverTurnedIntoAnotherAttempt() {
        var serviceAsked = false

        assertThrows(IllegalStateException::class.java) {
            route(NativeMode.AUTO, kit = { throw IllegalStateException("boom") }, service = { serviceAsked = true; FakeCalls() })
        }
        assertFalse(serviceAsked)
    }

    // ── What the person hears ───────────────────────────────────────────────────────────────────────

    @Test
    fun aCarThatIsTooFastOrDoesNotSayHowFastIsToldWhichOfTheTwo() {
        assertEquals("the car is going too fast for a drive mode change (95 km/h; the limit is 80)", DriveModeControl.speedRefusal(95.0))
        assertEquals("the car is going too fast for a drive mode change (80 km/h; the limit is 80)", DriveModeControl.speedRefusal(80.0))
        for (unknown in listOf(null, Double.NaN, -1.0)) {
            val reason = DriveModeControl.speedRefusal(unknown)
            assertTrue("$unknown: $reason", reason.contains("did not report its speed"))
            assertFalse("$unknown: $reason", reason.contains("too fast"))
        }
        // Whatever the gate refuses, it has something to say, and whatever it lets through it never says.
        for (speed in listOf(null, Double.NaN, -1.0, 80.0, 120.0)) assertFalse("$speed", DriveModeControl.mayChangeAt(speed))
        for (speed in listOf(0.0, 12.5, 79.9)) assertTrue("$speed", DriveModeControl.mayChangeAt(speed))
    }

    @Test
    fun aRefusedRequestReachesTheModelAsOneSentenceWithTheCarsExceptionNowhereInIt() {
        val failure = VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, DriveModeControl.REFUSED_REASON)
        val outcome = DriveModeControl.outcomeOf(DriveMode.SPORT, failure)
        val error = (SetDriveModeTool.answer(outcome)["error"] as JsonPrimitive).content

        assertEquals("Could not change the drive mode: this car's software does not let this app do that, so nothing was sent.", error)
        assertFalse(error.contains("Exception"))
        assertFalse(error.contains(".."))
    }

    @Test
    fun theRefusalsTheCarHasMadeAreRememberedOnlyForTheClassItRefused() {
        assertFalse(NativeRoute.isRefused("a.b.C"))
        NativeRoute.markRefused("a.b.C")
        assertTrue(NativeRoute.isRefused("a.b.C"))
        assertFalse(NativeRoute.isRefused("a.b.D"))
        NativeRoute.forgetRefusals()
        assertFalse(NativeRoute.isRefused("a.b.C"))
        assertNotNull(DriveModeCommand.SETTING_DEVICE_CLASS)
    }
}
