package in.ilovemyfiles.share;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of a public share download. */
@Getter
@Setter
@NoArgsConstructor
public class DownloadShareRequest {
    private String password; // null for links without one
}
