CREATE TABLE llm_calls (
 id TEXT PRIMARY KEY NOT NULL,
 request_id TEXT NOT NULL,
 attempt_no INTEGER NOT NULL CHECK(attempt_no >= 0),
 provider_name TEXT CHECK(provider_name IS NULL OR length(provider_name) <= 64),
 model_name TEXT CHECK(model_name IS NULL OR length(model_name) <= 128),
 status TEXT NOT NULL CHECK(status IN ('STARTED','SUCCEEDED','FAILED','REJECTED')),
 created_at TEXT NOT NULL,
 completed_at TEXT,
 duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms >= 0),
 input_tokens INTEGER CHECK(input_tokens IS NULL OR input_tokens >= 0),
 output_tokens INTEGER CHECK(output_tokens IS NULL OR output_tokens >= 0),
 total_tokens INTEGER CHECK(total_tokens IS NULL OR total_tokens >= 0),
 error_code TEXT,
 retryable BOOLEAN NOT NULL CHECK(retryable IN (0,1)),
 session_id TEXT CHECK(session_id IS NULL OR (instr(session_id,char(0))=0 AND length(session_id) BETWEEN 1 AND 64 AND substr(session_id,1,1) GLOB '[A-Za-z0-9]' AND session_id NOT GLOB '*[^A-Za-z0-9_-]*')),
 success BOOLEAN CHECK(success IS NULL OR success IN (0,1)),
 error_message TEXT CHECK(error_message IS NULL OR length(error_message) BETWEEN 1 AND 256),
 UNIQUE(request_id, attempt_no),
 CHECK((status='STARTED' AND success IS NULL AND error_message IS NULL)
 OR (status='SUCCEEDED' AND success IS NOT NULL AND success=1 AND error_message IS NULL AND duration_ms IS NOT NULL)
 OR (status IN ('FAILED','REJECTED') AND success IS NOT NULL AND success=0 AND error_message IS NOT NULL AND length(error_message)>0 AND duration_ms IS NOT NULL)),
 CHECK((status = 'STARTED' AND completed_at IS NULL AND duration_ms IS NULL AND error_code IS NULL AND retryable = 0 AND attempt_no > 0)
 OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND error_code IS NULL AND retryable = 0 AND attempt_no > 0)
 OR (status = 'FAILED' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND error_code IS NOT NULL AND attempt_no > 0)
 OR (status = 'REJECTED' AND completed_at IS NOT NULL AND error_code IS NOT NULL AND duration_ms = 0 AND retryable = 0 AND attempt_no = 0))
);
CREATE INDEX idx_llm_calls_request ON llm_calls(request_id);
CREATE INDEX idx_llm_calls_session_created ON llm_calls(session_id,created_at);
