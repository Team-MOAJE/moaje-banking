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
