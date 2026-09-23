package in.ilovemyfiles.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serializes every LocalDateTime in API responses and WebSocket notifications as
 * UTC with an explicit "Z". Without the suffix, browsers parse the value as their
 * own local time, so an upload made seconds ago in IST showed up as "5h ago".
 * Valid only because the JVM is pinned to UTC in IlovemyfilesApplication.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcTimestamps() {
        return builder -> builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(UTC_ISO));
    }
}
