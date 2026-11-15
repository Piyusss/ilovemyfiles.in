package in.ilovemyfiles.share;

import in.ilovemyfiles.file.FileEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SharedLinkController {

    private final SharedLinkService sharedLinkService;

    // ── Auth required ─────────────────────────────────────────────────────────

    @PostMapping("/api/files/{fileId}/share")
    public ResponseEntity<SharedLinkDto> create(
            @PathVariable UUID fileId,
            @RequestBody CreateShareLinkRequest request,
            @AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(sharedLinkService.create(fileId, userId, request));
    }

    @GetMapping("/api/files/{fileId}/shares")
    public ResponseEntity<List<SharedLinkDto>> list(
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(sharedLinkService.list(fileId, userId));
    }

    /** All share links the current user has created (for the "Shared" tab). */
    @GetMapping("/api/shares")
    public ResponseEntity<List<SharedLinkDto>> listAll(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(sharedLinkService.listAllForUser(userId));
    }

    @DeleteMapping("/api/share/{token}")
    public ResponseEntity<Map<String, String>> revoke(
            @PathVariable String token,
            @AuthenticationPrincipal UUID userId) {
        sharedLinkService.delete(token, userId);
        return ResponseEntity.ok(Map.of("message", "Link revoked"));
    }

    // ── Public ────────────────────────────────────────────────────────────────

    @GetMapping("/api/share/{token}")
    public ResponseEntity<PublicFileInfoDto> info(@PathVariable String token) {
        return ResponseEntity.ok(sharedLinkService.getPublicInfo(token));
    }

    /**
     * Validates the link (password / expiry / rate limits), then returns a presigned
     * download URL. POST, so the password travels in the body: as a GET query
     * parameter it ended up in proxy logs and browser history.
     */
    @PostMapping("/api/share/{token}/download")
    public ResponseEntity<Map<String, String>> download(
            @PathVariable String token,
            @RequestBody(required = false) DownloadShareRequest request) {
        String password = request != null ? request.getPassword() : null;
        FileEntity file = sharedLinkService.validateDownload(token, password);
        String url = sharedLinkService.presignedUrlFor(file);
        return ResponseEntity.ok(Map.of("url", url, "fileName", file.getName()));
    }
}
