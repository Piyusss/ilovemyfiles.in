package in.ilovemyfiles.notification;

import in.ilovemyfiles.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final UserService userService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                // A connection without a user can never receive a notification.
                // Refusing it makes the client reconnect (fetching a token) instead
                // of sitting on a socket that silently gets nothing.
                throw new IllegalArgumentException("Missing bearer token");
            }
            try {
                var jwt = jwtDecoder.decode(authHeader.substring(7));
                UUID userId = userService.resolveUserId(
                        jwt.getSubject(),
                        jwt.getClaimAsString("email"),
                        jwt.getClaimAsString("fullName"));
                // Set a Principal so SimpMessagingTemplate.convertAndSendToUser() can route by userId
                accessor.setUser(new StompPrincipal(userId.toString()));
            } catch (Exception e) {
                log.warn("WebSocket CONNECT rejected, invalid JWT: {}", e.getMessage());
                throw new IllegalArgumentException("Invalid or expired token");
            }
        }
        return message;
    }
}
