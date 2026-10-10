package com.planet.evolution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlanetEvolutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanetEvolutionApplication.class, args);
    }
}
