package in.postkaro.entity;

public enum Language {
	ENGLISH("en-IN", "English", false), HINGLISH("hi-IN", "Hinglish (Roman script)", true),
	HINDI("hi-IN", "Hindi", true), TAMIL("ta-IN", "Tamil", true), TELUGU("te-IN", "Telugu", true),
	KANNADA("kn-IN", "Kannada", true), MALAYALAM("ml-IN", "Malayalam", true), MARATHI("mr-IN", "Marathi", true),
	BENGALI("bn-IN", "Bengali", true), GUJARATI("gu-IN", "Gujarati", true), PUNJABI("pa-IN", "Punjabi", true),
	ODIA("or-IN", "Odia", true);

	private final String code;
	private final String displayName;
	private final boolean useIndicModel;

	Language(String code, String displayName, boolean useIndicModel) {
		this.code = code;
		this.displayName = displayName;
		this.useIndicModel = useIndicModel;
	}

	public String getCode() {
		return code;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isUseIndicModel() {
		return useIndicModel;
	}
}