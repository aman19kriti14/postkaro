package in.postkaro.enums;

public enum CreditAction {

	CAPTION(1, "Caption generation"), REFINE_CAPTION(1, "Caption refinement"), POSTER_COPY(1, "Poster copy"),
	IDEA_SET(2, "AI studio ideas"), BRAND_ANALYZE(3, "Brand analysis"), IMAGE(5, "Image generation"),
	CAROUSEL_SLIDE(5, "Carousel slide"), CAMPAIGN_PLAN(10, "Campaign plan"), VIDEO(50, "Video generation"),

	// Not spends: these add or adjust credits in the ledger
	MONTHLY_GRANT(0, "Monthly credits"), TOPUP(0, "Credit top-up"), REFUND(0, "Refund for failed generation"),
	ADMIN_ADJUST(0, "Manual adjustment");

	private final int cost;
	private final String label;

	CreditAction(int cost, String label) {
		this.cost = cost;
		this.label = label;
	}

	public int getCost() {
		return cost;
	}

	/** Cost for actions billed per unit, like carousel slides. */
	public int costFor(int units) {
		return cost * Math.max(1, units);
	}

	public String getLabel() {
		return label;
	}

	public boolean isSpend() {
		return cost > 0;
	}
}