CREATE TABLE CapabilityInvocation (
  id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(32) NOT NULL,
  app_slug VARCHAR(255) NOT NULL,
  app_version BIGINT NOT NULL,
  caller_json TEXT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL,
  input_json TEXT NOT NULL,
  output_json TEXT NULL,
  failure_type TEXT NULL,
  failure_message TEXT NULL,
  journal_count BIGINT NOT NULL,
  audit_count BIGINT NOT NULL,
  audit_head VARCHAR(64) NOT NULL,
  CHECK ((output_json IS NULL) <> (failure_type IS NULL))
);

CREATE INDEX CapabilityInvocationByAppSlugStartedAt
ON CapabilityInvocation(app_slug, started_at DESC);

CREATE TABLE CapabilityJournalEntry (
  invocation_id VARCHAR(36) NOT NULL REFERENCES CapabilityInvocation(id) ON DELETE CASCADE,
  seq BIGINT NOT NULL,
  capability_key TEXT NOT NULL,
  result_json TEXT NULL,
  failure_type TEXT NULL,
  failure_message TEXT NULL,
  PRIMARY KEY(invocation_id, seq),
  CHECK ((result_json IS NULL) <> (failure_type IS NULL))
);

CREATE TABLE CapabilityAuditEntry (
  invocation_id VARCHAR(36) NOT NULL REFERENCES CapabilityInvocation(id) ON DELETE CASCADE,
  seq BIGINT NOT NULL,
  timestamp_millis BIGINT NOT NULL,
  kind VARCHAR(64) NOT NULL,
  caller_json TEXT NOT NULL,
  detail TEXT NOT NULL,
  result_hash VARCHAR(64) NOT NULL,
  previous_hash VARCHAR(64) NOT NULL,
  hash VARCHAR(64) NOT NULL,
  PRIMARY KEY(invocation_id, seq)
);
