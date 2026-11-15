package in.ilovemyfiles.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may use admin-only endpoints (the maintenance jobs, which act on every
 * user's data). Configured as a comma-separated list of Clerk user ids in
 * {@code app.admin-clerk-ids} (env ADMIN_CLERK_IDS); empty means nobody. The
 * Clerk id is the verified JWT subject, so a request can't claim it.
 */
@Component
public class AdminAccess {

    private final Set<String> adminClerkIds;

    public AdminAccess(@Value("${app.admin-clerk-ids:}") String adminClerkIds) {
        this.adminClerkIds = Arrays.stream(adminClerkIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String clerkId) {
        return clerkId != null && adminClerkIds.contains(clerkId);
    }
}
