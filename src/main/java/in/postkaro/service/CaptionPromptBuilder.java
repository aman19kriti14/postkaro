package in.postkaro.service;

import org.springframework.stereotype.Component;

import in.postkaro.entity.Language;

@Component
public class CaptionPromptBuilder {

	public String system(Language language, String tone, java.util.List<String> channels) {
		StringBuilder sb = new StringBuilder();
		sb.append("You write social media captions for Indian creators and small brands. ");
		sb.append("Tone: ").append(tone).append(". ");
		sb.append("Target platforms: ").append(String.join(", ", channels)).append(". ");
		sb.append(languageInstruction(language));
		sb.append(" Keep it under 400 characters. ");
		sb.append("Add 3-5 relevant hashtags at the end. ");
		sb.append("Do not use markdown, quotes around the caption, or any preamble. ");
		sb.append("Return only the caption text.");
		return sb.toString();
	}

	private String languageInstruction(Language language) {
		return switch (language) {
		case ENGLISH -> "Write in English as spoken in India.";
		case HINGLISH -> "Write in Hinglish: Hindi words in Roman script mixed naturally with English, "
				+ "the way urban Indians actually text. Do NOT use Devanagari script.";
		default -> "Write entirely in " + language.getDisplayName() + ", in its native script. "
				+ "Use natural, everyday " + language.getDisplayName()
				+ " as spoken today, not formal or literary register. "
				+ "Keep widely-used English loanwords in Roman script where a native speaker would naturally use them. "
				+ "Do not transliterate brand names or product names — keep them exactly as given.";
		};
	}

	public String user(String topic, String brandName) {
		StringBuilder sb = new StringBuilder();
		sb.append("Write a caption about: ").append(topic);
		if (brandName != null && !brandName.isBlank()) {
			sb.append("\nBrand: ").append(brandName);
		}
		return sb.toString();
	}
    public String posterSystem(Language language) {
        StringBuilder sb = new StringBuilder();
        sb.append("You write copy for social media posters for Indian creators and small brands. ");
        sb.append(languageInstruction(language));
        sb.append(" Produce three fields: ");
        sb.append("headline (max 6 words, the hook), ");
        sb.append("subhead (max 14 words, the supporting detail), ");
        sb.append("cta (max 4 words, the action). ");
        sb.append("Poster text is read at a glance — be short and concrete, never a full sentence where a phrase works. ");
        sb.append("Return ONLY a JSON object with keys headline, subhead, cta. ");
        sb.append("No markdown, no code fences, no explanation.");
        return sb.toString();
    }
}