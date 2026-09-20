package dev.willcall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the Willcall modular monolith. */
@SpringBootApplication
@EnableScheduling
public class WillcallApplication {

  public static void main(String[] args) {
    SpringApplication.run(WillcallApplication.class, args);
  }
}
