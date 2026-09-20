package dev.willcall;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Placeholder unit test so the fast suite is never empty. Context loading is covered by the
 * Testcontainers integration suite, which has a real PostgreSQL to connect to; a context test here
 * would only assert that mocks can be wired.
 */
class WillcallApplicationTest {

  @Test
  void mainClassIsAnnotatedAsASpringBootApplication() {
    assertThat(
            WillcallApplication.class.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class))
        .isNotNull();
  }
}
