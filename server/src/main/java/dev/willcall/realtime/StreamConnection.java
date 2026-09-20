package dev.willcall.realtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One browser's stream.
 *
 * <h2>Why every connection gets its own queue and its own thread</h2>
 *
 * Writing to an {@link SseEmitter} blocks until the bytes reach the socket. If the coalescer wrote
 * to connections directly, one phone on a bad connection would stall the flush for everyone else on
 * that replica. So the coalescer only ever {@code offer}s to a bounded queue, and a virtual thread
 * per connection does the blocking write. Virtual threads make this affordable: five thousand of
 * them cost a few megabytes, where five thousand platform threads would not fit.
 *
 * <h2>What happens when a client cannot keep up</h2>
 *
 * The queue holds 64 messages. On overflow the connection does not block and is not closed: it
 * drops everything queued, sends a single {@code resync}, and carries on. Blocking would punish
 * every other client; closing would produce a reconnect storm, which is worse than a resync storm
 * because a reconnect also costs a snapshot. This is the backpressure rule in
 * docs/realtime-protocol.md section 7.
 */
final class StreamConnection {

  private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);

  /** A poison pill, so the writer thread stops without an interrupt racing a partial write. */
  private static final StreamMessage CLOSE = new StreamMessage("__close", null, "");

  private final String id;
  private final java.util.UUID eventId;
  private final SseEmitter emitter;
  private final ArrayBlockingQueue<StreamMessage> outbound;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean resyncPending = new AtomicBoolean(false);
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong sent = new AtomicLong();
  private volatile Thread writer;

  StreamConnection(String id, java.util.UUID eventId, SseEmitter emitter, int queueCapacity) {
    this.id = id;
    this.eventId = eventId;
    this.emitter = emitter;
    this.outbound = new ArrayBlockingQueue<>(queueCapacity);
  }

  void startWriter() {
    writer =
        Thread.ofVirtual()
            .name("sse-" + id)
            .start(
                () -> {
                  try {
                    while (!closed.get()) {
                      StreamMessage message = outbound.take();
                      // Reference equality is the point: CLOSE is a private sentinel instance
                      // that no other message can equal, so a client cannot forge a shutdown.
                      @SuppressWarnings("ReferenceEquality")
                      boolean isPoisonPill = message == CLOSE;
                      if (isPoisonPill) return;
                      write(message);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
  }

  private void write(StreamMessage message) {
    try {
      if (message.isComment()) {
        emitter.send(SseEmitter.event().comment(message.data()));
      } else {
        SseEmitter.SseEventBuilder builder =
            SseEmitter.event().name(message.name()).data(message.data());
        if (message.id() != null) builder = builder.id(Long.toString(message.id()));
        emitter.send(builder);
      }
      sent.incrementAndGet();
    } catch (Exception e) {
      // The client went away. This is the normal end of a stream, not an error: a tab closing
      // produces it several thousand times during a demo. Any exception here means the same
      // thing, so they are all handled the same way.
      log.debug("stream {} ended while writing: {}", id, e.toString());
      close();
    }
  }

  /**
   * Queues a message, or marks the connection for resync if the queue is full.
   *
   * @return false when the message was dropped
   */
  boolean offer(StreamMessage message) {
    if (closed.get()) return false;
    if (outbound.offer(message)) return true;

    dropped.incrementAndGet();
    if (resyncPending.compareAndSet(false, true)) {
      outbound.clear();
      // The cleared queue always has room, so this offer cannot fail for the same reason.
      outbound.offer(
          StreamMessage.resync(message.id() == null ? 0 : message.id(), "slow_consumer"));
      log.info("stream {} fell behind; queued a resync and dropped its backlog", id);
    }
    return false;
  }

  /** Called once the client has been sent a resync, so the next overflow can arm another. */
  void clearResyncFlag() {
    resyncPending.set(false);
  }

  void close() {
    if (!closed.compareAndSet(false, true)) return;
    outbound.clear();
    outbound.offer(CLOSE);
    try {
      emitter.complete();
    } catch (Exception e) {
      // Catching Exception, not RuntimeException, on purpose. When a browser closes a tab, Spring
      // raises AsyncRequestNotUsableException from complete(), and that is a *checked* IOException
      // subclass. Catching only RuntimeException let it escape the writer thread, where Spring's
      // async dispatch turned an ordinary tab close into an ERROR log line with a stack trace and
      // a second failure trying to render problem+json into a text/event-stream response. During a
      // load test with five thousand connections that is five thousand stack traces for nothing.
      log.debug("stream {} was already closed by the client: {}", id, e.toString());
    }
    Thread current = writer;
    if (current != null) current.interrupt();
  }

  boolean isClosed() {
    return closed.get();
  }

  String id() {
    return id;
  }

  java.util.UUID eventId() {
    return eventId;
  }

  SseEmitter emitter() {
    return emitter;
  }

  long droppedCount() {
    return dropped.get();
  }

  long sentCount() {
    return sent.get();
  }

  int queueDepth() {
    return outbound.size();
  }
}
