package in.postkaro.exception;

public class SubscriptionRequiredException extends RuntimeException {

	public SubscriptionRequiredException() {
		super("Your free trial has ended. Choose a plan to keep creating and publishing.");
	}
}