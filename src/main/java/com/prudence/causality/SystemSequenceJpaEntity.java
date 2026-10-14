package com.prudence.causality; import jakarta.persistence.*;
@Entity @Table(name="system_event_sequence") public class SystemSequenceJpaEntity { @Id private String aiSystemId; private long nextSequence; protected SystemSequenceJpaEntity(){} public SystemSequenceJpaEntity(String id){aiSystemId=id;nextSequence=1;} public long take(){return nextSequence++;} }
