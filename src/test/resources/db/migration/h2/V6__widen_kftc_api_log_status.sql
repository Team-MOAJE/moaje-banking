-- 테스트 DB도 실제 DB와 동일한 상태 문자열을 저장할 수 있어야 한다.
ALTER TABLE kftc_api_log ALTER COLUMN status VARCHAR(32) NOT NULL;
