package com.prudence.impact;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/impact")
public class ImpactController {
    private final FriaRepository frias; private final ImpactRequirementRepository requirements; private final TransparencyNoticeRepository notices;
    public ImpactController(FriaRepository frias, ImpactRequirementRepository requirements, TransparencyNoticeRepository notices){this.frias=frias;this.requirements=requirements;this.notices=notices;}
    @GetMapping("/systems/{id}/requirements") public Requirement requirement(@PathVariable String id){return requirements.findById(id).map(r->new Requirement(r.isFriaRequired(),r.isTransparencyRequired())).orElse(new Requirement(false,false));}
    @PostMapping("/systems/{id}/requirements") public Requirement requirement(@PathVariable String id,@RequestBody Requirement body){requirements.save(new ImpactRequirementJpaEntity(id,body.friaRequired,body.transparencyRequired));return body;}
    @GetMapping("/systems/{id}/fria") public List<FriaJpaEntity> list(@PathVariable String id){return frias.findByAiSystemIdOrderBySignedAtDesc(id);}
    @GetMapping("/systems/{id}/transparency-notices") public List<TransparencyNoticeJpaEntity> notices(@PathVariable String id){return notices.findByAiSystemIdOrderByDeployedAtDesc(id);}
    @PostMapping("/transparency-notices") public TransparencyNoticeJpaEntity notice(@RequestBody NoticeBody body){if(blank(body.aiSystemId)||body.kind==null||blank(body.audience)||blank(body.noticeVersion)||blank(body.deploymentEvidence)||blank(body.deployedBy))throw new IllegalArgumentException("complete transparency notice deployment evidence required");return notices.save(new TransparencyNoticeJpaEntity(UUID.randomUUID().toString(),body.aiSystemId,body.kind,body.audience,body.noticeVersion,body.deploymentEvidence,body.deployedBy,Instant.now().toString()));}
    @PostMapping("/fria") public FriaJpaEntity fria(@RequestBody FriaBody b){
        if(blank(b.aiSystemId)||blank(b.jurisdiction)||blank(b.templateVersion)||blank(b.affectedGroups)||blank(b.assumptions)||blank(b.mitigations)||blank(b.dpiaUri)||!hash(b.dpiaSha256)||blank(b.signedBy)) throw new IllegalArgumentException("complete FRIA and DPIA reference/hash required");
        return frias.save(new FriaJpaEntity(UUID.randomUUID().toString(),b.aiSystemId,b.jurisdiction,b.templateVersion,b.affectedGroups,b.assumptions,b.mitigations,b.dpiaUri,b.dpiaSha256,b.signedBy,Instant.now().toString()));
    }
    private static boolean blank(String s){return s==null||s.isBlank();} private static boolean hash(String h){return h!=null&&h.replace("sha256:","").matches("[a-fA-F0-9]{64}");}
    public record FriaBody(String aiSystemId,String jurisdiction,String templateVersion,String affectedGroups,String assumptions,String mitigations,String dpiaUri,String dpiaSha256,String signedBy){}
    public record Requirement(boolean friaRequired, boolean transparencyRequired){}
    public record NoticeBody(String aiSystemId, TransparencyKind kind, String audience, String noticeVersion, String deploymentEvidence, String deployedBy){}
}
