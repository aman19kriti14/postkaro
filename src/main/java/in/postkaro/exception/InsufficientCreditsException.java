package in.postkaro.exception;

import lombok.Getter;

@Getter
public class InsufficientCreditsException extends RuntimeException {

	private final int required;
	private final int available;

	public InsufficientCreditsException(int required, int available) {
		super("Not enough credits. This needs " + required + ", you have " + available + ".");
		this.required = required;
		this.available = available;
	}
}