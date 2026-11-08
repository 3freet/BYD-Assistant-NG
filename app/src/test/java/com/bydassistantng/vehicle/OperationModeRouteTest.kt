package com.bydassistantng.vehicle

import com.bydassistantng.gemini.assistantTools
import java.lang.reflect.InvocationTargetException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The classic drive mode: the Energy device's operation mode, the six modes its class takes, for a car whose table lacks the Leopard's feature. */
class OperationModeRouteTest {
    @After
    fun forgetWhatTheCarRefused() {
        NativeRoute.forgetRefusals()
    }

    private val control = 734003229
    private val state = 555745294

    /** A car's table as the route sees it: the names it has, with the numbers it gives them. */
    private fun table(vararg entries: Pair<String, Int>): (String, String) -> Int? {
        val map = entries.toMap()
        return { group, name -> map["$group.$name"] }
    }

    private val leopard = table(
        "Setting.${DriveModeControl.PRESELECT_NAME}" to DriveModeControl.PRESELECT_ID,
        "Energy.ENERGY_OPERATION_MODE_SET" to control,
        "Energy.ENERGY_OPERATION_MODE" to state,
    )
    private val classic = table("Energy.ENERGY_OPERATION_MODE_SET" to control, "Energy.ENERGY_OPERATION_MODE" to state)

    // ── Which route a car is on ─────────────────────────────────────────────────────────────────────

    @Test
    fun aCarThatNamesTheLeopardsFeatureKeepsTheWayItHasAlwaysUsedEvenWhenItHasTheOtherToo() {
        assertEquals(DriveModeRoute.SETTING, DriveModeControl.route(leopard))
    }

    @Test
    fun aCarThatNamesOnlyTheOperationModeIsOnTheClassicRoute() {
        assertEquals(DriveModeRoute.OPERATION_MODE, DriveModeControl.route(classic))
    }

