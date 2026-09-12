package com.moaje.banking.domain.account

import com.moaje.banking.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class BankingAccountStatus {
    ACTIVE,
    INACTIVE,
    REVOKED,
}

@Entity
@Table(
    name = "banking_account",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_banking_account_provider_account_id", columnNames = ["provider_account_id"]),
    ],
    indexes = [
        Index(name = "idx_banking_account_principal_id", columnList = "principal_id"),
    ],
)
class BankingAccount(
    @Id
    @TsidGeneratedValue
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "principal_id", nullable = false, length = 100)
    var principalId: String,

    @Column(name = "provider_account_id", nullable = false, length = 100)
    var providerAccountId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: BankingAccountStatus = BankingAccountStatus.ACTIVE,

    @Column(name = "bank_code", nullable = false, length = 20)
    var bankCode: String,

    @Column(name = "product_name", nullable = false, length = 100)
    var productName: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    /**
     * 송금에 사용할 외부 계좌 식별자는 소유권과 연결 상태를 확인한 뒤에만 꺼낼 수 있다.
     * 검증을 Aggregate에 모아 두면 REST나 향후 gRPC Adapter가 이 규칙을 우회하기 어렵다.
     */
    fun providerAccountIdForTransfer(requesterPrincipalId: String): String {
        if (principalId != requesterPrincipalId) {
            throw BankingAccountOwnershipException()
        }
        if (status != BankingAccountStatus.ACTIVE) {
            throw BankingAccountNotActiveException(status)
        }
        return providerAccountId
    }
}

class BankingAccountNotFoundException(accountId: Long) :
    RuntimeException("Banking 계좌를 찾을 수 없습니다. accountId=$accountId")

class BankingAccountOwnershipException :
    RuntimeException("요청 사용자는 출금 계좌의 소유자가 아닙니다.")

class BankingAccountNotActiveException(status: BankingAccountStatus) :
    RuntimeException("활성 상태의 계좌만 송금할 수 있습니다. status=$status")
