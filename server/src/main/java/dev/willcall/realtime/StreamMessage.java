package dev.willcall.realtime;

/**
 * One SSE frame, already serialised.
 *
 * <p>Serialising once per event rather than once per connection matters at five thousand
 * connections: the alternative is five thousand identical JSON encodings of the same delta.
 *
 * @param name the SSE {@code event:} name, or null for a comment-only frame
 * @param id the SSE {@code id:} value, which the browser echoes back as {@code Last-Event-ID}
 * @param data the JSON payload, or the comment text when {@code name} is null
 */
public record StreamMessage(String name, Long id, String data) {

  public static final String SNAPSHOT = "snapshot";
  public static final String DELTA = "delta";
  public static final String RESYNC = "resync";

  public static StreamMessage snapshot(long sequence, String json) {
    return new StreamMessage(SNAPSHOT, sequence, json);
  }

  public static StreamMessage delta(long sequence, String json) {
    return new StreamMessage(DELTA, sequence, json);
  }

  public static StreamMessage resync(long sequence, String reason) {
    return new StreamMessage(
        RESYNC, sequence, "{\"reason\":\"" + reason + "\",\"sequence\":" + sequence + "}");
  }

  public boolean isComment() {
    return name == null;
  }
}
