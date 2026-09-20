package dev.willcall.waitingroom;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the admission check.
 *
 * <p>Declared here rather than in a shared web configuration so that turning the waiting room off
 * is a matter of not having this module, not of remembering to unregister something.
 */
@Configuration
public class WaitingRoomWebConfig implements WebMvcConfigurer {

  private final AdmissionInterceptor admissionInterceptor;

  public WaitingRoomWebConfig(AdmissionInterceptor admissionInterceptor) {
    this.admissionInterceptor = admissionInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(admissionInterceptor).addPathPatterns("/api/events/*/holds");
  }
}
