package in.postkaro.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CampaignFlowResponse(UUID id, String name, String brief, String offer, String goal, String cadence,
		String tone, String visuals, String look, List<String> channels, LocalDate startsOn, LocalDate endsOn,
		String status, int currentStep, boolean autoPublish, List<FlowPost> posts) {

	public record FlowPost(UUID id, String title, String caption, String format, String stage, List<String> channels,
			LocalDate date, // IST
			String time, // HH:mm, IST
			boolean approved, String visualUrl, String status) {
	}

	public record Check(String key, boolean ok, String label, String detail) {
	}

	public record Checks(List<Check> items, boolean canPublish) {
	}

	public record ListItem(UUID id, String name, String group, // RUNNING, UPCOMING, UNFINISHED, CLOSED
			LocalDate startsOn, LocalDate endsOn, List<String> channels, int currentStep, long total, long published,
			long scheduled, long failed, Long reach, // null until insights are connected
			Double engagementRate, java.time.Instant createdAt) {
	}
}