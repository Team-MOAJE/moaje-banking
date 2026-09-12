# Moaje Banking

모아제의 금융 채널 서비스입니다. Mock Banking에 금융업무를 요청하고 송금 상태·멱등성·Outbox·대사를 관리합니다. 실제 잔액의 기준은 Mock Banking입니다.

- [개발 기록과 검증 결과](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/phase-history-and-retrospective.md)
- [공개 문서와 ADR](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/README.md)
- [Banking gRPC 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/grpc/banking_service.proto)
- [Banking 이벤트 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/events/banking_events.proto)
- [로컬 통합 실행](https://github.com/Team-MOAJE/moaje-infra/blob/main/readMe.md)

Auth·Work의 실제 연동 계약과 Swagger는 후속 작업입니다. 기존 데이터를 유지하는 DB 업그레이드는 별도 전환 검증이 필요합니다.
