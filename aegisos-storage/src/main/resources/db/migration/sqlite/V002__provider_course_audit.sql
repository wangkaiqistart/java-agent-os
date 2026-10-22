-- 离线执行前停止全部写入并用 SQLite backup 备份。任何失败由调用入口 ROLLBACK/关闭连接。
BEGIN IMMEDIATE;
CREATE TEMP TABLE migration_rerun(ok INTEGER CONSTRAINT ALREADY_MIGRATED CHECK(ok=1));
INSERT INTO migration_rerun SELECT CASE WHEN EXISTS(SELECT 1 FROM aegisos_schema WHERE version=2) THEN 0 ELSE 1 END;
DROP TABLE migration_rerun;
CREATE TEMP TABLE migration_preflight(ok INTEGER CONSTRAINT INVALID_V1_SCHEMA CHECK(ok=1));
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM pragma_table_info('aegisos_schema'))=2 AND (SELECT count(*) FROM pragma_table_info('aegisos_schema') WHERE (name='id' AND type='INTEGER' AND pk=1) OR (name='version' AND type='INTEGER' AND [notnull]=1))=2 THEN 1 ELSE 0 END;
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM aegisos_schema)=1 AND EXISTS(SELECT 1 FROM aegisos_schema WHERE id=1 AND version=1) THEN 1 ELSE 0 END;
INSERT INTO migration_preflight SELECT CASE WHEN EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='llm_calls' AND lower(replace(replace(replace(replace(replace(sql,char(32),''),char(10),''),char(13),''),char(9),''),char(34),''))='createtablellm_calls(idtextprimarykeynotnull,request_idtextnotnull,attempt_nointegernotnullcheck(attempt_no>=0),provider_nametextcheck(provider_nameisnullorlength(provider_name)<=64),model_nametextcheck(model_nameisnullorlength(model_name)<=128),statustextnotnullcheck(statusin(''started'',''succeeded'',''failed'',''rejected'')),created_attextnotnull,completed_attext,duration_msintegercheck(duration_msisnullorduration_ms>=0),input_tokensintegercheck(input_tokensisnullorinput_tokens>=0),output_tokensintegercheck(output_tokensisnulloroutput_tokens>=0),total_tokensintegercheck(total_tokensisnullortotal_tokens>=0),error_codetext,retryablebooleannotnullcheck(retryablein(0,1)),unique(request_id,attempt_no),check((status=''started''andcompleted_atisnullandduration_msisnullanderror_codeisnullandretryable=0andattempt_no>0)or(status=''succeeded''andcompleted_atisnotnullandduration_msisnotnullanderror_codeisnullandretryable=0andattempt_no>0)or(status=''failed''andcompleted_atisnotnullandduration_msisnotnullanderror_codeisnotnullandattempt_no>0)or(status=''rejected''andcompleted_atisnotnullanderror_codeisnotnullandduration_ms=0andretryable=0andattempt_no=0)))') THEN 1 ELSE 0 END;
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM sqlite_master WHERE tbl_name='llm_calls' AND type='index')=3 AND EXISTS(SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_llm_calls_request' AND lower(replace(replace(replace(replace(replace(sql,char(32),''),char(10),''),char(13),''),char(9),''),char(34),''))='createindexidx_llm_calls_requestonllm_calls(request_id)') AND NOT EXISTS(SELECT 1 FROM sqlite_master WHERE tbl_name='llm_calls' AND type='trigger') THEN 1 ELSE 0 END;
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM pragma_integrity_check WHERE integrity_check <> 'ok')=0 AND (SELECT count(*) FROM pragma_foreign_key_check)=0 THEN 1 ELSE 0 END;
CREATE TABLE llm_calls_new (
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
INSERT INTO llm_calls_new(id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable,session_id,success,error_message)
SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable,NULL,CASE WHEN status='STARTED' THEN NULL WHEN status='SUCCEEDED' THEN 1 ELSE 0 END,CASE WHEN status IN ('FAILED','REJECTED') THEN CASE error_code WHEN 'PROVIDER_TIMEOUT' THEN '模型调用超时' WHEN 'AUTHENTICATION_FAILED' THEN '认证失败' WHEN 'TEMPORARY_NETWORK_ERROR' THEN '临时网络错误' WHEN 'RATE_LIMITED' THEN '请求受到限流' WHEN 'AUDIT_UNAVAILABLE' THEN '审计服务不可用' WHEN 'PROVIDER_HTTP_ERROR' THEN '模型响应异常' WHEN 'PROVIDER_PROTOCOL_ERROR' THEN '模型响应异常' WHEN 'PROVIDER_REQUIRED' THEN '请求配置无效或模型不可用' WHEN 'MODEL_REQUIRED' THEN '请求配置无效或模型不可用' WHEN 'UNKNOWN_PROVIDER' THEN '请求配置无效或模型不可用' WHEN 'MODEL_UNAVAILABLE' THEN '请求配置无效或模型不可用' WHEN 'PROVIDER_UNAVAILABLE' THEN '请求配置无效或模型不可用' WHEN 'INVALID_CONFIGURATION' THEN '请求配置无效或模型不可用' WHEN 'INVALID_REQUEST' THEN '请求配置无效或模型不可用' WHEN 'UNSUPPORTED_TOOL_SCHEMA' THEN '请求配置无效或模型不可用' ELSE '历史调用未成功，详细原因不可用' END ELSE NULL END FROM llm_calls;
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM llm_calls)=(SELECT count(*) FROM llm_calls_new) AND NOT EXISTS(SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable FROM llm_calls EXCEPT SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable FROM llm_calls_new) AND NOT EXISTS(SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable FROM llm_calls_new EXCEPT SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable FROM llm_calls) THEN 1 ELSE 0 END;
DROP TABLE llm_calls;
ALTER TABLE llm_calls_new RENAME TO llm_calls;
CREATE INDEX idx_llm_calls_request ON llm_calls(request_id);
CREATE INDEX idx_llm_calls_session_created ON llm_calls(session_id,created_at);
INSERT INTO migration_preflight SELECT CASE WHEN (SELECT count(*) FROM pragma_integrity_check WHERE integrity_check <> 'ok')=0 AND (SELECT count(*) FROM pragma_foreign_key_check)=0 THEN 1 ELSE 0 END;
UPDATE aegisos_schema SET version=2 WHERE id=1 AND version=1;
DROP TABLE migration_preflight;
COMMIT;
