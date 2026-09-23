package in.postkaro.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class OtpDtos {

	public record VerifyOtpRequest(@NotBlank @Email String email, @NotBlank String code) {
	}

	public record ResendOtpRequest(@NotBlank @Email String email) {
	}
}