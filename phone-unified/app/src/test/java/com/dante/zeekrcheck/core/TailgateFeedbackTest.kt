package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test

class TailgateFeedbackTest {
    private val unlock = VehicleCommand.Body(BodyAction.TRUNK_UNLOCK)

    @Test fun unknownLatchUnlockDoesNotRequireAcknowledgementToUseOtherControls() {
        assertFalse(OperationFeedback.requiresAcknowledgement(unlock, CommandResult.UNKNOWN))
        for (action in listOf(BodyAction.LOCK, BodyAction.UNLOCK, BodyAction.TRUNK_LOCK, BodyAction.PORT_CLOSE))
            assertTrue(OperationFeedback.requiresAcknowledgement(VehicleCommand.Body(action), CommandResult.UNKNOWN))
    }

    @Test fun acceptedLatchRequestExplainsTheNextPhysicalStepWithoutClaimingTheTailgateOpened() {
        val message = OperationFeedback.message(unlock, CommandResult.ACCEPTED)
        assertTrue(message.contains("解锁请求已受理"))
        assertTrue(message.contains("未抬起可按车尾按键"))
        assertFalse(message.contains("尾门已开"))
        assertFalse(message.contains("待核实"))
        assertNull(BodyAction.TRUNK_UNLOCK.observation)
    }

    @Test fun uncertainLatchUnlockIsNotPresentedAsAcceptedOrAutomaticallyRetried() {
        val message = OperationFeedback.message(unlock, CommandResult.UNKNOWN)
        assertTrue(message.contains("未确认"))
        assertTrue(message.contains("未重发"))
        assertTrue(message.contains("可继续其他操作"))
        assertFalse(message.contains("已受理"))
    }

    @Test fun interruptedLatchUnlockRecoversAcrossPersistenceWithoutFakingSuccess() {
        val pending = AssistantState(operationPending = true, pendingBodyAction = BodyAction.TRUNK_UNLOCK)
        val recovered = OperationFeedback.recover(AssistantState.parse(pending.encode()))
        assertFalse(recovered.operationPending)
        assertNull(recovered.pendingBodyAction)
        assertTrue(recovered.operationMessage.contains("未确认"))
        assertTrue(recovered.history.isEmpty())
        assertEquals(recovered, OperationFeedback.recover(recovered))
    }

    @Test fun legacyLatchRecoveryOnlyRecognizesExactPendingMessages() {
        for (message in listOf("正在发送：解锁尾门", "解锁尾门 · ${CommandResult.UNKNOWN.label}")) {
            val pending = AssistantState(operationPending = true, operationMessage = message)
            assertFalse(OperationFeedback.recover(pending).operationPending)
        }
        val unrelated = AssistantState(operationPending = true, operationMessage = "上次操作中断，车辆结果待核实；不会重新发送",
            history = listOf(OperationEntry(1, BodyAction.TRUNK_UNLOCK.title, CommandResult.UNKNOWN.label)))
        assertEquals(unrelated, OperationFeedback.recover(unrelated))
        val typedLock = unrelated.copy(pendingBodyAction = BodyAction.LOCK, operationMessage = "正在发送：解锁尾门")
        assertEquals(typedLock, OperationFeedback.recover(typedLock))
    }
}
