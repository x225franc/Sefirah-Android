package sefirah.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionHeartbeatTest {
    @Test fun idleConnectionGetsOneProbeAndAResponseDeadline() {
        val heartbeat = ConnectionHeartbeat(0)
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(60_000))
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(90_000))
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(100_000))
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(149_999))
        assertEquals(HeartbeatAction.EXPIRED, heartbeat.nextAction(150_000))
    }

    @Test fun receivedTrafficAvoidsRedundantProbesAndClearsPendingDeadline() {
        val heartbeat = ConnectionHeartbeat(0)
        heartbeat.onMessageReceived(60_000)
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(90_000))
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(150_000))
        heartbeat.onMessageReceived(160_000)
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(180_000))
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(249_999))
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(250_000))
    }

    @Test fun wakeAfterLongSleepProbesBeforeDeclaringConnectionDead() {
        val heartbeat = ConnectionHeartbeat(0)
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(3_600_000))
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(3_600_001))
        heartbeat.onMessageReceived(3_600_010)
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(3_660_000))
    }

    @Test fun probePendingBeforeSleepGetsAFreshDeadlineOnWake() {
        val heartbeat = ConnectionHeartbeat(0)
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(90_000))
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(3_600_000))
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(3_659_999))
        assertEquals(HeartbeatAction.EXPIRED, heartbeat.nextAction(3_660_000))
    }

    @Test fun ordinaryWatchdogTicksDoNotPostponeAnUnansweredProbe() {
        val heartbeat = ConnectionHeartbeat(0)
        assertEquals(HeartbeatAction.NONE, heartbeat.nextAction(60_000))
        assertEquals(HeartbeatAction.PROBE, heartbeat.nextAction(120_100))
        assertEquals(HeartbeatAction.EXPIRED, heartbeat.nextAction(180_200))
    }
}
