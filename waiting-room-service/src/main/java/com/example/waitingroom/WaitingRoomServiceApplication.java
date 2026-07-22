package com.example.waitingroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WaitingRoomServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomServiceApplication.class, args);
    }
}
