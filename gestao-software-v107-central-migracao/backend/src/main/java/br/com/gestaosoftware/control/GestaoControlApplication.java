package br.com.gestaosoftware.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GestaoControlApplication {
    public static void main(String[] args) { SpringApplication.run(GestaoControlApplication.class, args); }
}
