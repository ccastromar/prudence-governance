package com.prudence.registry.adapter.in.web;

import com.prudence.registry.adapter.in.web.dto.AiSystemResponse;
import com.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import com.prudence.registry.adapter.in.web.dto.ReviewRequest;
import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewAiSystemUseCase;
import com.prudence.registry.application.port.in.ReviewQueries;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.ReviewEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/// Driving adapter: REST surface of the AI-system registry.
@RestController
@RequestMapping("/api/registry/systems")
public class AiSystemController {

    private final RegisterAiSystemUseCase register;
    private final AiSystemQueries queries;
    private final ReviewAiSystemUseCase review;
    private final ReviewQueries reviews;

    public AiSystemController(RegisterAiSystemUseCase register, AiSystemQueries queries,
                             ReviewAiSystemUseCase review, ReviewQueries reviews) {
        this.register = register;
        this.queries = queries;
        this.review = review;
        this.reviews = reviews;
    }

    @PostMapping
    public ResponseEntity<AiSystemResponse> create(@Valid @RequestBody RegisterAiSystemRequest request) {
        AiSystem created = register.register(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(AiSystemResponse.from(created));
    }

    @GetMapping
    public List<AiSystemResponse> list() {
        return queries.findAll().stream().map(AiSystemResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AiSystemResponse> get(@PathVariable String id) {
        return queries.findById(new AiSystemId(id))
                .map(AiSystemResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /// The approvals inbox acts here. Access is restricted to reviewer roles by the security
    /// filter chain (see SecurityConfig); the reviewer's identity is taken from the principal.
    @PostMapping("/{id}/approve")
    public AiSystemResponse approve(@PathVariable String id, Principal principal) {
        return AiSystemResponse.from(review.approve(new AiSystemId(id), reviewerOf(principal)));
    }

    @PostMapping("/{id}/reject")
    public AiSystemResponse reject(@PathVariable String id,
                                   @RequestBody(required = false) ReviewRequest body,
                                   Principal principal) {
        String reason = body != null ? body.reason() : null;
        return AiSystemResponse.from(review.reject(new AiSystemId(id), reviewerOf(principal), reason));
    }

    /// The tamper-evident audit trail for a system: every approve/reject, who and when, each
    /// sealed in Ernest.
    @GetMapping("/{id}/reviews")
    public List<ReviewEvent> reviews(@PathVariable String id) {
        return reviews.reviewsForSystem(id);
    }

    private static String reviewerOf(Principal principal) {
        return principal != null ? principal.getName() : "dev-reviewer";
    }
}
