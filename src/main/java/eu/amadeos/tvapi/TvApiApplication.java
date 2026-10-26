package eu.amadeos.tvapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * One program, two modes: without a profile the REST API for TV, with the profile {@code crawler} one run
 * of the crawler, after which the program ends with 0, or 1 if a source failed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TvApiApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TvApiApplication.class, args);
        if (context.getEnvironment().matchesProfiles("crawler")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
