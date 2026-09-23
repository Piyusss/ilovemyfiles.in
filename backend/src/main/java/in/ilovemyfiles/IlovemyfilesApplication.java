package in.ilovemyfiles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableCaching
public class IlovemyfilesApplication {

    public static void main(String[] args) {
        // Every LocalDateTime in the app (and the JDBC session zone) is UTC, whatever
        // the host's clock is set to. JacksonConfig relies on this to label them "Z".
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IlovemyfilesApplication.class, args);
    }
}
