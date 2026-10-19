package cc.ccmlab.prudence.shared.exception;

/// Base for domain rule violations (kept framework-free, like the rest of the domain).
public class DomainException extends RuntimeException {
  public DomainException(String message) {
    super(message);
  }
}
