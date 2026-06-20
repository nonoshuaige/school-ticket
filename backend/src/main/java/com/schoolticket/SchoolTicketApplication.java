package com.schoolticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SchoolTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolTicketApplication.class, args);
    }
}
