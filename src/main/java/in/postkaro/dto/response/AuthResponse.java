package in.postkaro.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private UserResponse user;
    private TokenResponse tokens;

    @Data
    @Builder
    public static class TokenResponse {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
    }
}
