package com.prudence.registry.adapter.in.web;

import com.prudence.registry.adapter.in.web.dto.AiSystemResponse;
import com.prudence.registry.adapter.in.web.dto.RegisterAiSystemRequest;
import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.application.port.in.RegisterAiSystemUseCase;
import com.prudence.registry.domain.AiSystem;
import com.prudence.registry.domain.AiSystemId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/// Driving adapter: REST surface of the AI-system registry.
@RestController
@RequestMapping("/api/registry/systems")
public class AiSystemController {

    private final RegisterAiSystemUseCase register;
    private final AiSystemQueries queries;

    public AiSystemController(RegisterAiSystemUseCase register, AiSystemQueries queries) {
        this.register = register;
        this.queries = queries;
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
}
