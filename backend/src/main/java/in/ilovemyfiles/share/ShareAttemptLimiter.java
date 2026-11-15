package in.ilovemyfiles.share;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limits password guesses per share link. The per-IP filter alone can be beaten
 * by spreading guesses over many addresses; this counts attempts on the link
 * itself, wherever they come from. The trade-off: someone hammering a link can
 * lock out its real recipients until the window refills.
 */
@Component
public class ShareAttemptLimiter {

    static final int ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(WINDOW)
            .build();

    /** Uses one attempt on this link; false once the link has none left. */
    public boolean tryAttempt(String token) {
        return buckets.get(token, t -> newBucket()).tryConsume(1);
    }

    /** Gives back the attempt a correct password used, so real downloads never lock a link. */
    public void refund(String token) {
        Bucket bucket = buckets.getIfPresent(token);
        if (bucket != null) bucket.addTokens(1);
    }

    private static Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder().capacity(ATTEMPTS).refillGreedy(ATTEMPTS, WINDOW).build();
        return Bucket.builder().addLimit(limit).build();
    }
}
