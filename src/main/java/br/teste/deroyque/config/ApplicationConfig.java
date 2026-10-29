package br.teste.deroyque.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ActivationProperties.class, ProviderProperties.class})
public class ApplicationConfig {

    /** Datas sempre em UTC; injetável para testes determinísticos. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
