package com.prudence.alerting.adapter.in.web;

import com.prudence.alerting.application.port.in.AlertQueries;
import com.prudence.alerting.application.port.in.AlertQueries.AlertView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/// Read-only: which alerts were raised, to whom, and whether each was actually delivered.
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertQueries alerts;

    public AlertController(AlertQueries alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertView> recent(@RequestParam(defaultValue = "50") int limit) {
        return alerts.recent(limit);
    }
}
