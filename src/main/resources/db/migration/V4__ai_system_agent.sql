-- The agents that act for each AI system (contract/identity/ section 5): a gateway may ask
-- about an agent only for a system that declares it, and a tool call without a system is
-- governed as the one system its agent acts for.
create table ai_system_agent (
    id varchar(255) not null,
    ai_system_id varchar(255) not null,
    agent_id varchar(255) not null,
    declared_by varchar(512),
    declared_at varchar(255) not null,
    primary key (id),
    constraint ai_system_agent_unique unique (ai_system_id, agent_id)
);
create index ai_system_agent_agent_idx on ai_system_agent (agent_id);
