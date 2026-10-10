-- 응답 해석 실패와 미확정 HTTP 오류를 기록할 수 있도록 상태 컬럼만 확장한다.
ALTER TABLE kftc_api_log MODIFY COLUMN status VARCHAR(32) NOT NULL;
