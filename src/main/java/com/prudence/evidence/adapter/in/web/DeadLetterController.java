package com.prudence.evidence.adapter.in.web;

import com.prudence.evidence.delivery.DeadLetterService;
import com.prudence.evidence.delivery.DeadLetterService.DeadLetter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/// Dead-lettered evidence and alerts: what never reached its destination, and re-driving it.
@RestController
@RequestMapping("/api/evidence/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetters;

    public DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public List<DeadLetter> list(@RequestParam(defaultValue = "100") int limit) {
        return deadLetters.list(limit);
    }

    @PostMapping("/{target}/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable String target, @PathVariable String id, Principal principal) {
        deadLetters.retry(target, id, principal != null ? principal.getName() : "dev-operator");
    }
}
