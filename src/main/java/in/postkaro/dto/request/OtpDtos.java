package in.postkaro.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OtpDtos {

	public record VerifyOtpRequest(@NotBlank @Email String email, @NotBlank String code) {
	}

	public record ResendOtpRequest(@NotBlank @Email String email) {
	}

	public record ForgotPasswordRequest(@NotBlank @Email String email) {
	}

	public record ResetPasswordRequest(@NotBlank @Email String email, @NotBlank String code,
			@NotBlank @Size(min = 8, message = "Password must be at least 8 characters.") String newPassword) {
	}
}