package com.systemdesign.ticketmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
public class TicketmasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketmasterApplication.class, args);
    }
}
