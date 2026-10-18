package com.aegisos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runnable Spring Boot entry point for the AegisOS runtime. */
@SpringBootApplication
public class AegisOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisOsApplication.class, args);
    }
}
