# Moaje Banking

모아제의 금융 채널 서비스입니다. Mock Banking에 금융업무를 요청하고 송금 상태·멱등성·Outbox·대사를 관리합니다. 실제 잔액의 기준은 Mock Banking입니다.

- [개발 기록과 검증 결과](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/phase-history-and-retrospective.md)
- [공개 문서와 ADR](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/README.md)
- [Banking gRPC 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/grpc/banking_service.proto)
- [Banking 이벤트 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/events/banking_events.proto)
- [로컬 통합 실행](https://github.com/Team-MOAJE/moaje-infra/blob/main/readMe.md)

Auth·Work의 실제 연동 계약과 Swagger는 후속 작업입니다. 기존 데이터를 유지하는 DB 업그레이드는 별도 전환 검증이 필요합니다.

## 패키지 구성

Asset과 동일하게 **기능을 먼저, 역할을 그 안에** 나눕니다. Banking의 주요 기능은 `account`, `transfer`, `outbox`이며, 감사 기록과 은행 상태는 `kftc`, `reconciliation`, `routing`에 있습니다.

| 기능 아래의 경로 | 담는 코드 |
|---|---|
| `web` | HTTP 요청을 받고 업무를 호출하는 Controller |
| `application/service` | 업무 인터페이스와 실제 처리 클래스 |
| `application/model` | 업무 요청·결과와 조회 응답 데이터 |
| `application/query` | 외부 조회 계약이 필요할 때 사용하는 경로 |
| `domain` | 계좌·거래 상태와 변경 규칙 |
| `persistence` | DB 조회·저장 Repository |
| `messaging` | 이벤트 생성·발행 관련 코드 |

업무 인터페이스와 구현체가 나뉜 경우 이름은 `XxxService`와 `XxxServiceImpl`로 맞춥니다. 인터페이스가 없는 서비스에는 `Impl`을 붙이지 않습니다.

송금은 `transfer/web/TransferController.kt`에서 시작해 `transfer/application/service/TransferCommandServiceImpl.kt`가 처리합니다. 거래 상태는 `transfer/domain/BankingTransfer.kt`, DB 접근은 `transfer/persistence/BankingTransferRepository.kt`에서 확인합니다. `Service` 인터페이스와 구현체는 같은 폴더에 둡니다.

계좌·송금이 함께 사용하는 Mock Banking 통신은 `integration/mockbanking`, gRPC 요청 접수는 `api/grpc`, 공통 인증·설정은 `common`에 둡니다. 송금 대사는 `transfer/application/reconciliation`에 있습니다. 외부 응답 DTO는 통신 코드의 `model`에 두며, 거래 생성·해시 규칙에서 직접 사용하는 `TransferCommand`와 금액 같은 값은 `domain`에 유지합니다. 패키지 정리를 위해 새로운 인터페이스나 빈 폴더를 만들지는 않습니다.

## 송금 오류 분류

- `MockTransferRejectionCode`의 HTTP 상태와 오류 코드가 모두 맞을 때만 `FAILED`로 확정합니다. 조회 전용 `TRANSFER_NOT_FOUND`는 송금 거절 목록에서 제외합니다.
- 통신 장애는 `TIMEOUT`, 해석할 수 없는 응답은 `UNREADABLE_RESPONSE`, 해석은 가능하지만 거절로 확정하지 못하는 HTTP 오류는 `UNCONFIRMED_HTTP_ERROR`로 기록합니다. 세 경우 모두 거래는 `UNKNOWN`, `reconciliationRequired=true`이며 확정 이벤트를 만들지 않습니다.
- 오류 본문과 예외 메시지 원문은 송금 결과에 저장하지 않습니다. 감사 로그의 `responseCode`에는 HTTP 번호를, 확인된 거절 코드는 실패 사유에 남깁니다.
- 새 상태 문자열을 위해 V6에서 `kftc_api_log.status`를 32자로 확장합니다. 이미 잘못 `FAILED`로 저장된 과거 거래를 자동으로 복구하는 변경은 아닙니다.
- 검증: `MockBankingTransferErrorTest`는 HTTP 오류 분류를, `TransferCommandServiceIdempotencyTest`는 UNKNOWN 저장·대사 필요·Outbox 미생성을 확인합니다. 이번 변경은 정상 응답·결과 조회 로직을 변경하지 않습니다. 성공 응답의 빈 본문은 여전히 예외로 전파되며 별도 보강 대상입니다.
