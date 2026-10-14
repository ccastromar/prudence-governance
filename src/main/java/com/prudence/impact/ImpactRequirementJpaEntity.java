package com.prudence.impact;
import jakarta.persistence.*;
@Entity @Table(name="impact_requirement") public class ImpactRequirementJpaEntity { @Id private String aiSystemId; private boolean friaRequired; private boolean transparencyRequired; protected ImpactRequirementJpaEntity(){} public ImpactRequirementJpaEntity(String id,boolean friaRequired,boolean transparencyRequired){aiSystemId=id;this.friaRequired=friaRequired;this.transparencyRequired=transparencyRequired;} public boolean isFriaRequired(){return friaRequired;} public boolean isTransparencyRequired(){return transparencyRequired;} }
