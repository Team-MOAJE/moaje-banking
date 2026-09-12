package com.moaje.banking.application.service

import com.moaje.banking.domain.transfer.BankingTransfer
import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.domain.transfer.OperationType
import com.moaje.banking.repository.transfer.BankingTransferRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

@DisplayName("Asset Projection용 Banking 거래 상태 조회")
class TransferProjectionQueryServiceTest {
    private val repository = mock(BankingTransferRepository::class.java)
    private val service = TransferProjectionQueryService(repository)

    @Test
    @DisplayName("요청한 계좌의 거래 상태와 외부 거래 식별자를 반환한다")
    fun returnsTransferStateForRequestedAccount() {
        // given: Asset이 복구하려는 출금 계좌와 Banking Journal의 계좌가 일치한다.
        val transfer = transfer(transferId = 10L, accountId = 20L).apply {
            status = BankingTransferStatus.REVERSED
            externalTransactionId = "EXT-10"
            externalReversalTransactionId = "REV-10"
        }
        `when`(repository.findAllById(listOf(10L))).thenReturn(listOf(transfer))

        // when
        val result = service.getStates(20L, listOf(10L))

        // then: Projection 복구에 필요한 식별자만 반환하고 실제 계좌번호는 계약에 포함하지 않는다.
        assertThat(result.single().status).isEqualTo(BankingTransferStatus.REVERSED)
        assertThat(result.single().externalTransactionId).isEqualTo("EXT-10")
        assertThat(result.single().externalReversalTransactionId).isEqualTo("REV-10")
    }

    @Test
    @DisplayName("다른 출금 계좌의 transferId를 조회하면 거부한다")
    fun rejectsTransferOwnedByDifferentAccount() {
        // given
        `when`(repository.findAllById(listOf(10L))).thenReturn(listOf(transfer(10L, 99L)))

        // when & then: transferId 추측만으로 다른 계좌의 금융 상태를 읽을 수 없어야 한다.
        assertThatThrownBy { service.getStates(20L, listOf(10L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("출금 계좌가 일치하지 않습니다")
    }

    private fun transfer(transferId: Long, accountId: Long): BankingTransfer = BankingTransfer(
        transferId = transferId,
        principalId = "user-1",
        operationType = OperationType.BANKING_TRANSFER,
        idempotencyKey = "idem-$transferId",
        requestHash = "a".repeat(64),
        withdrawalAccountId = accountId,
        depositBankCode = "088",
        depositAccountNumber = "encrypted-target",
        amount = BigDecimal("1000.0000"),
        currency = "KRW",
    )
}
