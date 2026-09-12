package com.moaje.banking.common.restclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.banking.common.bank.MockBankingAccountResponse
import com.moaje.banking.common.bank.MockBankingAccountSnapshotResponse
import com.moaje.banking.common.bank.ResponseToken
import com.moaje.banking.common.bank.TransferLookupResponse
import com.moaje.banking.common.bank.TransferResponse
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.transfer.TransferCommand
import org.springframework.http.HttpStatus
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 목업 금융망 API와 통신하는 HTTP Client입니다.
 *
 * 역할:
 * - 목업뱅킹 OAuth 토큰 발급 및 Redis 캐싱
 * - 계좌개설 API 호출
 * - 송금 API 호출
 * - 송금 timeout 발생 시 UNKNOWN 결과 반환
 *
 * 트러블 슈팅 포인트:
 * - timeout은 "실패"가 아니라 "응답을 받지 못한 상태"입니다.
 * - 목업 금융망 서버가 요청을 처리한 뒤 응답만 늦게 온 것일 수 있으므로, timeout은 실패로 확정하지 않습니다.
 * - UNKNOWN 거래는 clientTransferId 결과 조회와 Reconciliation Worker가 별도로 복구합니다.
 *
 * Kotlin 포인트:
 * - 주 생성자에 의존성을 선언하면 Spring이 생성자 주입을 수행합니다.
 * - `private val`은 클래스 내부에서만 읽을 수 있는 불변 프로퍼티입니다.
 * - nullable 값은 `?:` Elvis 연산자로 기본 메시지를 안전하게 채웁니다.
 */
