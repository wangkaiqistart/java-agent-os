package com.aegisos.boot;

import com.aegisos.web.baseline.WebBaselineConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Runnable Spring Boot entry point for the AegisOS runtime. */
@SpringBootApplication
@Import(WebBaselineConfiguration.class)
public class AegisOsApplication {

  public static void main(String[] args) {
    SpringApplication.run(AegisOsApplication.class, args);
  }
}
