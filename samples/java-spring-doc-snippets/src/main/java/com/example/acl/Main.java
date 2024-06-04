package com.example.acl;

import kalix.javasdk.annotations.Acl;
import kalix.javasdk.annotations.KalixService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@KalixService
@SpringBootApplication
// Allow all other Kalix services deployed in the same project to access the components of this
// Kalix service, but disallow access from the internet. This can be overridden explicitly
// per component or method using annotations.
// tag::acl[]
@Acl(allow = @Acl.Matcher(service = "*"))
public class Main {
// end::acl[]

  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) {
    logger.info("Starting Kalix Application");
    SpringApplication.run(Main.class, args);
  }
}