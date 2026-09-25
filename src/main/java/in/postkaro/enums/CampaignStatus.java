package in.postkaro.enums;

public enum CampaignStatus {
	DRAFT, // still in the builder
	SCHEDULED, // published from step 4
	COMPLETED, // end date passed
	ARCHIVED,
	STOPPED
}