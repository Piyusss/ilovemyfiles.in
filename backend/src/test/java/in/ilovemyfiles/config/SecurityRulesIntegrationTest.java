package in.ilovemyfiles.config;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The URL rules in SecurityConfig, exercised through the real filter chain. */
@SpringBootTest(properties = {
    "minio.endpoint=http://localhost:9000",
    "minio.access-key=test-access",
    "minio.secret-key=test-secret",
    "minio.bucket=test-bucket",
    "app.share-base-url=http://localhost:5173",
    "cors.allowed-origins=http://localhost:5173",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers
class SecurityRulesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean MinioClient minioClient;
    @MockBean RedisConnectionFactory redisConnectionFactory;
    @MockBean JwtDecoder jwtDecoder;

    @Autowired MockMvc mvc;

    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");

    @Test
    void maintenanceJobsAreForAdminsOnly() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
        // Any signed-in user could list and trigger the global jobs before.
        mvc.perform(get("/api/jobs").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(post("/api/jobs/version-cleanup/run").with(jwt())).andExpect(status().isForbidden());

        mvc.perform(get("/api/jobs").with(jwt().authorities(ADMIN))).andExpect(status().isOk());
        mvc.perform(post("/api/jobs/no-such-job/run").with(jwt().authorities(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shareDownloadsArePublicAndTakeThePasswordInTheBody() throws Exception {
        // Public: no sign-in needed (an unknown link is a 404, not a 401).
        mvc.perform(post("/api/share/no-such-link/download")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isNotFound());
        // The old GET with ?password= is gone, reported as 405 instead of a 500.
        mvc.perform(get("/api/share/no-such-link/download").param("password", "x"))
                .andExpect(status().isMethodNotAllowed());
        // A body that isn't JSON is the client's mistake, not a server error.
        mvc.perform(post("/api/share/no-such-link/download")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
