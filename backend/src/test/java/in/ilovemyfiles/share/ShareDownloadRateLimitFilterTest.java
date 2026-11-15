package in.ilovemyfiles.share;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ShareDownloadRateLimitFilterTest {

    // Render: Cloudflare appends the client's address, Render's proxy appends Cloudflare's.
    private final ShareDownloadRateLimitFilter filter = new ShareDownloadRateLimitFilter(2);

    private static MockHttpServletRequest download(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/share/abc/download");
        request.setRemoteAddr("10.0.0.5"); // Render's proxy, as the app sees it
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    @Test
    void readsTheAddressTheFirstTrustedProxyAppended_notWhatTheClientSent() {
        assertEquals("1.1.1.1", filter.clientIp(download("1.1.1.1, 104.22.17.40")));
        // A forged entry in front is ignored (this used to be the address read).
        assertEquals("1.1.1.1", filter.clientIp(download("6.6.6.6, 1.1.1.1, 104.22.17.40")));
    }

    @Test
    void doesNotTrustAHeaderThatSkippedTheProxies() {
        assertEquals("10.0.0.5", filter.clientIp(download("6.6.6.6")));
        assertEquals("10.0.0.5", filter.clientIp(download(null)));
    }

    @Test
    void zeroTrustedHopsIgnoresTheHeader() {
        assertEquals("10.0.0.5", new ShareDownloadRateLimitFilter(0).clientIp(download("1.1.1.1, 2.2.2.2")));
    }

    @Test
    void rotatingForgedAddressesStillHitsTheLimit() throws Exception {
        MockHttpServletResponse response = null;
        for (int i = 0; i < 21; i++) {
            response = new MockHttpServletResponse();
            filter.doFilter(download("203.0.113." + i + ", 1.1.1.1, 104.22.17.40"), response, new MockFilterChain());
        }
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"message\""));
    }

    @Test
    void limitsOnlyDownloads() {
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/share/abc")));
        assertFalse(filter.shouldNotFilter(download(null)));
    }
}
