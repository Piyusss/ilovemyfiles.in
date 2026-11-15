package in.ilovemyfiles.config;

import in.ilovemyfiles.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ClerkAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final List<GrantedAuthority> ADMIN = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));

    private final UserService userService;
    private final AdminAccess adminAccess;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String clerkId = jwt.getSubject();

        // Maps the Clerk user to our internal id, creating the account on first
        // sign-in. After the first request it is answered from an in-memory
        // cache (see UserService#resolveUserId), not the database.
        UUID userId = userService.resolveUserId(
                clerkId, jwt.getClaimAsString("email"), jwt.getClaimAsString("fullName"));

        // Our UUID is the principal, so controllers take @AuthenticationPrincipal UUID.
        return new UsernamePasswordAuthenticationToken(
                userId, null, adminAccess.isAdmin(clerkId) ? ADMIN : List.of());
    }
}
