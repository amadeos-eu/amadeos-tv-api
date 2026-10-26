package eu.amadeos.tvapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The clock, which the tests replace. */
@Configuration(proxyBeanMethods = false)
class TimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
