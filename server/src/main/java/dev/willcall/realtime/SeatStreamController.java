package dev.willcall.realtime;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live seat stream.
 *
 * <p>{@code X-Accel-Buffering: no} and {@code Cache-Control: no-transform} are not decoration. Any
 * intermediary that buffers or recompresses this response turns the 50 ms coalescing window into
 * buffer-flush latency, and the propagation percentile then measures the proxy rather than the
 * service. The local edge proxy also has {@code proxy_buffering off} for this path; the headers
 * cover proxies this repository does not configure.
 */
@RestController
@RequestMapping("/api/events/{eventId}")
public class SeatStreamController {

  private final SeatStreamService streams;

  public SeatStreamController(SeatStreamService streams) {
    this.streams = streams;
  }

  /**
   * Opens the stream.
   *
   * <p>Failures are deliberately resolved <em>before</em> the emitter is created. Once the response
   * is committed as {@code text/event-stream}, Spring cannot write a problem+json body into it, and
   * an exception escaping later produces a second, more confusing failure about a missing message
   * converter on top of the first.
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream(
      @PathVariable UUID eventId,
      @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventIdHeader,
      @RequestParam(value = "lastEventId", required = false) Long lastEventIdParam) {

    // EventSource sends the header automatically on reconnect. The query parameter exists for k6
    // and for curl, neither of which is an EventSource.
    Long lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
        .header(HttpHeaders.CONNECTION, "keep-alive")
        .header("X-Accel-Buffering", "no")
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(streams.open(eventId, lastEventId));
  }

  /** The snapshot on its own, for a client that wants to resync without reconnecting. */
  @GetMapping("/snapshot")
  public SeatSnapshotMessage snapshot(@PathVariable UUID eventId) {
    return streams.snapshotOf(eventId);
  }
}
