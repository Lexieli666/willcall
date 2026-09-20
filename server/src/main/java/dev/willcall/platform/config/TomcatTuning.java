package dev.willcall.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Sizes Tomcat for thousands of mostly-idle long-lived connections.
 *
 * <h2>Why the defaults are wrong for this workload</h2>
 *
 * Tomcat allocates an application read buffer and an application write buffer per connection,
 * 8 KiB each by default. That is a good size for a server handling large request bodies and
 * sizeable responses. Here, every request on a stream is a few hundred bytes and every frame is a
 * few hundred more, so 16 KiB per connection is bought and not used — and at five thousand
 * connections it is 80 MiB of it.
 *
 * <p>Measured: retained heap per connection was 90.5 KiB before this change. The figure after it
 * is in the results directory rather than in this comment, because a number in a comment is a
 * number nobody re-measures.
 *
 * <p>The trade is real and bounded: a request or response larger than the buffer costs an extra
 * read or write cycle. For an API whose largest response is a seat map served on a normal
 * connection — not a stream — that is a cost paid rarely, against a saving paid per connection.
 */
@Component
public class TomcatTuning implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  private static final Logger log = LoggerFactory.getLogger(TomcatTuning.class);

  private final int appReadBufferBytes;
  private final int appWriteBufferBytes;

  public TomcatTuning(
      @Value("${willcall.tomcat.app-read-buffer-bytes:2048}") int appReadBufferBytes,
      @Value("${willcall.tomcat.app-write-buffer-bytes:2048}") int appWriteBufferBytes) {
    this.appReadBufferBytes = appReadBufferBytes;
    this.appWriteBufferBytes = appWriteBufferBytes;
  }

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    factory.addConnectorCustomizers(
        connector -> {
          // These are endpoint properties rather than protocol setters, so they go through the
          // connector's string-keyed property bridge. setProperty returns false for a name it does
          // not recognise, and a silently ignored tuning knob is worse than none — so a rejected
          // name is logged rather than assumed to have worked.
          setOrWarn(connector, "socket.appReadBufSize", appReadBufferBytes);
          setOrWarn(connector, "socket.appWriteBufSize", appWriteBufferBytes);
          // Direct buffers live outside the heap, so per-connection cost would be invisible to the
          // retained-heap measurement this project publishes. Keeping them on the heap makes the
          // figure honest, which matters more here than the copy it saves.
          setOrWarn(connector, "socket.directBuffer", false);
          setOrWarn(connector, "socket.directSslBuffer", false);
        });
  }

  private void setOrWarn(org.apache.catalina.connector.Connector connector, String name, Object value) {
    if (!connector.setProperty(name, String.valueOf(value))) {
      log.warn("Tomcat rejected the property {}; per-connection memory will be the default", name);
    }
  }
}
