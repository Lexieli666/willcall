package dev.willcall.platform.web;

import java.util.Map;

/** An expected failure with a defined status code, as opposed to a bug. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ErrorCode code;
  private final transient Map<String, Object> extra;
  private final transient Integer retryAfterSeconds;

  public ApiException(ErrorCode code) {
    this(code, code.defaultDetail(), Map.of(), null);
  }

  public ApiException(ErrorCode code, String detail) {
    this(code, detail, Map.of(), null);
  }

  public ApiException(ErrorCode code, String detail, Map<String, Object> extra) {
    this(code, detail, extra, null);
  }

  public ApiException(
      ErrorCode code, String detail, Map<String, Object> extra, Integer retryAfterSeconds) {
    super(detail);
    this.code = code;
    this.extra = Map.copyOf(extra);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public ErrorCode code() {
    return code;
  }

  public Map<String, Object> extra() {
    return extra;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
