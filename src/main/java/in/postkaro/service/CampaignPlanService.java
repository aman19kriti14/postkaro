package in.postkaro.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CampaignPlanService {

	private static final int MAX_POSTS = 30;
	private static final String[] TIMES = { "11:30", "18:00", "09:15", "13:00" };

	private final RestTemplate http = new RestTemplate();
	private final ObjectMapper json = new ObjectMapper();

	@Value("${OPENAI_API_KEY}")
	private String openAiKey;

	public List<Map<String, Object>> buildPlan(Map<String, Object> b) {
		String name = str(b, "name");
		String brief = str(b, "brief");
		String offer = str(b, "offer");
		String goal = str(b, "goal", "sales");
		String cadence = str(b, "cadence", "steady");
		String tone = str(b, "tone", "warm");
		String visuals = str(b, "visuals", "ai_all");
		List<String> channels = list(b, "channels");

		if (brief.isBlank())
			throw bad("Describe what you're promoting");
		if (channels.isEmpty())
			throw bad("Pick at least one channel");

		LocalDate start = date(b, "startsOn");
		LocalDate end = date(b, "endsOn");
		if (end.isBefore(start))
			throw bad("The end date is before the start date");
		if (end.isBefore(LocalDate.now()))
			throw bad("This campaign window has already ended");

		LocalDate earliest = LocalDate.now().plusDays(1);
		LocalDate planStart = start.isBefore(earliest) ? earliest : start;
		if (planStart.isAfter(end))
			throw bad("There are no days left in this window to post");

		// How many posts, spread evenly across the window
		long days = ChronoUnit.DAYS.between(start, end) + 1;
		int perWeek = switch (cadence) {
		case "light" -> 2;
		case "heavy" -> 7;
		default -> 4;
		};
		int count = (int) Math.min(MAX_POSTS, Math.max(1, Math.round(days * perWeek / 7.0)));

		List<String> stages = new ArrayList<>();
		for (int i = 0; i < count; i++)
			stages.add(stageFor(i, count));

		List<String> formats = switch (visuals) {
		case "text_only" -> List.of("post");
		case "ai_images", "my_photos" -> List.of("post", "carousel");
		default -> List.of("reel", "carousel", "post");
		};

		List<JsonNode> ideas = askAi(name, brief, offer, goal, tone, formats, stages);

		List<Map<String, Object>> plan = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			JsonNode idea = i < ideas.size() ? ideas.get(i) : null;
			long offset = count == 1 ? 0 : Math.round(i * (days - 1) / (double) (count - 1));

			String format = idea != null ? idea.path("format").asText("post") : "post";
			if (!formats.contains(format))
				format = formats.get(0);

			Map<String, Object> item = new HashMap<>();
			item.put("index", i);
			item.put("date", start.plusDays(offset).toString());
			item.put("time", TIMES[i % TIMES.length]);
			item.put("format", format);
			item.put("stage", stages.get(i));
			item.put("title", idea != null ? idea.path("title").asText("") : "");
			item.put("hook", idea != null ? idea.path("hook").asText("") : "");
			item.put("channels", channels);
			plan.add(item);
		}
		return plan;
	}

	private String stageFor(int i, int count) {
		double pos = count == 1 ? 1 : i / (double) (count - 1);
		if (pos < 0.25)
			return "tease";
		if (pos < 0.55)
			return "explain";
		if (pos < 0.8)
			return "proof";
		return "convert";
	}

	private List<JsonNode> askAi(String name, String brief, String offer, String goal, String tone,
			List<String> formats, List<String> stages) {

		String system = """
				You plan social media campaigns for small Indian brands and creators.
				Reply with JSON only: {"ideas":[{"title":"...","hook":"...","format":"..."}]}.
				Return exactly one idea per stage, in the same order as the stages given.
				title: under 8 words, specific to this product, no hashtags, no emojis.
				hook: one sentence under 20 words saying what the post shows or says.
				format: one of %s.
				Stages mean: tease = build curiosity, explain = what it is and why it matters,
				proof = real use or reactions, convert = a clear reason to act now.
				Do not repeat ideas. Only use facts from the brief; do not invent prices or dates.
				""".formatted(formats);

		String user = """
				Campaign: %s
				Brief: %s
				Offer: %s
				Goal: %s
				Tone: %s
				Stages in order: %s
				""".formatted(name, brief, offer.isBlank() ? "none" : offer, goal, tone, stages);

		Map<String, Object> req = Map.of("model", "gpt-4o-mini", "response_format", Map.of("type", "json_object"),
				"temperature", 0.8, "messages",
				List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(openAiKey);

		try {
			String res = http.postForObject("https://api.openai.com/v1/chat/completions",
					new HttpEntity<>(req, headers), String.class);
			String content = json.readTree(res).path("choices").path(0).path("message").path("content").asText("{}");
			List<JsonNode> out = new ArrayList<>();
			json.readTree(content).path("ideas").forEach(out::add);
			return out;
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"Couldn't generate the plan right now. Try again.");
		}
	}

	// ── helpers ──
	private static ResponseStatusException bad(String msg) {
		return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
	}

	private static String str(Map<String, Object> b, String k) {
		return str(b, k, "");
	}

	private static String str(Map<String, Object> b, String k, String def) {
		Object v = b.get(k);
		return v == null ? def : v.toString().trim();
	}

	@SuppressWarnings("unchecked")
	private static List<String> list(Map<String, Object> b, String k) {
		return b.get(k) instanceof List<?> l ? (List<String>) l : List.of();
	}

	private static LocalDate date(Map<String, Object> b, String k) {
		try {
			return LocalDate.parse(str(b, k));
		} catch (Exception e) {
			throw bad("Pick a valid " + (k.equals("startsOn") ? "start" : "end") + " date");
		}
	}
}