package in.ilovemyfiles.notification;

import in.ilovemyfiles.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock JwtDecoder jwtDecoder;
    @Mock UserService userService;
    @Mock MessageChannel channel;

    @InjectMocks WebSocketAuthInterceptor interceptor;

    private static StompHeaderAccessor connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) accessor.setNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void refusesAConnectWithoutAToken() {
        // Used to be let through with no user: a socket that silently got nothing.
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(connect(null)), channel));
        verifyNoInteractions(jwtDecoder, userService);
    }

    @Test
    void refusesAnInvalidToken() {
        when(jwtDecoder.decode("bad")).thenThrow(new BadJwtException("expired"));

        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(message(connect("Bearer bad")), channel));
    }

    @Test
    void aValidTokenBecomesTheSessionUser() {
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("good").header("alg", "RS256").subject("user_1").build();
        when(jwtDecoder.decode("good")).thenReturn(jwt);
        when(userService.resolveUserId("user_1", null, null)).thenReturn(id);
        StompHeaderAccessor accessor = connect("Bearer good");

        interceptor.preSend(message(accessor), channel);

        assertEquals(id.toString(), accessor.getUser().getName());
    }
}
