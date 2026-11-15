package in.ilovemyfiles.config;

import in.ilovemyfiles.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClerkAuthConverterTest {

    @Mock UserService userService;

    private final AdminAccess adminAccess = new AdminAccess(" user_admin , user_ops ");

    private ClerkAuthConverter converter() {
        return new ClerkAuthConverter(userService, adminAccess);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token").header("alg", "RS256")
                .subject(subject).claim("email", subject + "@x.com").build();
    }

    @Test
    void principalIsOurUserId_andRegularUsersGetNoRole() {
        UUID id = UUID.randomUUID();
        when(userService.resolveUserId("user_1", "user_1@x.com", null)).thenReturn(id);

        var auth = converter().convert(jwt("user_1"));

        assertEquals(id, auth.getPrincipal());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @Test
    void configuredAdminsGetTheAdminRole() {
        when(userService.resolveUserId(eq("user_ops"), any(), any())).thenReturn(UUID.randomUUID());

        var auth = converter().convert(jwt("user_ops"));

        assertEquals(List.of("ROLE_ADMIN"),
                auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void anEmptyAdminListMeansNobody() {
        assertFalse(new AdminAccess("").isAdmin("user_admin"));
        assertFalse(adminAccess.isAdmin(null));
    }
}
