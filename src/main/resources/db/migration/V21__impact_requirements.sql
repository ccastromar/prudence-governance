create table impact_requirement (ai_system_id varchar(255) primary key references ai_system(id), fria_required boolean not null default false);
