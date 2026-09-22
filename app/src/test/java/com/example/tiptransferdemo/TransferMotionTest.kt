package com.yunsi.tiptransferdemo

import org.junit.Assert.*
import org.junit.Test

class TransferMotionTest {
    @Test fun arrivalEndsAtTheExactRestingPoseForEverySlot() {
        for (index in 0L..35L) for (flower in listOf(false, true)) {
            assertEquals(restingPose(index, flower), arrivalPose(index, 1f, flower))
            assertEquals(restingPose(index, flower), arrivalPose(index, 5f, flower))
        }
    }

    @Test fun motionDoesNotJumpAcrossArrivalAndHoldBoundaries() {
        for (boundary in listOf(.38f, .53f)) {
            val before = arrivalPose(7L, boundary - .00001f)
            val after = arrivalPose(7L, boundary + .00001f)
            assertEquals(before.x, after.x, .001f)
            assertEquals(before.y, after.y, .001f)
            assertEquals(before.scale, after.scale, .001f)
        }
    }

    @Test fun allVisibleTokensHaveDifferentRestingPositions() {
        val positions = (0L until VISIBLE_TOKEN_LIMIT).map { restingPose(it).let { p -> p.x to p.y } }
        assertEquals(VISIBLE_TOKEN_LIMIT, positions.toSet().size)
        assertEquals(restingPose(0L), restingPose(VISIBLE_TOKEN_LIMIT.toLong()))
    }

    @Test fun arrivalMovesDownwardAndAlwaysHasAPositiveScale() {
        var previousY = Float.POSITIVE_INFINITY
        for (step in 0..1000) {
            val pose = arrivalPose(11L, step / 1000f)
            assertTrue(pose.y <= previousY + .00001f)
            assertTrue(pose.scale > 0f)
            assertTrue(pose.x.isFinite() && pose.y.isFinite() && pose.turn.isFinite())
            previousY = pose.y
        }
    }
}
