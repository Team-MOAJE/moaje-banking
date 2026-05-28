package com.moaje.banking.application.service.impl

import com.moaje.banking.application.asset.AssetTransferClient
import com.moaje.banking.application.service.TransferCommandService
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestResult
import com.moaje.banking.domain.transfer.TransferStatus
import org.springframework.stereotype.Service

@Service
class TransferCommandServiceImpl(
    private val assetTransferClient: AssetTransferClient,
) : TransferCommandService {
    /**
     * 사용자 송금 요청을 Asset 서비스에 가승인 요청하고, 실제 금융망 처리는 Kafka 비동기 흐름에 위임합니다.
     */
    override fun request(command: TransferCommand): TransferRequestResult {
        val reservation = assetTransferClient.reserve(command)

        return TransferRequestResult(
            transferId = reservation.transferId,
            publicTransferId = reservation.publicTransferId,
            status = TransferStatus.PROCESSING,
            message = "송금 요청이 접수되었습니다. 금융망 처리 결과를 확인하고 있습니다.",
        )
    }
}
