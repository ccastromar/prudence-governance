package com.prudence.incident.domain;

public class IncidentNotFoundException extends RuntimeException {
  public IncidentNotFoundException(String id) {
    super("incident not found: " + id);
  }
}
