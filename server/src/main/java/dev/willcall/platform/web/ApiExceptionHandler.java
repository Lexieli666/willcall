package dev.willcall.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into RFC 9457 {@code application/problem+json} with a stable {@code code}
 * field. The code, not the status, is what a client branches on: several distinct situations share
 * 409 and a client that cannot tell "seat taken" from "you already hold it" cannot show a useful
 * message.
 *
 * <p>Expected failures are logged at INFO with no stack trace. Under a flash sale the 409 rate is
 * the majority of traffic by design, and logging each one at WARN with a stack trace would cost
 * more than serving the request.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final URI PROBLEM_BASE = URI.create("https://willcall.dev/problems/");

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApiException(
      ApiException e, HttpServletRequest request) {
    ProblemDetail problem = problem(e.code(), e.getMessage(), request);
    e.extra().forEach(problem::setProperty);

    HttpHeaders headers = new HttpHeaders();
    if (e.retryAfterSeconds() != null) {
      headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()));
    }

    if (log.isInfoEnabled()) {
      log.info(
          "api_error code={} status={} path={}",
          e.code().wireCode(),
          e.code().status().value(),
          request.getRequestURI());
    }
    return ResponseEntity.status(e.code().status()).headers(headers).body(problem);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    BindException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ProblemDetail> handleBadRequest(Exception e, HttpServletRequest request) {
    String detail =
        switch (e) {
          case MethodArgumentNotValidException ex ->
              ex.getBindingResult().getFieldErrors().stream()
                  .map(f -> f.getField() + " " + f.getDefaultMessage())
                  .collect(Collectors.joining("; "));
          case MissingRequestHeaderException ex -> "missing header " + ex.getHeaderName();
          default -> ErrorCode.INVALID_REQUEST.defaultDetail();
        };
    return ResponseEntity.badRequest().body(problem(ErrorCode.INVALID_REQUEST, detail, request));
  }

  /**
   * An unknown path is a 404, not a 500.
   *
   * <p>Spring raises {@code NoResourceFoundException} when nothing handles a request and the
   * static-resource handler cannot find a file either. Without this, the catch-all below turned
   * every typo'd URL into a 500 with an error-level log line and a stack trace — noise that would
   * have made a real 500 invisible during a load test.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ProblemDetail> handleNotFound(Exception e, HttpServletRequest request) {
    log.debug("no handler for {} {}", request.getMethod(), request.getRequestURI());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such endpoint");
    problem.setType(PROBLEM_BASE.resolve("not_found"));
    problem.setTitle("Not found");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "not_found");
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
  }

  /**
   * The database connection pool is exhausted. This is load shedding, not a bug.
   *
   * <p>It surfaced as 500 until this handler existed, nearly two thousand times in the first
   * fifty-run flash-sale suite. A 500 tells a client nothing it can act on and, worse, cannot be
   * distinguished from a real fault — so "the service is at capacity" and "the service is broken"
   * looked identical on the dashboard, when they should page different people.
   *
   * <p>503 with {@code Retry-After} says what to do. It is 503 rather than 500 because the request
   * never ran: there was no connection to run it on, so a retry is safe with or without an
   * idempotency key.
   */
  @ExceptionHandler({
    CannotGetJdbcConnectionException.class,
    CannotCreateTransactionException.class,
    QueryTimeoutException.class
  })
  public ResponseEntity<ProblemDetail> handleOverloaded(Exception e, HttpServletRequest request) {
    // INFO without a stack trace: under a burst this fires thousands of times, and thousands of
    // stack traces would bury the genuine 500 somebody needs to find.
    log.info(
        "shedding load on {} {}: {}",
        request.getMethod(),
        request.getRequestURI(),
        e.getClass().getSimpleName());

    ProblemDetail problem =
        problem(ErrorCode.OVERLOADED, "The service is at capacity. Retry shortly.", request);
    problem.setProperty("retryable", true);

    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "1");
    return ResponseEntity.status(ErrorCode.OVERLOADED.status()).headers(headers).body(problem);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
    // A committed response — an event stream, or anything part-written — cannot take a
    // problem+json body. Attempting it produces a second, more confusing failure about a missing
    // message converter stacked on top of the first, which is what the stream path used to do on
    // every closed tab.
    String accept = request.getHeader("Accept");
    if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
      log.debug("stream request failed after the response was committed: {}", e.toString());
      return null;
    }

    // Anything reaching here is a bug, so it is logged with the stack trace and returns a body
    // that says nothing about the internals.
    log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(problem(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultDetail(), request));
  }

  private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setType(PROBLEM_BASE.resolve(code.wireCode()));
    problem.setTitle(code.defaultDetail());
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code.wireCode());
    return problem;
  }
}
