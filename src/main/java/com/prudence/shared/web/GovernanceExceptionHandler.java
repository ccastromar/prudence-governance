package com.prudence.shared.web;

import com.prudence.incident.domain.IncidentNotFoundException;
import com.prudence.registry.domain.AiSystemNotFoundException;
import com.prudence.shared.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/// Translates domain rule violations into HTTP problem responses, so controllers stay thin
/// and the domain stays framework-free. Not-found maps to 404; any other domain-rule breach
/// (e.g. approving a prohibited system) is a 409 state conflict.
@RestControllerAdvice
public class GovernanceExceptionHandler {

  @ExceptionHandler(AiSystemNotFoundException.class)
  ProblemDetail onNotFound(AiSystemNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IncidentNotFoundException.class)
  ProblemDetail onIncidentNotFound(IncidentNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  /// Invalid input the domain rejects (missing fields, impossible dates) is the caller's error.
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail onInvalidInput(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(DomainException.class)
  ProblemDetail onDomainRule(DomainException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }
}
