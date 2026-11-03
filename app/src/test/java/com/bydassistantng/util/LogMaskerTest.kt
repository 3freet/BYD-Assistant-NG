package com.bydassistantng.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMaskerTest {
    private val stamp = "2026-10-30 14:03:22.123"

    private fun entry(tag: String, message: String) = "$stamp [$tag] $message"

    private fun masked(tag: String, message: String) = LogMasker.mask(entry(tag, message))

    private fun unchanged(tag: String, message: String) = assertEquals(entry(tag, message), masked(tag, message))

    /** What takes the place of [text]. */
    private fun gone(text: String) = "<hidden: ${text.length} chars>"

    // ── What the user said and asked for ─────────────────────────────────────────────────────────

    @Test
    fun whatTheAssistantHeardIsHiddenButItsLengthIsKept() {
        val said = "open the windows please now"
        assertEquals(
            entry("LiveConversationController", "heard: \"${gone(said)}\""),
            masked("LiveConversationController", "heard: \"$said\""),
        )
    }

    @Test
    fun aSentenceThatHoldsQuotesIsHiddenToTheLastOne() {
        val result = masked("LiveConversationController", """heard: "she said "meet me at 5" and left"""")
        assertFalse(result, "meet" in result)
        assertTrue(result, result.endsWith("\"")) 
    }

    @Test
    fun aTranscriptThatRunsOverSeveralLinesIsHiddenWhole() {
        val result = LogMasker.mask(entry("LiveConversationController", "heard: \"first line\nsecond line\"") + "\n" + entry("X", "next"))
        assertFalse(result, "first" in result || "second" in result)
        assertTrue(result, result.endsWith(entry("X", "next")))
    }

    @Test
    fun placesSongsAndSearchesAreHidden() {
        val place = "12 Elm Street, Boston"
        assertEquals(
            entry("NavigationTool", "Started navigation to \"${gone(place)}\" in Google Maps"),
            masked("NavigationTool", "Started navigation to \"$place\" in Google Maps"),
        )
        val song = "Bohemian Rhapsody"
        assertEquals(
            entry("PlayMediaTool", "Asked Spotify to play \"${gone(song)}\" (song)"),
            masked("PlayMediaTool", "Asked Spotify to play \"$song\" (song)"),
        )
        val search = masked("YouTubeVideoFinder", """Search for "cat videos" -> dQw4w9WgXcQ "Funny cats 10 hours"""")
        assertFalse(search, "cat" in search || "Funny" in search)
    }

    @Test
    fun whatIsSpokenOutsideTheCarIsHidden() {
        val result = masked("LiveConversationController", """Speaking outside the car (medium): "Hello neighbour, move your car"""")
        assertFalse(result, "neighbour" in result)
        assertTrue(result, "Speaking outside the car (medium)" in result)
    }

    @Test
    fun theEngineTextToSpeechIsGivenIsHidden() {
        val result = masked("TextToSpeechEngine", """speak(): locale=pl_PL, isLanguageAvailable=0, setLanguage=0, text="Dzień dobry Anno"""")
        assertFalse(result, "Anno" in result)
        assertTrue(result, "locale=pl_PL" in result)
    }

    @Test
    fun screenLabelsTheAppPressedAreHidden() {
        val label = "Daily Mix 1 - Me"
        assertEquals(
            entry("UiPlayStarter", "Pressing the first result of com.spotify.music: ${gone(label)}"),
            masked("UiPlayStarter", "Pressing the first result of com.spotify.music: $label"),
        )
    }

    // ── Tool calls ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCarsOwnControlsAreLoggedWithTheirArguments() {
        unchanged("LiveConversationController", """Tool call: window({"setting":"driver","value":"open"})""")
        unchanged("ConversationController", """[turn 3] function call: seat({"setting":"driver.heating","value":"high"})""")
        unchanged("LiveConversationController", """Tool call: read_car({"what":"tyres"})""")
    }

    @Test
    fun theArgumentsOfAnyOtherToolAreHiddenButItsNameAndKeysStay() {
        assertEquals(
            entry("LiveConversationController", """Tool call: navigate({"destination":"${gone("Home")}","app":"${gone("Waze")}","walking":true,"stops":3})"""),
            masked("LiveConversationController", """Tool call: navigate({"destination":"Home","app":"Waze","walking":true,"stops":3})"""),
        )
        assertEquals(
            entry("LiveConversationController", """Tool call: send_message({"to":["${gone("Anna")}","${gone("Bob")}"]})"""),
            masked("LiveConversationController", """Tool call: send_message({"to":["Anna","Bob"]})"""),
        )
    }

    // ── The car's side of the log ────────────────────────────────────────────────────────────────

    @Test
    fun theStepsOfTheCarAndItsAnswersAreKept() {
        unchanged("ConversationShared", "dispatch window.driver(value=open -> 1) -> Failure(error=INVOCATION_FAILED, detail=HAL returned error code -10011)")
        unchanged("ShellHelperVehicle", "Helper: window.driver.position 40")
        unchanged("ShellHelperVehicle", "Helper saw: sdk=33 release=13 constants=6432 classes=bodywork,light,audio,setting,ac calls=base,set:AbsBYDAutoDevice,get:AbsBYDAutoDevice services=autoservice,byd_airconditioning missing=none")
        unchanged("ShellHelperVehicle", "Helper ready in 1387ms")
    }

    @Test
    fun anErrorBodyFromTheServerKeepsItsMessage() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        unchanged("GeminiClient", "<- HTTP 400 after 312ms — $body")
    }

    @Test
    fun aPrettyPrintedErrorBodyThatRunsOverManyLinesIsKeptWhole() {
        // As Google really answers a key it does not know: the entry is the header line and the lines of the body.
        val log = entry("GeminiClient", "<- HTTP 400 after 1235ms — {") + "\n" +
            """  "error": {
    "code": 400,
    "message": "API key not valid. Please pass a valid API key.",
    "status": "INVALID_ARGUMENT",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "API_KEY_INVALID",
        "domain": "googleapis.com",
        "metadata": {
          "service": "generativelanguage.googleapis.com"
        }
      }
    ]
  }
}
""" + "\n" + entry("ConversationController", "[turn 11769] first call failed after 1266ms: INVALID_API_KEY")
        assertEquals(log, LogMasker.mask(log))
    }

    @Test
    fun theTextFieldsOfAServerMessageAreHidden() {
        val result = masked(
            "GeminiLiveClient",
            """Failed to parse server message: {"serverContent":{"inputTranscription":{"text":"call my wife"},"turnComplete":true}}""",
        )
        assertFalse(result, "wife" in result)
        assertTrue(result, """"turnComplete":true""" in result)
        assertTrue(result, """"text":"<hidden: 12 chars>"""" in result)
    }

    @Test
    fun aHostNameInAnErrorIsKept() {
        val log = entry("GeminiClient", "Network error after 120ms") + "\n" +
            "java.net.UnknownHostException: Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname\n" +
            "\tat java.net.InetAddress.lookupHostByName(InetAddress.java:1)\n"
        assertEquals(log, LogMasker.mask(log))
    }

    @Test
    fun theCarsOwnPromptQuotedByTheAdbCheckIsKept() {
        unchanged("AdbHelper", "Checking that ADB is allowed (the car may ask \"Allow USB debugging?\" — waiting up to 60 s for the answer)")
    }

    // ── Secrets ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anApiKeyIsHiddenWhereverItAppears() {
        // Built from pieces so that no file holds a whole key-shaped string (secret scanners cannot tell a sample from a key).
        val key = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
        val result = masked("GeminiLiveClient", "WS failure (http=403) for wss://generativelanguage.googleapis.com/ws/x?key=$key")
        assertFalse(result, key in result || "AIza" in result)
        assertTrue(result, "wss://generativelanguage.googleapis.com/ws/x?key=<hidden:key>" in result)
        assertFalse(masked("X", "stored $key in the field").contains(key))
    }

    @Test
    fun tokensAndPasswordsAreHidden() {
        val bearer = masked("UpdateManager", "Authorization: Bearer abcdef1234567890abcdef")
        assertFalse(bearer, "abcdef1234567890" in bearer)
        val jwt = masked("X", "got eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertTrue(jwt, "<hidden:token>" in jwt)
        val field = masked("X", """{"password":"hunter2hunter2","user":"x"}""")
        assertFalse(field, "hunter2" in field)
    }

    @Test
    fun theWordsAuthorizationAndTokenAloneAreNotTakenForSecrets() {
        unchanged("WheelKeyService", "authorization requested for the wheel button")
        unchanged("GeminiClient", "tokenCount=1234 promptTokens=88")
    }

    // ── Identifiers ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun addressesNumbersAndIdsAreHiddenWithTheirKind() {
        assertEquals(entry("AdbHelper", "connected to <hidden:ip>:5555"), masked("AdbHelper", "connected to 192.168.1.20:5555"))
        assertEquals(entry("X", "mac <hidden:mac> seen"), masked("X", "mac 3C:5A:B4:01:02:03 seen"))
        assertEquals(entry("X", "mail <hidden:email> bounced"), masked("X", "mail anna.k+car@example.co.uk bounced"))
        assertEquals(entry("X", "at <hidden:location>"), masked("X", "at 52.229676, 21.012229"))
        assertEquals(entry("X", "lat=<hidden:location> lon=<hidden:location>"), masked("X", "lat=52.2297 lon=21.0122"))
        assertEquals(entry("X", "call <hidden:phone> now"), masked("X", "call +48 22 555 01 00 now"))
        assertEquals(entry("X", "call <hidden:phone> now"), masked("X", "call 415-555-0100 now"))
        assertEquals(entry("X", "car <hidden:vin>"), masked("X", "car LGXC16DG2P0123456"))
        assertEquals(entry("X", "project projects/<hidden> over quota"), masked("X", "project projects/123456789012 over quota"))
        assertEquals(entry("X", "open https://www.youtube.com/watch?<hidden>"), masked("X", "open https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
    }

    @Test
    fun aVersionOfFourPartsIsNotAnAddress() {
        unchanged("ShellHelperVehicle", "Helper saw: props=vehicle.type:Di5.1,byd.dilink.version:5.1.0.8,product.model:DiLink_5.1")
        assertEquals(entry("X", "from <hidden:ip>"), masked("X", "from 10.0.0.12"))
    }

    @Test
    fun longBlobsOfDataAreHiddenWithTheirSize() {
        val blob = "QUJD".repeat(60)
        val result = masked("X", "chunk $blob end")
        assertEquals(entry("X", "chunk <hidden:data> end"), result)
    }

    @Test
    fun theCarsNumbersAndOrdinaryFiguresAreNotTakenForIdentifiers() {
        unchanged("ShellHelperVehicle", "Helper saw: sdk=29 release=10 constants=6432 missing=window.driver.position:1276219408,window.driver.position:947912728")
        unchanged("ShellHelperVehicle", "windows=BODYWORK_LF_WINDOW_CTRL_SET=1125122104,BODYWORK_LF_WINDOW_TARGET_POSITION_SET=1276219408")
        unchanged("CabinNoise", "Cabin noise now -47.3 dBFS in the speech band, peak 0.1234")
        unchanged("VehicleController", "STUB dispatch: window.driver (deviceType=1001, featureId=0x43100038) <- 1")
        unchanged("Updater", "Updated to 1.2.0-beta.3 from 1.2.0-beta.2 in 12.5 s")
    }

    // ── The log as a whole ───────────────────────────────────────────────────────────────────────

    @Test
    fun aLogWithNothingPrivateInItComesBackAsItWas() {
        val log = listOf(
            entry("AssistantApp", "Running as another Android user (not the main one) — no background work"),
            entry("ShellHelperVehicle", "Helper: window.driver 1"),
            "",
        ).joinToString("\n")
        assertEquals(log, LogMasker.mask(log))
    }

    @Test
    fun theEmptyLogAndTheNoEntriesNoticeSurvive() {
        assertEquals("", LogMasker.mask(""))
        assertEquals("No log entries yet.", LogMasker.mask("No log entries yet."))
    }

    @Test
    fun aCrashEntryKeepsItsThreadAndStackButNotTheTextInItsMessage() {
        val crash = "=== 2026-10-30 14:03:22 — thread \"DefaultDispatcher-worker-1\" ===\n" +
            "java.lang.IllegalStateException: no match for \"call Anna on +48 22 555 01 00\"\n" +
            "\tat com.bydassistantng.Foo.bar(Foo.kt:12)\n\n"
        val result = LogMasker.mask(crash)
        assertTrue(result, "thread \"DefaultDispatcher-worker-1\"" in result)
        assertFalse(result, "Anna" in result || "555" in result)
        assertTrue(result, "at com.bydassistantng.Foo.bar(Foo.kt:12)" in result)
        assertTrue(result, result.endsWith("\n\n"))
    }

    @Test
    fun entriesAreMaskedIndependentlyOfEachOther() {
        val log = entry("A", "heard: \"one\"") + "\n" + entry("B", "plain line") + "\n" + entry("C", "heard: \"two\"") + "\n"
        val result = LogMasker.mask(log)
        assertEquals(
            entry("A", "heard: \"<hidden: 3 chars>\"") + "\n" + entry("B", "plain line") + "\n" + entry("C", "heard: \"<hidden: 3 chars>\"") + "\n",
            result,
        )
    }

    @Test
    fun aLargeLogIsMaskedInReasonableTime() {
        val line = entry("LiveConversationController", """heard: "open the windows and tell Anna I will call her at 415-555-0100"""")
        val plain = entry("ShellHelperVehicle", "Helper: window.driver 1")
        val log = (0 until 6000).joinToString("\n") { if (it % 3 == 0) line else plain }

        val started = System.nanoTime()
        val result = LogMasker.mask(log)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertFalse(result.contains("Anna"))
        // Generous: the point is that it is nowhere near the several seconds a naive masker would take on a head unit.
        assertTrue("took ${tookMs}ms", tookMs < 5_000)
    }
}
