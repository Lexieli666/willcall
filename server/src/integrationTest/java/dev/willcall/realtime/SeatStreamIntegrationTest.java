package dev.willcall.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.willcall.support.HttpIntegrationTestBase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The stream over real HTTP, read with a real client.
 *
 * <p>Using {@code java.net.http} rather than a mock: an SSE bug is usually in framing, buffering or
 * connection lifecycle, and none of those exist in a test double. What is asserted is the wire
 * format — event names, {@code id:} lines, the sequence range on a coalesced frame — because that
 * is the contract the browser depends on.
 */
class SeatStreamIntegrationTest extends HttpIntegrationTestBase {

  /** Reads an SSE stream on a background thread and records the frames. */
  private static final class StreamReader implements AutoCloseable {
    private final List<String[]> frames = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private volatile boolean stopped;
    private Thread thread;

    void start(String url) {
      thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      HttpResponse<java.io.InputStream> response =
                          client.send(
                              HttpRequest.newBuilder(URI.create(url))
                                  .header("Accept", "text/event-stream")
                                  .timeout(Duration.ofSeconds(30))
                                  .build(),
                              HttpResponse.BodyHandlers.ofInputStream());

                      try (BufferedReader reader =
                          new BufferedReader(
                              new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String eventName = null;
                        String id = null;
                        StringBuilder data = new StringBuilder();
                        String line;
                        while (!stopped && (line = reader.readLine()) != null) {
                          if (line.isEmpty()) {
                            if (eventName != null) {
                              frames.add(new String[] {eventName, id, data.toString()});
                            }
                            eventName = null;
                            id = null;
                            data.setLength(0);
                          } else if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                          } else if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                          } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                          } else if (line.startsWith(":")) {
                            frames.add(new String[] {"comment", null, line.substring(1).trim()});
                          }
                        }
                      }
                    } catch (Exception e) {
                      // A closed stream at the end of a test is not a failure.
                    }
                  });
    }

    List<String[]> framesOfType(String name) {
      List<String[]> out = new ArrayList<>();
      for (String[] frame : frames) if (name.equals(frame[0])) out.add(frame);
      return out;
    }

    @Override
    public void close() {
      stopped = true;
      if (thread != null) thread.interrupt();
      client.close();
    }
  }

  private String eventId;
  private StreamReader reader;

  @BeforeEach
  void setUpEvent() {
    eventId = createEvent(3, 8, 120, 8);
    reader = new StreamReader();
  }

  @AfterEach
  void closeStream() {
    if (reader != null) reader.close();
  }

  private String streamUrl(String query) {
    return "http://127.0.0.1:" + port + "/api/events/" + eventId + "/stream" + query;
  }

  private List<String> availableSeatIds(int count) {
    return jdbc.queryForList(
        "select id::text from seats where event_id = ?::uuid and status = 'AVAILABLE' order by id limit ?",
        String.class,
        eventId,
        count);
  }

  private void holdSeats(List<String> seatIds, String buyer) {
    String body =
        "{\"seatIds\":["
            + String.join(",", seatIds.stream().map(id -> "\"" + id + "\"").toList())
            + "]}";
    ResponseEntity<String> response =
        post("/api/events/" + eventId + "/holds", body, buyer, "k-" + buyer);
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
  }

  /** Publishes whatever is pending, since the relay's scheduler is off in tests. */
  private void publishPending() {
    ResponseEntity<String> response = post("/api/admin/test/flush-stream", "", "buyer-admin", null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("a stream opens with a snapshot carrying every seat and the current sequence")
  void opensWithASnapshot() {
    reader.start(streamUrl(""));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("snapshot")).hasSize(1));

    String[] snapshot = reader.framesOfType("snapshot").get(0);
    assertThat(snapshot[1]).isEqualTo("0");
    assertThat(snapshot[2]).contains("\"coalesceWindowMs\":50");
    assertThat(snapshot[2]).contains("\"available\":24");
    // Every seat, not a diff: a client that has just connected has nothing to diff against.
    assertThat(snapshot[2].split("\"status\"", -1)).hasSize(25);
  }

  @Test
  @DisplayName("a hold of three seats arrives as one frame spanning three sequence numbers")
  void coalescedFrameCarriesItsRange() {
    reader.start(streamUrl(""));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("snapshot")).hasSize(1));

    holdSeats(availableSeatIds(3), "buyer-00000001");
    drainOutbox();
    publishPending();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("delta")).isNotEmpty());

    String data = reader.framesOfType("delta").get(0)[2];
    // Three seats consume three sequence numbers and arrive as one frame. Without the range the
    // client would see a jump from 0 to 3 and correctly call it a gap.
    assertThat(data).contains("\"fromSequence\":1").contains("\"sequence\":3");
    assertThat(data.split("\"status\"", -1)).hasSize(4);
  }

  @Test
  @DisplayName("reconnecting with a cursor replays the history instead of a whole snapshot")
  void reconnectWithCursorGetsCatchUp() {
    holdSeats(availableSeatIds(2), "buyer-00000002");
    drainOutbox();

    // The client was away for sequences 1 and 2 and asks to resume from 0.
    reader.start(streamUrl("?lastEventId=0"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("delta")).hasSize(2));

    List<String[]> deltas = reader.framesOfType("delta");
    assertThat(deltas.get(0)[1]).isEqualTo("1");
    assertThat(deltas.get(1)[1]).isEqualTo("2");
    assertThat(reader.framesOfType("snapshot")).isEmpty();
  }

  @Test
  @DisplayName("a cursor the server can no longer satisfy gets a resync and a fresh snapshot")
  void unsatisfiableCursorGetsResync() {
    holdSeats(availableSeatIds(1), "buyer-00000003");
    drainOutbox();

    // A cursor from a sequence the server never had, which is what a client that reconnects to a
    // rebuilt event looks like.
    reader.start(streamUrl("?lastEventId=9999"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("snapshot")).hasSize(1));

    assertThat(reader.framesOfType("resync")).hasSize(1);
    assertThat(reader.framesOfType("resync").get(0)[2]).contains("history_unavailable");
  }

  @Test
  @DisplayName("a client already at the head gets a snapshot rather than nothing at all")
  void cursorAtHeadGetsSnapshot() {
    reader.start(streamUrl("?lastEventId=0"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(reader.framesOfType("snapshot")).hasSize(1));

    assertThat(reader.framesOfType("delta")).isEmpty();
  }

  @Test
  @DisplayName("the snapshot endpoint returns the same shape as the opening frame")
  void snapshotEndpointMatches() {
    ResponseEntity<String> response = get("/api/events/" + eventId + "/snapshot", "buyer-00000001");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("\"coalesceWindowMs\":50").contains("\"seats\"");
  }

  @Test
  @DisplayName("the stream sets the headers that stop a proxy from buffering it")
  void antiBufferingHeadersArePresent() {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/events/" + eventId + "/snapshot",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(headers("buyer-00000001", null)),
            String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  /** Publishes the outbox, since the relay's own scheduler is disabled in the test profile. */
  private void drainOutbox() {
    ResponseEntity<String> response = post("/api/admin/test/drain-outbox", "", "buyer-admin", null);
    assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
  }
}
