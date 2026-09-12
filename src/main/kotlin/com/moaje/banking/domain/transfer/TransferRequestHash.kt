package com.moaje.banking.domain.transfer

import com.moaje.banking.domain.common.Money
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

/**
 * requestHash는 원래 String 타입이지만, 개발자의 실수로 파라미터 순서가 바뀌거나 해시 값이 아닌 잘못된 값이 들어가는것을 방지하기 위해
 * 전용 class로 wrapping 해서 만들게 되었다. 여기에, 수많은 송금 요청에 검증을 위해 자주 사용되는 만큼
 * data class 나 normal class 로 만들어서 처리한다면 JVM Heap 메모리에 많은 인스턴스가 쌓이게 되니,
 * value class로 만들어서 실제 컴파일에는 UnWrapping 하여 String 으로 넣게하여 오버헤드 발생을 줄일 수 있도록 한다.
  */

@JvmInline
value class TransferRequestHash(val value: String) {
    init {
        require(value.isNotBlank()) { "요청 해시는 비어 있을 수 없습니다." }
    }

    companion object {
        fun from(command: TransferCommand): TransferRequestHash {

            // 하나의 금융업무 자체에 필요한 최소한의 데이터를 모아서 SHA-256으로 해싱한다.
            val transferInfoText = listOf(
                "operationType=${OperationType.BANKING_TRANSFER.name}",
                "withdrawalAccountId=${command.withdrawalAccountId}",
                "depositBankCode=${command.depositBankCode.trim().uppercase()}",
                "depositAccountNumber=${command.depositAccountNumber.trim()}",
                "amount=${command.amount.normalizedAmount()}",
                "currency=${command.amount.currency.trim().uppercase()}",
            ).joinToString("\n")

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(transferInfoText.toByteArray(Charsets.UTF_8))
            return TransferRequestHash(HexFormat.of().formatHex(digest))
        }

        private fun Money.normalizedAmount(): String {
            return amount.stripTrailingZeros().toPlainString()
        }
    }
}
