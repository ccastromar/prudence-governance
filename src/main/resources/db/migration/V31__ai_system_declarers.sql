-- Who declared a system (registered it, and submitted its current review), so a model that makes
-- system.declare and system.approve incompatible can refuse a declarer's review. Older rows stay
-- null: Prudence cannot know who declared them.
alter table ai_system add column declared_by varchar(512);
alter table ai_system add column submitted_by varchar(512);
