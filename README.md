# Moaje Banking Domain

`moaje` BANKING 도메인 초기 골격입니다.

현재 단계에서는 컨트롤러, Kafka 컨슈머, DB 구현체, gRPC 클라이언트, 목업 뱅킹 API 클라이언트를 만들지 않았습니다. 소스 흐름 파악과 직접 구현을 위해 서비스 인터페이스와 서비스 구현체만 남겨두었습니다.

## 현재 패키지 구조

```text
com.moaje.banking
├── domain
│   ├── common
│   └── transfer
└── application
    └── service
```

## 남겨둔 서비스

- `TransferCommandService`
  - 사용자 송금 요청을 처리하는 서비스 인터페이스입니다.
- `TransferCommandServiceImpl`
  - ASSET 가승인, 목업 뱅킹 API 송금 요청, 완료/실패 이벤트 구성을 구현할 자리입니다.
- `TransferEventService`
  - ASSET 또는 Kafka에서 넘어온 송금 이벤트를 처리하는 서비스 인터페이스입니다.
- `TransferEventServiceImpl`
  - 이벤트 멱등성 확인, 외부 금융망 호출, 완료/실패 이벤트 발행을 구현할 자리입니다.
- `ExternalBankingSyncService`
  - 외부 금융망 동기화를 처리하는 서비스 인터페이스입니다.
- `ExternalBankingSyncServiceImpl`
  - 외부 거래 조회, externalTransactionId 멱등성 검증, ASSET 반영 연계를 구현할 자리입니다.

## 구현 전제

사용자 경험은 토스 같은 뱅킹 앱처럼 즉시 완료에 가깝게 보여줄 수 있습니다. 다만 내부적으로는 외부 금융망 성공이 확인된 경우에만 `COMPLETED`로 확정하고, 결과가 불명확한 경우 `PROCESSING`, 명확히 실패한 경우 `FAILED`로 분리하는 편이 안전합니다.