@Component
class MockBankingApiClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    private val properties: BankingExternalApiProperties,
) {
    /**
     * 목업뱅킹 서버에 계좌개설을 요청합니다.
     *
     * 세밀하게 봐야 할 부분:
     * - token은 CI 기준으로 Redis에 캐시합니다. 추후 CI를 Redis key에 직접 쓰지 않도록 hash/user opaque id로 개선해야 합니다.
     * - 요청 본문은 HMAC 서명에 그대로 사용되므로, 서명한 문자열과 실제 body 문자열이 같아야 합니다.
     */
    fun openAccount(command: OpenAccountCommand): MockBankingAccountResponse {
        val token = getOrFetchToken(command.ci, command.userName, command.phoneNumber)
        val requestBody = buildOpenAccountRequestPayload(command)

        return restClient.post()
            .uri("api/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .headers {
                it.setBearerAuth(token.accessToken)
                it.set(MOCK_SIGNATURE_HEADER, sign(requestBody))
            }
            .body(requestBody)
            .retrieve()
            .body<MockBankingAccountResponse>()
            ?: throw IllegalStateException("목업 뱅킹 API 계좌개설 응답이 비어 있습니다.")
    }

    /**
     * 목업 금융망에 실제 송금을 요청합니다. (token에 필요한 값은 추후 Auth 서비스에서 연동하여 가져와야 할듯 합니다.)
     */
    fun transferRequest(
        transferId: Long,
        command: TransferCommand,
        withdrawalProviderAccountId: String,
    ): MockBankingTransferResult {
        val token = getOrFetchToken(
            properties.transferAuthCi,
            properties.transferAuthUserName,
            properties.transferAuthPhoneNumber,
        )
        return transferExecute(transferId, command, withdrawalProviderAccountId, token.accessToken)
    }

    /**
     * providerAccountId로 계정계의 기준 잔액과 cursor 이후 거래내역을 같은 Snapshot으로 조회한다.
     * 계좌번호 대신 불투명 식별자를 사용하며, 응답은 Banking Application 계층에서 다시 최소 정보로 축소한다.
     */
    fun fetchAccountSnapshot(
        providerAccountId: String,
        cursor: Instant,
    ): MockBankingAccountSnapshotResponse {
        val token = getOrFetchToken(
            properties.transferAuthCi,
            properties.transferAuthUserName,
            properties.transferAuthPhoneNumber,
        )
        return restClient.get()
            .uri { builder ->
                builder.path("api/accounts/providers/{providerAccountId}/snapshot")
                    .queryParam("cursor", cursor.toString())
                    .build(providerAccountId)
            }
            .headers {
                it.setBearerAuth(token.accessToken)
                it.set(MOCK_SIGNATURE_HEADER, sign(EMPTY_BODY))
            }
            .retrieve()
            .body<MockBankingAccountSnapshotResponse>()
            ?: throw IllegalStateException("목업 뱅킹 API 계좌 Snapshot 응답이 비어 있습니다.")
    }

    fun lookupTransferResult(transferId: Long): MockBankingTransferResult {
        val token = getOrFetchToken(
            properties.transferAuthCi,
            properties.transferAuthUserName,
            properties.transferAuthPhoneNumber,
        )
        val requestPayload = objectMapper.writeValueAsString(mapOf("clientTransferId" to transferId.toString()))

        return try {
            val response = restClient.get()
                .uri("api/transfers/{clientTransferId}", transferId.toString())
                .headers {
                    it.setBearerAuth(token.accessToken)
                    it.set(MOCK_SIGNATURE_HEADER, sign(EMPTY_BODY))
                }
                .retrieve()
                .body<TransferLookupResponse>()
                ?: throw IllegalStateException("목업 뱅킹 API 송금 조회 응답이 비어 있습니다.")

            when (response.status) {
                "SUCCEEDED" -> MockBankingTransferResult(
                    status = KftcApiLogStatus.SUCCESS,
                    requestPayload = requestPayload,
                    responseCode = SUCCESS_RESPONSE_CODE,
                    externalTransactionId = response.debitTransactionId,
                )
                "REVERSED" -> MockBankingTransferResult(
                    status = KftcApiLogStatus.REVERSED,
                    requestPayload = requestPayload,
                    responseCode = SUCCESS_RESPONSE_CODE,
                    externalTransactionId = response.debitTransactionId,
                    externalReversalTransactionId = response.debitReversalTransactionId,
                    reversedAt = response.reversedAt,
                    reversalReason = "Mock Banking 대사에서 이미 완료된 보상 거래를 확인했습니다.",
                )
                else -> MockBankingTransferResult(
                    status = KftcApiLogStatus.TIMEOUT,
                    requestPayload = requestPayload,
                    responseCode = "UNKNOWN_LOOKUP_STATUS",
                    failureReason = "알 수 없는 목업 뱅킹 송금 조회 상태입니다. status=${response.status}",
                )
            }
        } catch (exception: RestClientResponseException) {
            if (exception.statusCode == HttpStatus.NOT_FOUND) {
                MockBankingTransferResult(
                    status = KftcApiLogStatus.FAILED,
                    requestPayload = requestPayload,
                    responseCode = "404",
                    failureReason = "Mock Banking에 clientTransferId에 해당하는 송금 결과가 없습니다.",
                )
            } else {
                MockBankingTransferResult(
                    status = KftcApiLogStatus.TIMEOUT,
                    requestPayload = requestPayload,
                    responseCode = exception.statusCode.value().toString(),
                    failureReason = exception.responseBodyAsString.ifBlank { exception.message },
                )
            }
        } catch (exception: ResourceAccessException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.TIMEOUT,
                requestPayload = requestPayload,
                responseCode = "LOOKUP_TIMEOUT",
                failureReason = exception.message ?: "목업 뱅킹 API 송금 조회 timeout이 발생했습니다.",
            )
        } catch (exception: RestClientException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.TIMEOUT,
                requestPayload = requestPayload,
                responseCode = "LOOKUP_UNAVAILABLE",
                failureReason = exception.message ?: "목업 뱅킹 API 송금 조회에 실패했습니다.",
            )
        }
    }

    /**
     * 목업 금융망 접근 토큰을 새로 발급받습니다.
     */
    fun fetchNewToken(ci: String, userName: String, phoneNumber: String): ResponseToken {
        val authBody = objectMapper.writeValueAsString(
            mapOf(
                "ci" to ci,
                "userName" to userName,
                "phoneNumber" to phoneNumber,
            ),
        )

        val responseToken = restClient.post()
            .uri("oauth/2.0/token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(authBody)
            .retrieve()
            .body<ResponseToken>() ?: throw IllegalStateException("목업 뱅킹 API 토큰 응답이 비어 있습니다.")

        val safeExpiresInSeconds = (responseToken.expiresIn - TOKEN_REFRESH_MARGIN_SECONDS)
            .coerceAtLeast(MIN_TOKEN_TTL_SECONDS)
        val absoluteExpiration = System.currentTimeMillis() + (safeExpiresInSeconds * 1000L)

        return responseToken.copy(absoluteExpiration = absoluteExpiration)
    }

    /**
     * Redis 캐시에서 토큰을 우선 사용하고, 없거나 만료되었으면 새로 발급받습니다.
     *
     * Kotlin 포인트:
     * - `copy(...)`는 data class의 일부 필드만 바꿔 새 객체를 만들 때 사용합니다.
     * - `takeIf`와 비슷하게 조건을 만족할 때만 값을 이어가는 패턴을 자주 씁니다.
     */
    private fun getOrFetchToken(ci: String, userName: String, phoneNumber: String): ResponseToken {
        val redisKey = getTokenKey(ci)
        val cachedToken = getTokenByRedis(ci)

        if (cachedToken != null && cachedToken.absoluteExpiration > System.currentTimeMillis()) {
            extendTokenTtl(redisKey, cachedToken, 1, TimeUnit.DAYS)
            return cachedToken
        }

        if (cachedToken != null) {
            redisTemplate.delete(redisKey)
        }

        val newToken = fetchNewToken(ci, userName, phoneNumber)
        redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(newToken), 3, TimeUnit.DAYS)
        return newToken
    }

    private fun getTokenByRedis(ci: String): ResponseToken? {
        val redisKey = getTokenKey(ci)
        return redisTemplate.opsForValue().get(redisKey)
            ?.let { readCachedToken(redisKey, it) }
    }

    private fun readCachedToken(redisKey: String, cachedJson: String): ResponseToken? {
        return runCatching {
            objectMapper.readValue(cachedJson, ResponseToken::class.java)
        }.getOrElse {
            redisTemplate.delete(redisKey)
            null
        }
    }

    private fun extendTokenTtl(
        key: String,
        cachedToken: ResponseToken,
        amount: Long,
        timeUnit: TimeUnit,
    ) {
        val currentTtlInSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        if (currentTtlInSeconds <= 0) {
            return
        }

        val requestedTtlSeconds = currentTtlInSeconds + timeUnit.toSeconds(amount)
        val remainingRealTtlSeconds = (cachedToken.absoluteExpiration - System.currentTimeMillis()) / 1000L
        val finalTtlInSeconds = minOf(requestedTtlSeconds, remainingRealTtlSeconds)

        if (finalTtlInSeconds > 0) {
            redisTemplate.expire(key, finalTtlInSeconds, TimeUnit.SECONDS)
        }
    }

    /**
     * 송금 요청을 실제로 전송하고 결과를 Banking 내부 상태로 변환합니다.
     */
    private fun transferExecute(
        transferId: Long,
        command: TransferCommand,
        withdrawalProviderAccountId: String,
        accessToken: String,
    ): MockBankingTransferResult {
        val transferBody = buildTransferRequestPayload(transferId, command, withdrawalProviderAccountId)
        val auditPayload = buildTransferAuditPayload(transferId, command)

        return try {
            val transferResponse = restClient.post()
                .uri("api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .headers {
                    it.setBearerAuth(accessToken)
                    it.set(MOCK_SIGNATURE_HEADER, sign(transferBody))
                }
                .body(transferBody)
                .retrieve()
                .body<TransferResponse>() ?: throw IllegalStateException("목업 뱅킹 API 송금 응답이 비어 있습니다.")

            MockBankingTransferResult(
                status = KftcApiLogStatus.SUCCESS,
                requestPayload = auditPayload,
                responseCode = SUCCESS_RESPONSE_CODE,
                externalTransactionId = transferResponse.debitHistory.transactionId,
            )
        } catch (exception: ResourceAccessException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.TIMEOUT,
                requestPayload = auditPayload,
                responseCode = "TIMEOUT",
                failureReason = exception.message ?: "목업 뱅킹 API 송금 요청 timeout이 발생했습니다.",
            )
        } catch (exception: RestClientResponseException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.FAILED,
                requestPayload = auditPayload,
                responseCode = exception.statusCode.value().toString(),
                failureReason = exception.responseBodyAsString.ifBlank { exception.message },
            )
        } catch (exception: RestClientException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.FAILED,
                requestPayload = auditPayload,
                failureReason = exception.message ?: "목업 뱅킹 API 송금 요청에 실패했습니다.",
            )
        }
    }

    private fun buildTransferRequestPayload(
        transferId: Long,
        command: TransferCommand,
        withdrawalProviderAccountId: String,
    ): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "clientTransferId" to transferId.toString(),
                // 실제 계좌번호는 계정계인 Mock Banking만 해석하며 Banking에는 다시 노출하지 않는다.
                "fromProviderAccountId" to withdrawalProviderAccountId,
                "toAccountNumber" to command.depositAccountNumber,
                "amount" to command.amount.amount.toLong(),
            ),
        )
    }

    /**
     * 외부 전송 본문에는 providerAccountId와 수취 계좌가 필요하지만 감사 로그에는 재사용 가능한 계좌 식별자를 남기지 않는다.
     * 장애 추적에 필요한 거래 ID와 금액·통화만 별도 Payload로 만들어 KFTC API 로그에 저장한다.
     */
    private fun buildTransferAuditPayload(transferId: Long, command: TransferCommand): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "clientTransferId" to transferId.toString(),
                "amount" to command.amount.amount.toLong(),
                "currency" to command.amount.currency,
            ),
        )
    }

    private fun buildOpenAccountRequestPayload(command: OpenAccountCommand): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "bankCode" to command.bankCode,
                "productName" to command.productName,
                "initialBalance" to command.initialBalance,
            ),
        )
    }

    /**
     * 요청 본문에 대한 HMAC-SHA256 서명을 생성합니다.
     *
     * Kotlin 포인트:
     * - `joinToString("") { ... }`는 컬렉션/배열을 문자열로 접을 때 많이 쓰는 표준 함수입니다.
     */
    private fun sign(body: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val key = SecretKeySpec(properties.hmacSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM)
        mac.init(key)

        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun getTokenKey(ci: String): String = "finance:token:$ci"

    companion object {
        private const val EMPTY_BODY = ""
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MOCK_SIGNATURE_HEADER = "X-Signature"
        private const val SUCCESS_RESPONSE_CODE = "200"
        private const val TOKEN_REFRESH_MARGIN_SECONDS = 300L
        private const val MIN_TOKEN_TTL_SECONDS = 1L
    }
}
