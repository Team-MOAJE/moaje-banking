package com.moaje.banking.common.restclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.banking.common.bank.MockBankingAccountResponse
import com.moaje.banking.common.bank.ResponseToken
import com.moaje.banking.common.bank.TransferHistoryResponse
import com.moaje.banking.common.bank.TransferResponse
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.transfer.TransferCommand
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

@Component
class MockBankingApiClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val properties: BankingExternalApiProperties,
) {
    /**
     * 목업 뱅킹 서버에 계좌개설을 요청합니다.
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
     * 목업 금융망에 실제 송금 실행을 요청합니다.
     */
    fun transferRequest(command: TransferCommand): MockBankingTransferResult {
        val token = getOrFetchToken(command.ci, command.userName, command.phoneNumber)
        return transferExecute(command, token.accessToken)
    }

    /**
     * 외부 금융망 계좌 거래내역을 조회합니다.
     */
    fun fetchAccountHistories(
        accountNumber: String,
        ci: String,
        userName: String,
        phoneNumber: String,
    ): List<TransferHistoryResponse> {
        val token = getOrFetchToken(ci, userName, phoneNumber)

        return restClient.get()
            .uri("api/accounts/{accountNumber}/histories", accountNumber)
            .headers {
                it.setBearerAuth(token.accessToken)
                it.set(MOCK_SIGNATURE_HEADER, sign(EMPTY_BODY))
            }
            .retrieve()
            .body<List<TransferHistoryResponse>>()
            ?: emptyList()
    }

    /**
     * 목업 금융망 API 접근 토큰을 새로 발급받습니다.
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
     * Redis 캐시에서 토큰을 우선 사용하고, 만료되었으면 목업 금융망에서 새로 발급받습니다.
     */
    private fun getOrFetchToken(ci: String, userName: String, phoneNumber: String): ResponseToken {
        val redisKey = tokenKey(ci)
        val cachedToken = getTokenByRedis(ci)

        if (cachedToken != null && cachedToken.absoluteExpiration > System.currentTimeMillis()) {
            extendTokenTtl(redisKey, cachedToken, 1, TimeUnit.DAYS)
            return cachedToken
        }

        if (cachedToken != null) {
            redisTemplate.delete(redisKey)
        }

        val newToken = fetchNewToken(ci, userName, phoneNumber)
        redisTemplate.opsForValue().set(redisKey, newToken, 3, TimeUnit.DAYS)
        return newToken
    }

    private fun getTokenByRedis(ci: String): ResponseToken? {
        return redisTemplate.opsForValue().get(tokenKey(ci)) as? ResponseToken
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

    private fun transferExecute(
        command: TransferCommand,
        accessToken: String,
    ): MockBankingTransferResult {
        val transferBody = buildTransferRequestPayload(command)

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
                requestPayload = transferBody,
                responseCode = SUCCESS_RESPONSE_CODE,
                externalTransactionId = transferResponse.debitHistory.transactionId,
            )
        } catch (exception: ResourceAccessException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.TIMEOUT,
                requestPayload = transferBody,
                responseCode = "TIMEOUT",
                failureReason = exception.message ?: "목업 뱅킹 API 타임아웃이 발생했습니다.",
            )
        } catch (exception: RestClientResponseException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.FAILED,
                requestPayload = transferBody,
                responseCode = exception.statusCode.value().toString(),
                failureReason = exception.responseBodyAsString.ifBlank { exception.message },
            )
        } catch (exception: RestClientException) {
            MockBankingTransferResult(
                status = KftcApiLogStatus.FAILED,
                requestPayload = transferBody,
                failureReason = exception.message ?: "목업 뱅킹 API 송금 요청에 실패했습니다.",
            )
        }
    }

    private fun buildTransferRequestPayload(command: TransferCommand): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "fromAccountNumber" to command.withdrawalAccountId,
                "toAccountNumber" to command.depositAccountNumber,
                "amount" to command.amount.amount.toLong(),
            ),
        )
    }

    /**
     * 목업 뱅킹 계좌개설 요청 JSON 본문을 생성합니다.
     */
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
     * 목업 뱅킹 API 스펙에 맞춰 요청 본문에 대한 HMAC-SHA256 서명을 생성합니다.
     */
    private fun sign(body: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val key = SecretKeySpec(properties.hmacSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM)
        mac.init(key)

        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun tokenKey(ci: String): String = "finance:token:$ci"

    companion object {
        private const val EMPTY_BODY = ""
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MOCK_SIGNATURE_HEADER = "X-Signature"
        private const val SUCCESS_RESPONSE_CODE = "200"
        private const val TOKEN_REFRESH_MARGIN_SECONDS = 300L
        private const val MIN_TOKEN_TTL_SECONDS = 1L
    }
}
