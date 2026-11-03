package com.xrdavies.mob.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {
    @Test fun aNewQuestionOfTheCurrentTurnFollows() {
        assertTrue(followsQuestion(null, "q1", inCurrentTurn = true))
        assertTrue(followsQuestion("q1", "q2", inCurrentTurn = true))
    }

    @Test fun theSameQuestionOrOneInOlderTurnsDoesNot() {
        assertFalse(followsQuestion("q1", "q1", inCurrentTurn = true))
        assertFalse("loaded above with older turns", followsQuestion(null, "q0", inCurrentTurn = false))
        assertFalse(followsQuestion("q1", null, inCurrentTurn = true))
    }
}
