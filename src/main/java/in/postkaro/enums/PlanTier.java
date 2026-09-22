package in.postkaro.enums;

public enum PlanTier {

	TRIAL(0, 100, 1), STARTER(599, 300, 1), GROWTH(2999, 2000, 5), PRO(5999, 5000, 12), AGENCY(9999, 12000, 25);

	private final int priceInr;
	private final int monthlyCredits;
	private final int accountLimit;

	PlanTier(int priceInr, int monthlyCredits, int accountLimit) {
		this.priceInr = priceInr;
		this.monthlyCredits = monthlyCredits;
		this.accountLimit = accountLimit;
	}

	public int getPriceInr() {
		return priceInr;
	}

	public int getMonthlyCredits() {
		return monthlyCredits;
	}

	public int getAccountLimit() {
		return accountLimit;
	}

	public boolean isPaid() {
		return this != TRIAL;
	}
}