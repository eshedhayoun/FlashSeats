-- ============================================================================
-- notification — make the dead-letter queue findable
--
-- `GET /api/v1/admin/notifications/dlq` is the operator's answer to "whose
-- ticket did not arrive?", and until now there was no way to ask: the table has
-- carried a `status` column since V5 with no index on it at all, so the query
-- was a sequential scan over every notification ever sent.
--
-- A PARTIAL index, not a plain one. Almost every row in this table is SENT and
-- stays that way forever; an index over the whole column would be large, mostly
-- useless, and maintained on every successful delivery during a flash sale. This
-- one indexes only the rows an operator ever looks for — typically a handful,
-- often none — so it costs almost nothing to keep and answers the only question
-- it exists for.
--
-- ORDERED by updated_at, because a DLQ listing is read newest-first: the ticket
-- someone is currently chasing is the one that just failed.
--
-- Why this is not merely a performance note: ADR-029 sends deterministic
-- failures straight to the DLQ with NO retries, which is correct only if someone
-- can find and replay them. ADR-038 then went to real trouble making a
-- dead-lettered claim re-claimable so a replay would actually send. Both of
-- those assume an operator who can see this list.
-- ============================================================================

CREATE INDEX idx_notification_logs_dlq
    ON notification_logs (updated_at DESC)
    WHERE status = 'DLQ';
