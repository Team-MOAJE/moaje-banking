package com.moaje.banking.domain.transfer

import com.moaje.banking.domain.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

@DisplayName("Banking 송금 REVERSED 상태 전이")
class BankingTransferReversedStateTest {
    @Test
    @DisplayName("대사 대상인 UNKNOWN 거래는 이미 확인된 REVERSED 결과로 전이할 수 있다")
    fun unknownCanBecomeReversedWhenExternalResultIsConfirmed() {
        // given: 외부 응답을 받지 못해 UNKNOWN이지만 대사 가능한 송금이 있다.
        val transfer = newTransfer()
        transfer.markProcessing()
        transfer.markUnknown("TIMEOUT", "응답 유실")
        val reversedAt = LocalDateTime.of(2026, 9, 7, 0, 0)

        // when: Mock Banking 강한 일관성 조회에서 이미 완료된 보상 결과를 확인한다.
        transfer.markReversed("EXT-1", "REV-1", "외부 보상 확인", reversedAt)

        // then: 원거래와 보상거래 식별자를 분리해 보존하고 대사 대상에서 빠진다.
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.REVERSED)
        assertThat(transfer.externalTransactionId).isEqualTo("EXT-1")
        assertThat(transfer.externalReversalTransactionId).isEqualTo("REV-1")
        assertThat(transfer.reversedAt).isEqualTo(reversedAt)
        assertThat(transfer.reconciliationRequired).isFalse()
    }

    @Test
    @DisplayName("업무 정책이 없는 REQUESTED 거래에서는 REVERSED 전이를 허용하지 않는다")
    fun requestedCannotStartReversal() {
        // given: 아직 외부 송금조차 시작하지 않은 REQUESTED 거래가 있다.
        val transfer = newTransfer()

        // when & then: 현재 구현은 보상 실행 유스케이스가 아니므로 임의의 보상 전이를 차단한다.
        assertThatThrownBy { transfer.markReversed("EXT-1", "REV-1", "허용되지 않은 보상") }
            .isInstanceOf(InvalidTransferStateTransitionException::class.java)
    }

    private fun newTransfer(): BankingTransfer {
        val command = TransferCommand(
            principalId = PrincipalId("user-1"),
            idempotencyKey = "idem-1",
            withdrawalAccountId = 10L,
            depositBankCode = "088",
            depositAccountNumber = "test-destination",
            amount = Money(BigDecimal("1000.0000")),
        )
        return BankingTransfer.create(
            transferId = 1L,
            principalId = command.principalId,
            idempotencyKey = command.idempotencyKey,
            requestHash = TransferRequestHash.from(command),
            command = command,
        )
    }
}
