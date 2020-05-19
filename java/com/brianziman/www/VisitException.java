package com.brianziman.www;

/**
 * Exception used to capture errors and status in
 * CurrentServlet.
 */
class VisitException extends RuntimeException {
  public enum Status {
    MY_BAD,
    YOUR_BAD;
  }

  private final Status status;

  public VisitException(Status status, String display) {
    super(display);
    this.status = status;
  }

  public Status getStatus() {
    return status;
  }
}
