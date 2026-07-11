package com.cineseekerr.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CineSeekerrBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CineSeekerrBotApplication.class, args);
    }
}
