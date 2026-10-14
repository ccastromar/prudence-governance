create table system_event_sequence (ai_system_id varchar(255) primary key references ai_system(id), next_sequence bigint not null);
