# Moaje Banking Domain

Moaje Banking 서비스는 계좌개설, 송금 요청 접수, 목업뱅킹 API 통신, 금융망 처리 결과 이벤트 발행을 담당하는 대외계 도메인입니다.

원장과 잔액의 최종 소유자는 Asset 서비스이며, Banking 서비스는 실제 외부 금융망 또는 목업뱅킹 서버와 통신하는 경계 역할을 맡습니다.

## 현재 개발 범위

- 계좌개설 API 및 서비스 흐름 구성
- 송금 요청 API 및 서비스 흐름 구성
- 목업뱅킹 API 호출용 `RestClient` 구성
- Asset 서비스 가승인 요청용 gRPC 클라이언트 구성
- Asset 송금 요청 이벤트 Kafka Consumer 구성
- Banking 송금 성공/실패 이벤트 Kafka Producer 구성
- 계좌개설 이벤트 Kafka Producer 구성
- 외부 거래 동기화 gRPC 서버 골격 구성
- KFTC API 로그, 은행 라우팅 상태, 대사 이력 JPA 엔티티 및 Repository 구성
- TSID 기반 내부 PK 및 외부 노출 ID 생성 컴포넌트 구성
- Redis 설정 구성
- Dockerfile 추가

## 주요 책임

- 클라이언트의 계좌개설 요청을 받아 목업뱅킹 서버에 계좌개설을 요청합니다.
- 계좌개설 성공 시 `account_created_events` 토픽으로 계좌개설 이벤트를 발행합니다.
- 클라이언트의 송금 요청을 받으면 Asset 서비스에 먼저 가승인을 요청합니다.
- Asset이 발행한 송금 요청 이벤트를 소비한 뒤 목업뱅킹 서버로 실제 송금을 요청합니다.
- 금융망 처리 결과에 따라 송금 성공/실패 이벤트를 발행하여 Asset 서비스가 거래 상태를 확정할 수 있게 합니다.
- 외부 금융망 거래 동기화 요청을 받아 목업뱅킹 거래내역 조회와 Asset 반영 흐름을 연결합니다.

## HTTP API

기본 포트는 `8080`입니다.

### 계좌개설

`POST /api/v1/banking/accounts`

```json
{
  "userId": "1",
  "ci": "ci-test-user",
  "userName": "홍길동",
  "phoneNumber": "01012345678",
  "bankCode": "004",
  "productName": "MOAJE 입출금통장",
  "initialBalance": 0
}
```

### 송금 요청

`POST /api/v1/banking/transfers`

```json
{
  "ci": "ci-test-user",
  "userName": "홍길동",
  "phoneNumber": "01012345678",
  "requesterUserId": "1",
  "withdrawalAccountId": "1000001",
  "depositBankCode": "004",
  "depositAccountNumber": "12345678901234",
  "amount": 10000,
  "currency": "KRW",
  "idempotencyKey": "optional-client-generated-key"
}
```

## gRPC

Banking 서비스는 기본적으로 `9091` 포트에서 gRPC 서버를 엽니다.

주요 메서드는 `moaje-grpc-contracts/proto/grpc/banking_service.proto`를 기준으로 합니다.

- `ExecuteTransfer`: Asset 이벤트 기반 송금 실행 흐름에서 사용
- `SyncExternalAccountTransactions`: 외부 금융망 거래내역 동기화 요청 처리

Banking 서비스는 Asset 서비스의 gRPC 서버(`localhost:9090`)를 호출하여 송금 가승인을 요청합니다.

## Kafka

현재 사용하는 주요 토픽은 다음과 같습니다.

- `moaje.asset.transfer-requested`
- `account_created_events`
- `moaje.banking.transfer-completed`
- `moaje.banking.transfer-failed`

Work 도메인의 소비 패턴 분석/FDS 연동을 위해 거래 성공 이벤트는 Asset 서비스의 `transaction_succeeded_events` 토픽을 기준으로 확장합니다.

## 외부 연동

목업뱅킹 API 서버 기본 주소는 다음 설정을 사용합니다.

- `moaje.banking.external-api.base-url=http://localhost:8081/`

목업뱅킹 서버는 Docker 컨테이너가 아니라 로컬 WAS/Tomcat `8081` 포트에서 별도로 실행하는 구성을 기준으로 합니다.

## 실행 설정

주요 기본 설정은 `src/main/resources/application.yml`에 있습니다.

- HTTP: `8080`
- gRPC server: `9091`
- Asset gRPC target: `localhost:9090`
- Mock Banking API: `localhost:8081`
- Kafka: `localhost:9092`
- Redis: `localhost:6379`

로컬 실행:

```bash
./gradlew bootRun
```

테스트:

```bash
./gradlew test
```

Docker 이미지는 프로젝트 루트의 `Dockerfile`을 기준으로 빌드합니다.
