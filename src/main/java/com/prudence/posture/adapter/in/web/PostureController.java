package com.prudence.posture.adapter.in.web;

import com.prudence.posture.application.port.in.DriftQuery;
import com.prudence.posture.application.port.in.PostureQuery;
import com.prudence.posture.domain.Drift;
import com.prudence.posture.domain.Posture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Driving adapter: the oversight dashboard reads the estate's posture and drift here.
@RestController
@RequestMapping("/api/posture")
public class PostureController {

    private final PostureQuery posture;
    private final DriftQuery drift;

    public PostureController(PostureQuery posture, DriftQuery drift) {
        this.posture = posture;
        this.drift = drift;
    }

    @GetMapping
    public Posture get() {
        return posture.compute();
    }

    /// Policy drift: executions observed in the evidence plane that diverge from the
    /// governance record (ungoverned runs).
    @GetMapping("/drift")
    public Drift drift() {
        return drift.compute();
    }
}
