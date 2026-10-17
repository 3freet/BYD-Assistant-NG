package com.bydassistantng.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningReplyTest {
    @Test
    fun aGreetingThatEndsInAQuestionAsksSomething() {
        assertTrue(OpeningReply.asksSomething("Good morning! Where are we going today?"))
        assertTrue(OpeningReply.asksSomething("صباح الخير، إلى أين نذهب؟"))
    }

    @Test
    fun trailingSpaceAndQuotesDoNotHideTheQuestion() {
        assertTrue(OpeningReply.asksSomething("Where to? \n"))
        assertTrue(OpeningReply.asksSomething("Did you say \"home?\" "))
        assertTrue(OpeningReply.asksSomething("Shall we go (home?)"))
    }

    @Test
    fun aStatementAsksNothing() {
        assertFalse(OpeningReply.asksSomething("Good afternoon. It is 31 degrees outside."))
        assertFalse(OpeningReply.asksSomething("Good evening, it is 22 degrees outside!"))
    }

    @Test
    fun aQuestionBeforeTheEndIsNotTheEnd() {
        assertFalse(OpeningReply.asksSomething("Is it hot? It is 40 degrees outside."))
    }

    @Test
    fun nothingSaidAsksNothing() {
        assertFalse(OpeningReply.asksSomething(""))
        assertFalse(OpeningReply.asksSomething("   "))
    }
}