    @Test
    fun theLeopardsFeatureUnderAnotherNumberIsNotTheLeopardsRoute() {
        // The Leopard's route stays behind its exact id; a car that numbers the name otherwise falls to the classic route if it has it.
        val otherNumber = table("Setting.${DriveModeControl.PRESELECT_NAME}" to DriveModeControl.PRESELECT_ID + 1, "Energy.ENERGY_OPERATION_MODE_SET" to control, "Energy.ENERGY_OPERATION_MODE" to state)
        assertEquals(DriveModeRoute.OPERATION_MODE, DriveModeControl.route(otherNumber))
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route(table("Setting.${DriveModeControl.PRESELECT_NAME}" to DriveModeControl.PRESELECT_ID + 1)))
    }

    @Test
    fun theClassicRouteNeedsBothTheControlAndTheStateToReadItBack() {
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route(table("Energy.ENERGY_OPERATION_MODE_SET" to control)))
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route(table("Energy.ENERGY_OPERATION_MODE" to state)))
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route(table()))
    }

    @Test
    fun aTestSwitchCanPutACarThatHasBothOnTheClassicRouteButNeverOnOneItLacks() {
        assertEquals(DriveModeRoute.OPERATION_MODE, DriveModeControl.route(leopard, preferred = "operation"))
        assertEquals(DriveModeRoute.SETTING, DriveModeControl.route(leopard, preferred = "setting"))
        assertEquals(DriveModeRoute.SETTING, DriveModeControl.route(leopard, preferred = "auto"))
        assertEquals(DriveModeRoute.OPERATION_MODE, DriveModeControl.route(classic, preferred = "setting"))
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route(table(), preferred = "operation"))
        assertEquals(DriveModeRoute.SETTING, DriveModeControl.route(table("Setting.${DriveModeControl.PRESELECT_NAME}" to DriveModeControl.PRESELECT_ID), preferred = "operation"))
    }

    @Test
    fun withoutTheCarsFrameworkThereIsNoRoute() {
        assertEquals(DriveModeRoute.NONE, DriveModeControl.route())
        assertFalse(OperationModeRoute.isOnThisCar())
    }

    // ── What each route may be asked ────────────────────────────────────────────────────────────────

    @Test
    fun theClassicRouteOffersTheSixModesTheCarsClassTakesAndNotTheOthers() {
        // Eco, Normal and Sport were only examples of what a car has; the class takes Snow, Muddy and Sand too.
        assertEquals(
            setOf("eco", "normal", "sport", "snow", "mud", "sand"),
            DriveModeControl.modesOf(DriveModeRoute.OPERATION_MODE).map { it.word }.toSet(),
        )
        assertEquals(6, DriveModeControl.modesOf(DriveModeRoute.OPERATION_MODE).size)
        assertEquals(DriveMode.entries, DriveModeControl.modesOf(DriveModeRoute.SETTING))
        assertTrue(DriveModeControl.modesOf(DriveModeRoute.NONE).isEmpty())
    }

    @Test
    fun eachRouteMayBeAskedForItsOwnNumbersAndNoOthers() {
        for (mode in OperationModeRoute.MODES) {
            assertTrue(mode.word, DriveModeControl.isPermitted(DriveModeRoute.OPERATION_MODE, DriveModeControl.requestFor(mode)))
        }
        // The class has no number for these; the Leopard's route does.
        for (mode in listOf(DriveMode.MOUNTAIN, DriveMode.ROCK, DriveMode.AUTO, DriveMode.EXIT)) {
            assertFalse(mode.word, DriveModeControl.isPermitted(DriveModeRoute.OPERATION_MODE, DriveModeControl.requestFor(mode)))
            assertTrue(mode.word, DriveModeControl.isPermitted(DriveModeRoute.SETTING, DriveModeControl.requestFor(mode)))
        }
        for (value in listOf(0, 7, 8, 9, 20, 21, 255, -1)) assertFalse("$value", DriveModeControl.isPermitted(DriveModeRoute.OPERATION_MODE, value))
        for (value in 0..300) assertFalse("$value", DriveModeControl.isPermitted(DriveModeRoute.NONE, value))
    }

    @Test
    fun theClassicRouteWritesToTheEnergyDeviceAndNoOtherDevice() {
        assertTrue(OperationModeRoute.isPermitted(OperationModeRoute.DEVICE_TYPE, 2))
        assertFalse(OperationModeRoute.isPermitted(DriveModeControl.DEVICE_TYPE, 2))
        assertFalse(OperationModeRoute.isPermitted(1002, 2))
    }

    @Test
    fun aCarOnTheClassicRouteIsToldWhatItCanBeAskedFor() {
        val reason = DriveModeControl.notOfferedReason(DriveModeRoute.OPERATION_MODE)
        // It speaks of what this app can ask, not of what the car has.
        assertTrue(reason, reason.startsWith("this app can only ask this car for "))
        for (word in listOf("eco", "normal", "sport", "snow", "mud", "sand")) assertTrue("$word: $reason", word in reason)
        assertFalse(reason, "rock" in reason || "mountain" in reason)
        assertEquals("that mode is not one this build may ask for", DriveModeControl.notOfferedReason(DriveModeRoute.SETTING))
    }

    // ── The car's own encoding ──────────────────────────────────────────────────────────────────────

    @Test
    fun theStateAndTheRequestAreTheCarsOwnNumbers() {
        // BYDAutoEnergyDevice: ENERGY_OPERATION_ECONOMY 1, SPORT 2, NORMAL 3 (what the state reads); the screen's numbers are Normal 1, Eco 2, Sport 3.
        assertEquals(2, OperationModeRoute.driveModeOf(1))
        assertEquals(3, OperationModeRoute.driveModeOf(2))
        assertEquals(1, OperationModeRoute.driveModeOf(3))
        assertEquals(3, OperationModeRoute.operationOf(1))
        assertEquals(1, OperationModeRoute.operationOf(2))
        assertEquals(2, OperationModeRoute.operationOf(3))
    }

    @Test
    fun askingForAModeAndReadingItBackAreInverse() {
        for (request in OperationModeRoute.REQUESTS) {
            assertEquals(request, OperationModeRoute.driveModeOf(OperationModeRoute.operationOf(request)!!))
        }
    }

    @Test
    fun theOffRoadModesKeepTheClassesNumbersApartFromTheScreens() {
        // Screen: Snow 4, Sand 5, Mud 6. Class: ENERGY_OPERATION_SNOW 4, MUDDY 5, SAND 6.
        assertEquals(4, OperationModeRoute.operationOf(4))
        assertEquals(6, OperationModeRoute.operationOf(5)) // the screen's Sand is the class's sand, 6
        assertEquals(5, OperationModeRoute.operationOf(6)) // the screen's Mud is the class's muddy, 5
        assertEquals(4, OperationModeRoute.driveModeOf(4))
        assertEquals(6, OperationModeRoute.driveModeOf(5)) // the class's muddy reads as the screen's Mud
        assertEquals(5, OperationModeRoute.driveModeOf(6)) // the class's sand reads as the screen's Sand
        assertNull(OperationModeRoute.driveModeOf(0))
        assertNull(OperationModeRoute.driveModeOf(7))
        assertNull(OperationModeRoute.operationOf(7))
        assertNull(OperationModeRoute.operationOf(255))
    }

    @Test
    fun whatTheClassWritesIsItsOwnRemapOfTheApiNumbers() {
        // setOperationMode: 3 -> 1, 1 -> 2, 2 -> 3, and 4 to 6 as they are. The screen's number reaches the same raw value by rawOf.
        for (request in OperationModeRoute.REQUESTS) {
            val api = OperationModeRoute.operationOf(request)!!
            val written = when (api) { 3 -> 1; 1 -> 2; 2 -> 3; else -> api }
            assertEquals("screen number $request", written, OperationModeRoute.rawOf(request))
        }
        assertNull(OperationModeRoute.rawOf(7))
        assertNull(OperationModeRoute.rawOf(21))
    }

    // ── The car's native service ────────────────────────────────────────────────────────────────────

    /** A car whose operation mode behaves as the Energy class describes: the raw number is written, the state moves to the API's. */
    private class ClassicCar(var stateValue: Int, private val moves: Boolean = true) : NativeCalls {
        val reads = ArrayList<Pair<Int, Int>>()
        val writes = ArrayList<Triple<Int, Int, Int>>()

        override fun getInt(deviceType: Int, featureId: Int): NativeAutoservice.Reply? {
            reads += deviceType to featureId
            return NativeAutoservice.Reply(0, stateValue)
        }

        override fun setInt(deviceType: Int, featureId: Int, value: Int): NativeAutoservice.Reply? {
            writes += Triple(deviceType, featureId, value)
            // The raw numbers (Normal 1, Eco 2, Sport 3, Snow 4, muddy 5, sand 6) and the state the car reports (Eco 1, Sport 2, Normal 3, then the same).
            if (moves) stateValue = when (value) { 1 -> 3; 2 -> 1; 3 -> 2; in 4..6 -> value; else -> stateValue }
            return NativeAutoservice.Reply(1, 0)
        }
    }

    private class Clock {
        var nanos = 0L
        val now: () -> Long = { nanos }
        val pause: (Long) -> Unit = { ms -> nanos += ms * 1_000_000L }
    }

    private fun carryOut(device: DriveModeCommand.Device, value: Int, clock: Clock = Clock()): VehicleDispatchResult =
        DriveModeCommand.carryOut(device, value, 0, clock.now, clock.pause)

    @Test
    fun theNativeDeviceReadsTheStateInTheScreensNumbers() {
        val car = ClassicCar(stateValue = 3)
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        assertEquals(1, device.reportedMode())
        assertEquals(listOf(OperationModeRoute.DEVICE_TYPE to state), car.reads)
        assertEquals(NativeAnswer.NATIVE_TAG, device.tag)
    }

    @Test
    fun theNativeDeviceTakesTheCarsNoReadingForNoMode() {
        for (reply in listOf(NativeAutoservice.Reply(0, 65535), NativeAutoservice.Reply(0, -10011), NativeAutoservice.Reply(-1, 3), null)) {
            val calls = object : NativeCalls {
                override fun getInt(deviceType: Int, featureId: Int) = reply
                override fun setInt(deviceType: Int, featureId: Int, value: Int) = null
            }
            assertNull("$reply", DriveModeCommand.OperationNativeDevice(calls, control, state).reportedMode())
        }
    }

    @Test
    fun theNativeDeviceWritesTheClassesRawNumberToTheOneFeatureAndNothingElse() {
        val car = ClassicCar(stateValue = 3)
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val result = device.ask(2)

        assertEquals(VehicleDispatchResult.Success("HAL returned 1${NativeAnswer.NATIVE_TAG}"), result)
        assertEquals(listOf(Triple(OperationModeRoute.DEVICE_TYPE, control, 2)), car.writes)
    }

    @Test
    fun theNativeDeviceWritesNothingForAnyOtherValue() {
        val car = ClassicCar(stateValue = 3)
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        for (value in listOf(0, 7, 8, 21, 255, -1)) assertThrows("$value", IllegalStateException::class.java) { device.ask(value) }
        assertTrue(car.writes.isEmpty())
    }

    @Test
    fun sandAndMudAreWrittenAsTheClassNumbersThemAndReadBackAsTheScreenDoes() {
        val car = ClassicCar(stateValue = 3) // Normal
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val sand = carryOut(device, DriveModeControl.requestFor(DriveMode.SAND)) as VehicleDispatchResult.Success
        assertEquals(DriveModeOutcome.Changed(before = "Normal", now = "Sand"), DriveModeControl.outcomeOf(DriveMode.SAND, sand))

        val mud = carryOut(device, DriveModeControl.requestFor(DriveMode.MUD)) as VehicleDispatchResult.Success
        assertEquals(DriveModeOutcome.Changed(before = "Sand", now = "Mud"), DriveModeControl.outcomeOf(DriveMode.MUD, mud))

        val snow = carryOut(device, DriveModeControl.requestFor(DriveMode.SNOW)) as VehicleDispatchResult.Success
        assertEquals(DriveModeOutcome.Changed(before = "Mud", now = "Snow"), DriveModeControl.outcomeOf(DriveMode.SNOW, snow))

        // The class's sand is 6 and its muddy 5: that is what reached the car, not the screen's 5 and 6.
        assertEquals(listOf(6, 5, 4), car.writes.map { it.third })
    }

    @Test
    fun aCarWithoutOffRoadModesThatIgnoresTheRequestIsReportedAsUnchanged() {
        val car = ClassicCar(stateValue = 3, moves = false)
        val result = carryOut(DriveModeCommand.OperationNativeDevice(car, control, state), DriveModeControl.requestFor(DriveMode.SAND))

        assertEquals(
            DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"),
            DriveModeControl.outcomeOf(DriveMode.SAND, result),
        )
    }

    @Test
    fun anEcoRequestFromNormalWritesTwoAndIsReportedAsEco() {
        val car = ClassicCar(stateValue = 3) // Normal
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val result = carryOut(device, DriveModeControl.requestFor(DriveMode.ECO)) as VehicleDispatchResult.Success

        assertEquals(listOf(Triple(OperationModeRoute.DEVICE_TYPE, control, 2)), car.writes)
        val reading = DriveModeControl.parseNote(result.note!!)!!
        assertEquals(1, reading.before)
        assertEquals(2, reading.now)
        assertEquals(DriveModeOutcome.Changed(before = "Normal", now = "Eco"), DriveModeControl.outcomeOf(DriveMode.ECO, result))
    }

    @Test
    fun sportAndNormalAreAskedAndReadBackTheSameWay() {
        val car = ClassicCar(stateValue = 1) // Eco
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val sport = carryOut(device, DriveModeControl.requestFor(DriveMode.SPORT)) as VehicleDispatchResult.Success
        assertEquals(DriveModeOutcome.Changed(before = "Eco", now = "Sport"), DriveModeControl.outcomeOf(DriveMode.SPORT, sport))

        val normal = carryOut(device, DriveModeControl.requestFor(DriveMode.NORMAL)) as VehicleDispatchResult.Success
        assertEquals(DriveModeOutcome.Changed(before = "Sport", now = "Normal"), DriveModeControl.outcomeOf(DriveMode.NORMAL, normal))
        assertEquals(listOf(3, 1), car.writes.map { it.third })
    }

    @Test
    fun aRequestForTheModeTheCarIsInSendsNothing() {
        val car = ClassicCar(stateValue = 2) // Sport
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val result = carryOut(device, DriveModeControl.requestFor(DriveMode.SPORT)) as VehicleDispatchResult.Success

        assertTrue(car.writes.isEmpty())
        assertEquals(DriveModeOutcome.Changed(before = "Sport", now = "Sport"), DriveModeControl.outcomeOf(DriveMode.SPORT, result))
    }

    @Test
    fun aCarThatTakesTheRequestAndDoesNotMoveIsReportedAsUnchangedNotAsDone() {
        val car = ClassicCar(stateValue = 3, moves = false)
        val device = DriveModeCommand.OperationNativeDevice(car, control, state)

        val result = carryOut(device, DriveModeControl.requestFor(DriveMode.ECO))

        assertEquals(
            DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"),
            DriveModeControl.outcomeOf(DriveMode.ECO, result),
        )
    }

    @Test
    fun aStateTheRouteCannotNameIsNotAModeAndNothingIsSent() {
        val car = ClassicCar(stateValue = 9)
        val result = carryOut(DriveModeCommand.OperationNativeDevice(car, control, state), 2)

        assertEquals(VehicleDispatchError.PRECONDITION_NOT_MET, (result as VehicleDispatchResult.Failure).error)
        assertTrue(car.writes.isEmpty())
    }

    // ── Which way the helper goes ───────────────────────────────────────────────────────────────────

    private val refusal = InvocationTargetException(
        SecurityException("Neither user 2000 nor current process has android.permission.BYDAUTO_ENERGY_COMMON."),
    )

    private fun viaClassOrService(
        mode: NativeMode,
        car: ClassicCar?,
        kit: () -> DriveModeCommand.Device,
        log: MutableList<String> = ArrayList(),
        work: (DriveModeCommand.Device) -> VehicleDispatchResult = { VehicleDispatchResult.Success() },
    ): VehicleDispatchResult = DriveModeCommand.viaClassOrService(
        mode, OperationModeRoute.DEVICE_CLASS, kit, { car }, { DriveModeCommand.OperationNativeDevice(it, control, state) }, { log += it }, work,
    )

    @Test
    fun theEnergyClassIsUsedWhileTheCarAcceptsIt() {
        var used: DriveModeCommand.Device? = null
        val classDevice = object : DriveModeCommand.Device {
            override val tag = ""
            override fun reportedMode() = 1
            override fun ask(value: Int) = VehicleDispatchResult.Success()
        }

        viaClassOrService(NativeMode.AUTO, ClassicCar(3), { classDevice }) { used = it; VehicleDispatchResult.Success() }

        assertEquals(classDevice, used)
        assertFalse(NativeRoute.isRefused(OperationModeRoute.DEVICE_CLASS))
    }

    @Test
    fun aCarThatRefusesTheEnergyClassIsSpokenToDirectlyAndTheRefusalIsRemembered() {
        var used: DriveModeCommand.Device? = null
        val log = ArrayList<String>()

        viaClassOrService(NativeMode.AUTO, ClassicCar(3), { throw refusal }, log) { used = it; VehicleDispatchResult.Success() }

        assertTrue(used is DriveModeCommand.OperationNativeDevice)
        assertTrue(NativeRoute.isRefused(OperationModeRoute.DEVICE_CLASS))
        // The Setting class's record is its own: the Energy class refusing says nothing about it.
        assertFalse(NativeRoute.isRefused(DriveModeCommand.SETTING_DEVICE_CLASS))
        assertTrue(log.toString(), log.single().contains(OperationModeRoute.DEVICE_CLASS))
    }

    @Test
    fun aCarWhoseEnergyClassRefusesAndWhichHasNoServiceIsToldSoInWords() {
        val result = viaClassOrService(NativeMode.AUTO, null, { throw refusal })

        assertEquals(DriveModeControl.REFUSED_REASON, (result as VehicleDispatchResult.Failure).detail)
        assertEquals(VehicleDispatchError.SECURITY_DENIED, result.error)
    }

    @Test
    fun aRefusalByAnotherDeviceClassDoesNotSendTheClassicRouteToTheService() {
        // Remembering that the Setting class refuses must not make this route skip a class it has not tried.
        NativeRoute.markRefused(DriveModeCommand.SETTING_DEVICE_CLASS)
        var used: DriveModeCommand.Device? = null
        val classDevice = object : DriveModeCommand.Device {
            override val tag = ""
            override fun reportedMode() = 1
            override fun ask(value: Int) = VehicleDispatchResult.Success()
        }

        viaClassOrService(NativeMode.AUTO, ClassicCar(3), { classDevice }) { used = it; VehicleDispatchResult.Success() }

        assertEquals(classDevice, used)
    }

    // ── What the assistant is told ──────────────────────────────────────────────────────────────────

    @Test
    fun theClassicToolOffersTheModesOfTheClassAndNoOthers() {
        val declaration = SetDriveModeTool.declarationFor(DriveModeControl.modesOf(DriveModeRoute.OPERATION_MODE))

        val words = declaration.parameters?.properties?.get("mode")?.enum.orEmpty()
        assertEquals(setOf("eco", "normal", "sport", "snow", "mud", "sand"), words.toSet())
        for (other in listOf("mountain", "rock", "auto", "exit")) assertFalse(other, other in words)
        val text = declaration.description
        for (word in words) assertTrue("$word: $text", word in text)
        // It does not claim that the car has no other modes: the car's table decides what is there.
        assertFalse(text, "no other modes" in text)
        assertTrue(text, "which of these modes it has is its to say" in text)
    }

    @Test
    fun theLeopardsToolIsTheOneItHasAlwaysHad() {
        assertEquals(SetDriveModeTool.declaration, SetDriveModeTool.declarationFor(DriveModeControl.modesOf(DriveModeRoute.SETTING)))
        assertEquals(DriveMode.entries.map { it.word }, SetDriveModeTool.declaration.parameters?.properties?.get("mode")?.enum)
    }

    @Test
    fun theConversationOffersTheToolWithTheModesOfTheCarsRoute() {
        val tools = assistantTools(
            vehicleControlEnabled = true,
            conversation = true,
            driveModes = true,
            driveModeOptions = DriveModeControl.modesOf(DriveModeRoute.OPERATION_MODE),
        )

        val drive = tools.flatMap { it.functionDeclarations.orEmpty() }.single { it.name == SetDriveModeTool.FUNCTION_NAME }
        assertEquals(
            DriveModeControl.modesOf(DriveModeRoute.OPERATION_MODE).map { it.word },
            drive.parameters?.properties?.get("mode")?.enum,
        )
    }

    @Test
    fun withoutOptionsTheConversationOffersEveryMode() {
        val tools = assistantTools(vehicleControlEnabled = true, conversation = true, driveModes = true)

        val drive = tools.flatMap { it.functionDeclarations.orEmpty() }.single { it.name == SetDriveModeTool.FUNCTION_NAME }
        assertEquals(DriveMode.entries.map { it.word }, drive.parameters?.properties?.get("mode")?.enum)
    }
}
