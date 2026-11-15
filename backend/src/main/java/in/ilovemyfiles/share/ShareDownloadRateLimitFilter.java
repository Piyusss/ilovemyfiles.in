package in.ilovemyfiles.share;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** At most 20 public share downloads a minute per client IP. */
@Component
public class ShareDownloadRateLimitFilter extends OncePerRequestFilter {

    private static final int REQUESTS_PER_MINUTE = 20;
    private static final Pattern DOWNLOAD_PATH = Pattern.compile("/api/share/[^/]+/download");

    // One bucket per recently seen IP, bounded and expiring. The plain map this
    // replaces grew with every distinct address and was never cleaned up, so
    // forged addresses could fill the heap.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private final int trustedProxyHops;

    public ShareDownloadRateLimitFilter(@Value("${app.trusted-proxy-hops:2}") int trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    private static Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(REQUESTS_PER_MINUTE)
                .refillGreedy(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && DOWNLOAD_PATH.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Bucket bucket = buckets.get(clientIp(request), ip -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many downloads. Try again in a minute.\",\"status\":429}");
        }
    }

    /**
     * The client's address as the first trusted proxy saw it. Every proxy in front
     * of the app appends the address it got the request from to X-Forwarded-For,
     * so only the last {@code trustedProxyHops} entries were written by proxies we
     * trust; anything to their left came from the client and can be forged (the
     * leftmost entry, which this used to read, is whatever the client sent). On
     * Render there are two hops: Cloudflare, then Render's own proxy. A header
     * with fewer entries didn't pass through them, so it isn't trusted at all.
     */
    String clientIp(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header != null && trustedProxyHops > 0) {
            List<String> hops = Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(hop -> !hop.isEmpty())
                    .toList();
            if (hops.size() >= trustedProxyHops) {
                return hops.get(hops.size() - trustedProxyHops);
            }
        }
        return request.getRemoteAddr();
    }
}
