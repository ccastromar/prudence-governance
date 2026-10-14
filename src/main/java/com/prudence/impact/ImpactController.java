package com.prudence.impact;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/impact")
public class ImpactController {
    private final FriaRepository frias;
    public ImpactController(FriaRepository frias){this.frias=frias;}
    @GetMapping("/systems/{id}/fria") public List<FriaJpaEntity> list(@PathVariable String id){return frias.findByAiSystemIdOrderBySignedAtDesc(id);}
    @PostMapping("/fria") public FriaJpaEntity fria(@RequestBody FriaBody b){
        if(blank(b.aiSystemId)||blank(b.jurisdiction)||blank(b.templateVersion)||blank(b.affectedGroups)||blank(b.assumptions)||blank(b.mitigations)||blank(b.dpiaUri)||!hash(b.dpiaSha256)||blank(b.signedBy)) throw new IllegalArgumentException("complete FRIA and DPIA reference/hash required");
        return frias.save(new FriaJpaEntity(UUID.randomUUID().toString(),b.aiSystemId,b.jurisdiction,b.templateVersion,b.affectedGroups,b.assumptions,b.mitigations,b.dpiaUri,b.dpiaSha256,b.signedBy,Instant.now().toString()));
    }
    private static boolean blank(String s){return s==null||s.isBlank();} private static boolean hash(String h){return h!=null&&h.replace("sha256:","").matches("[a-fA-F0-9]{64}");}
    public record FriaBody(String aiSystemId,String jurisdiction,String templateVersion,String affectedGroups,String assumptions,String mitigations,String dpiaUri,String dpiaSha256,String signedBy){}
}
