package in.ilovemyfiles.chunk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InitUploadRequest {

    @NotBlank(message = "File name is required")
    @Size(max = 255, message = "File name must be 255 characters or less")
    private String fileName;

    @Positive(message = "File size must be positive")
    private long fileSize;

    @Positive(message = "Total chunks must be positive")
    private int totalChunks;

    @NotBlank(message = "Content type is required")
    @Size(max = 255, message = "Content type must be 255 characters or less")
    private String contentType;

    private UUID folderId;

    /**
     * SHA-256 (lowercase hex) of the full file. Optional; lets the same user's
     * re-uploads of identical content skip the transfer.
     */
    @Pattern(regexp = "[0-9a-f]{64}", message = "Hash must be a lowercase hex SHA-256")
    private String hash;
}
