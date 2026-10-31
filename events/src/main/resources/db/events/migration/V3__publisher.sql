-- The service that dispatched the event (conventionally its maven coordinate, e.g.
-- 'eu.wohlben.qits:qits-ci-service'), or null for an event recorded before publishers stamped it —
-- which is why there is no backfill: null is the true value for those rows, not a gap to paper
-- over. Envelope data like parent_id and environment, named by value with no FK and no existence
-- check: a service retired after its events happened does not make the events false.
--
-- No index: nothing filters by it yet. A ?publisher= filter would add one, the way
-- idx_event_environment came with ?environment=.
alter table event add column publisher varchar(255);
