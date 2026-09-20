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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
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
