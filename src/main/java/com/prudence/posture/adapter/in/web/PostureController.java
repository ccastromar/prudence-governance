package com.prudence.posture.adapter.in.web;

import com.prudence.posture.application.port.in.PostureQuery;
import com.prudence.posture.domain.Posture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Driving adapter: the oversight dashboard reads the estate's posture here.
@RestController
@RequestMapping("/api/posture")
public class PostureController {

    private final PostureQuery posture;

    public PostureController(PostureQuery posture) {
        this.posture = posture;
    }

    @GetMapping
    public Posture get() {
        return posture.compute();
    }
}
