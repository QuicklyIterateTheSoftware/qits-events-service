-- The call chain an event was published in (qits-2694): origin_trace_id is the W3C trace id
-- (32 lowercase hex) of the chain's FIRST call, on_behalf_of the bearer subject the chain acts for
-- (a person's user UUID, an agent's tok-… subject). Envelope data like parent_id, stated only by a
-- service on the bus publish; null for every event recorded by hand or published before the fields,
-- which is why there is no backfill.
--
-- No index: nothing filters by either yet.
alter table event add column origin_trace_id varchar(32);
alter table event add column on_behalf_of varchar(255);
