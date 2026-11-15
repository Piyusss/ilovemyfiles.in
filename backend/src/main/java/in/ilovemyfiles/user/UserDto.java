package in.ilovemyfiles.user;

import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private UUID id;
    private String email;
    private String fullName;
    private Long storageUsed;
    private Long storageLimit;
    private LocalDateTime createdAt;
    /** May view and run the maintenance jobs (see AdminAccess). */
    private boolean admin;
}
