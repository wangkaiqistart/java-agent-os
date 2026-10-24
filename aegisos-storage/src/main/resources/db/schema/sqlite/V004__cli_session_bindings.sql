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
CREATE TABLE sessions (
 id TEXT PRIMARY KEY NOT NULL,
 profile_name TEXT NOT NULL CHECK(length(profile_name) BETWEEN 1 AND 64 AND profile_name NOT GLOB '*[^A-Za-z0-9_-]*'),
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 version INTEGER NOT NULL CHECK(version>=0),
 next_sequence_no INTEGER NOT NULL CHECK(next_sequence_no>=1)
);
CREATE TABLE agent_processings (
 id TEXT PRIMARY KEY NOT NULL,
 session_id TEXT NOT NULL REFERENCES sessions(id),
 runtime_instance_id TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('RUNNING','COMPLETED','MAX_ITERATIONS','FAILED','INTERRUPTED')),
 started_at TEXT NOT NULL,
 completed_at TEXT,
 iteration_count INTEGER NOT NULL CHECK(iteration_count>=0),
 error_code TEXT CHECK(error_code IS NULL OR error_code IN ('INVALID_INPUT','UNKNOWN_PROFILE','UNKNOWN_SESSION','SESSION_PROFILE_MISMATCH','SESSION_BUSY','WORKSPACE_IN_USE','CONTEXT_LIMIT','CONTEXT_FILE_UNAVAILABLE','SENSITIVE_CONTENT','TOOL_CALL_CONFLICT','PROVIDER_FAILURE','AUDIT_UNAVAILABLE','PERSISTENCE_UNCONFIRMED','INTERRUPTED','RECOVERED_INTERRUPTION','MAX_ITERATIONS','INVALID_RESPONSE','INVALID_ARGUMENTS','UNKNOWN_TOOL','TOOL_NOT_ALLOWED','SANDBOX_DENIED','TOOL_TIMEOUT','TOOL_RESPONSE_LIMIT','TOOL_EXECUTION_FAILED','RESOURCE_LIMIT')),
 version INTEGER NOT NULL CHECK(version>=0),
 UNIQUE(id,session_id),
 CHECK((status='RUNNING' AND completed_at IS NULL AND error_code IS NULL) OR (status='COMPLETED' AND completed_at IS NOT NULL AND error_code IS NULL) OR (status IN ('MAX_ITERATIONS','FAILED','INTERRUPTED') AND completed_at IS NOT NULL AND error_code IS NOT NULL))
);
CREATE UNIQUE INDEX idx_processing_running_session ON agent_processings(session_id) WHERE status='RUNNING';
CREATE INDEX idx_processing_session_started ON agent_processings(session_id,started_at,id);
CREATE TABLE model_turns (
 process_id TEXT NOT NULL REFERENCES agent_processings(id),
 iteration_no INTEGER NOT NULL CHECK(iteration_no>=1),
 status TEXT NOT NULL CHECK(status IN ('PREPARED','ACCEPTED','FAILED','INTERRUPTED')),
 prepared_at TEXT NOT NULL,
 completed_at TEXT,
 error_code TEXT CHECK(error_code IS NULL OR error_code IN ('INVALID_INPUT','UNKNOWN_PROFILE','UNKNOWN_SESSION','SESSION_PROFILE_MISMATCH','SESSION_BUSY','WORKSPACE_IN_USE','CONTEXT_LIMIT','CONTEXT_FILE_UNAVAILABLE','SENSITIVE_CONTENT','TOOL_CALL_CONFLICT','PROVIDER_FAILURE','AUDIT_UNAVAILABLE','PERSISTENCE_UNCONFIRMED','INTERRUPTED','RECOVERED_INTERRUPTION','MAX_ITERATIONS','INVALID_RESPONSE','INVALID_ARGUMENTS','UNKNOWN_TOOL','TOOL_NOT_ALLOWED','SANDBOX_DENIED','TOOL_TIMEOUT','TOOL_RESPONSE_LIMIT','TOOL_EXECUTION_FAILED','RESOURCE_LIMIT')),
 PRIMARY KEY(process_id,iteration_no),
 CHECK((status='PREPARED' AND completed_at IS NULL AND error_code IS NULL) OR (status='ACCEPTED' AND completed_at IS NOT NULL AND error_code IS NULL) OR (status IN ('FAILED','INTERRUPTED') AND completed_at IS NOT NULL AND error_code IS NOT NULL))
);
CREATE TABLE llm_request_links (
 request_id TEXT PRIMARY KEY NOT NULL,
 process_id TEXT NOT NULL,
 iteration_no INTEGER NOT NULL,
 UNIQUE(process_id,iteration_no),
 FOREIGN KEY(process_id,iteration_no) REFERENCES model_turns(process_id,iteration_no)
);
CREATE TABLE message_exchange_groups (
 id TEXT PRIMARY KEY NOT NULL,
 process_id TEXT NOT NULL REFERENCES agent_processings(id),
 group_ordinal INTEGER NOT NULL CHECK(group_ordinal>=0),
 kind TEXT NOT NULL CHECK(kind IN ('USER','TEXT','TOOL_EXCHANGE')),
 state TEXT NOT NULL CHECK(state IN ('OPEN','CLOSED')),
 iteration_no INTEGER,
 expected_observations INTEGER NOT NULL CHECK(expected_observations>=0),
 committed_observations INTEGER NOT NULL CHECK(committed_observations>=0 AND committed_observations<=expected_observations),
 UNIQUE(process_id,group_ordinal),
 UNIQUE(id,process_id),
 FOREIGN KEY(process_id,iteration_no) REFERENCES model_turns(process_id,iteration_no),
 CHECK((kind='USER' AND iteration_no IS NULL AND expected_observations=0 AND state='CLOSED') OR (kind='TEXT' AND iteration_no IS NOT NULL AND expected_observations=0 AND state='CLOSED') OR (kind='TOOL_EXCHANGE' AND iteration_no IS NOT NULL AND expected_observations>0)),
 CHECK((state='CLOSED' AND committed_observations=expected_observations) OR (state='OPEN' AND committed_observations<expected_observations))
);
CREATE TABLE session_messages (
 id TEXT PRIMARY KEY NOT NULL,
 session_id TEXT NOT NULL,
 process_id TEXT NOT NULL,
 sequence_no INTEGER NOT NULL CHECK(sequence_no>=1),
 group_id TEXT NOT NULL,
 role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT','TOOL')),
 content TEXT,
 tool_call_id TEXT CHECK(tool_call_id IS NULL OR (length(tool_call_id) BETWEEN 1 AND 128 AND tool_call_id NOT GLOB '*[^A-Za-z0-9_.-]*')),
 tool_calls_json TEXT,
 created_at TEXT NOT NULL,
 UNIQUE(session_id,sequence_no),
 FOREIGN KEY(process_id,session_id) REFERENCES agent_processings(id,session_id),
 FOREIGN KEY(group_id,process_id) REFERENCES message_exchange_groups(id,process_id),
 CHECK((role='TOOL' AND tool_call_id IS NOT NULL AND tool_calls_json IS NULL AND content IS NOT NULL) OR (role='USER' AND tool_call_id IS NULL AND tool_calls_json IS NULL AND content IS NOT NULL) OR (role='ASSISTANT' AND tool_call_id IS NULL AND (content IS NOT NULL OR tool_calls_json IS NOT NULL)))
);
CREATE INDEX idx_messages_process_group ON session_messages(process_id,group_id,sequence_no);
CREATE TABLE tool_invocations (
 id TEXT PRIMARY KEY NOT NULL,
 session_id TEXT NOT NULL,
 process_id TEXT NOT NULL,
 raw_call_id TEXT NOT NULL CHECK(length(raw_call_id) BETWEEN 1 AND 128 AND raw_call_id NOT GLOB '*[^A-Za-z0-9_.-]*'),
 tool_name TEXT NOT NULL CHECK(length(tool_name) BETWEEN 1 AND 64 AND tool_name NOT GLOB '*[^A-Za-z0-9_-]*'),
 first_iteration_no INTEGER NOT NULL CHECK(first_iteration_no>=1),
 call_ordinal INTEGER NOT NULL CHECK(call_ordinal>=1),
 arguments_fingerprint TEXT NOT NULL CHECK(length(arguments_fingerprint)=64 AND arguments_fingerprint NOT GLOB '*[^0-9a-f]*'),
 status TEXT NOT NULL CHECK(status IN ('STARTED','SUCCEEDED','FAILED','REJECTED','UNKNOWN')),
 started_at TEXT NOT NULL,
 completed_at TEXT,
 duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms>=0),
 success BOOLEAN CHECK(success IS NULL OR success IN (0,1)),
 error_code TEXT CHECK(error_code IS NULL OR error_code IN ('INVALID_INPUT','UNKNOWN_PROFILE','UNKNOWN_SESSION','SESSION_PROFILE_MISMATCH','SESSION_BUSY','WORKSPACE_IN_USE','CONTEXT_LIMIT','CONTEXT_FILE_UNAVAILABLE','SENSITIVE_CONTENT','TOOL_CALL_CONFLICT','PROVIDER_FAILURE','AUDIT_UNAVAILABLE','PERSISTENCE_UNCONFIRMED','INTERRUPTED','RECOVERED_INTERRUPTION','MAX_ITERATIONS','INVALID_RESPONSE','INVALID_ARGUMENTS','UNKNOWN_TOOL','TOOL_NOT_ALLOWED','SANDBOX_DENIED','TOOL_TIMEOUT','TOOL_RESPONSE_LIMIT','TOOL_EXECUTION_FAILED','RESOURCE_LIMIT')),
 error_message TEXT CHECK(error_message IS NULL OR length(error_message) BETWEEN 1 AND 256),
 external_outcome TEXT NOT NULL CHECK(external_outcome IN ('NOT_SENT','RESPONDED','UNKNOWN')),
 UNIQUE(process_id,raw_call_id),
 FOREIGN KEY(process_id,session_id) REFERENCES agent_processings(id,session_id),
 FOREIGN KEY(process_id,first_iteration_no) REFERENCES model_turns(process_id,iteration_no),
 CHECK((status='STARTED' AND completed_at IS NULL AND duration_ms IS NULL AND success IS NULL AND error_code IS NULL AND error_message IS NULL AND external_outcome='NOT_SENT') OR (status='SUCCEEDED' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND success IS NOT NULL AND success=1 AND error_code IS NULL AND error_message IS NULL AND external_outcome='RESPONDED') OR (status IN ('FAILED','REJECTED','UNKNOWN') AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND success IS NOT NULL AND success=0 AND error_code IS NOT NULL AND error_message IS NOT NULL)),
 CHECK(status<>'REJECTED' OR (external_outcome='NOT_SENT' AND duration_ms=0))
);
CREATE TABLE processing_events (
 id TEXT PRIMARY KEY NOT NULL,
 process_id TEXT NOT NULL REFERENCES agent_processings(id),
 iteration_no INTEGER,
 group_id TEXT,
 event_type TEXT NOT NULL CHECK(event_type IN ('WARN','TRUNCATED','TOOL_REUSED','TOOL_CONFLICT','STOPPED','RECOVERED')),
 created_at TEXT NOT NULL,
 reason_code TEXT NOT NULL CHECK(reason_code IN ('OPTIONAL_FILE_MISSING','CONTEXT_TRIMMED','HISTORY_PROCESSINGS_TRIMMED','CURRENT_GROUPS_TRIMMED','OPTIONAL_MEMORY_TRIMMED','CONTEXT_BYTES_TRIMMED','RELIABLE_OBSERVATION','TOOL_CALL_CONFLICT','INVALID_INPUT','UNKNOWN_PROFILE','UNKNOWN_SESSION','SESSION_PROFILE_MISMATCH','SESSION_BUSY','WORKSPACE_IN_USE','CONTEXT_LIMIT','CONTEXT_FILE_UNAVAILABLE','SENSITIVE_CONTENT','TOOL_CALL_CONFLICT','PROVIDER_FAILURE','AUDIT_UNAVAILABLE','PERSISTENCE_UNCONFIRMED','INTERRUPTED','RECOVERED_INTERRUPTION','MAX_ITERATIONS','INVALID_RESPONSE','INVALID_ARGUMENTS','UNKNOWN_TOOL','TOOL_NOT_ALLOWED','SANDBOX_DENIED','TOOL_TIMEOUT','TOOL_RESPONSE_LIMIT','TOOL_EXECUTION_FAILED','RESOURCE_LIMIT')),
 count INTEGER NOT NULL CHECK(count>=0),
 FOREIGN KEY(process_id,iteration_no) REFERENCES model_turns(process_id,iteration_no),
 FOREIGN KEY(group_id,process_id) REFERENCES message_exchange_groups(id,process_id)
);
CREATE UNIQUE INDEX idx_sessions_id_profile ON sessions(id,profile_name);
CREATE TABLE session_bindings (
 channel TEXT NOT NULL CHECK(instr(channel,char(0))=0 AND length(channel) BETWEEN 1 AND 64 AND substr(channel,1,1) GLOB '[A-Za-z0-9]' AND channel NOT GLOB '*[^A-Za-z0-9_.:-]*'),
 user_id TEXT NOT NULL CHECK(instr(user_id,char(0))=0 AND length(user_id) BETWEEN 1 AND 64 AND substr(user_id,1,1) GLOB '[A-Za-z0-9]' AND user_id NOT GLOB '*[^A-Za-z0-9_.:-]*'),
 profile_name TEXT NOT NULL CHECK(instr(profile_name,char(0))=0 AND length(profile_name) BETWEEN 1 AND 64 AND substr(profile_name,1,1) GLOB '[A-Za-z0-9]' AND profile_name NOT GLOB '*[^A-Za-z0-9_-]*'),
 session_id TEXT NOT NULL UNIQUE CHECK(length(session_id)=36 AND length(replace(session_id,'-',''))=32 AND session_id NOT GLOB '*[^0-9a-f-]*' AND substr(session_id,9,1)='-' AND substr(session_id,14,1)='-' AND substr(session_id,19,1)='-' AND substr(session_id,24,1)='-'),
 created_at TEXT NOT NULL CHECK(length(created_at)>0),
 PRIMARY KEY(channel,user_id,profile_name),
 FOREIGN KEY(session_id,profile_name) REFERENCES sessions(id,profile_name)
);
