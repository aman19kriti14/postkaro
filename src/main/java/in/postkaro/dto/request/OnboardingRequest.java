package in.postkaro.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class OnboardingRequest {

	@NotBlank(message = "Name is required")
	@Size(max = 80)
	@JsonProperty("name")
	private String name;

	@NotBlank(message = "Mobile number is required")
	@Pattern(regexp = "^\\d{10}$", message = "Enter a valid 10-digit mobile number")
	@JsonProperty("phone")
	private String phone;

	@NotBlank(message = "User type is required")
	@JsonProperty("userType")
	private String userType;

	@NotBlank(message = "Brand name is required")
	@Size(max = 100)
	@JsonProperty("brandName")
	private String brandName;

	@NotBlank(message = "Category is required")
	@JsonProperty("category")
	private String category;

	@NotBlank(message = "Source type is required")
	@JsonProperty("sourceType")
	private String sourceType;

	@JsonProperty("websiteUrl")
	private String websiteUrl;

	@JsonProperty("description")
	private String description;

	@NotBlank(message = "Team size is required")
	@JsonProperty("teamSize")
	private String teamSize;

	@JsonProperty("referralCode")
	private String referralCode;

	public OnboardingRequest() {
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getUserType() {
		return userType;
	}

	public void setUserType(String userType) {
		this.userType = userType;
	}

	public String getBrandName() {
		return brandName;
	}

	public void setBrandName(String brandName) {
		this.brandName = brandName;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getWebsiteUrl() {
		return websiteUrl;
	}

	public void setWebsiteUrl(String websiteUrl) {
		this.websiteUrl = websiteUrl;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getTeamSize() {
		return teamSize;
	}

	public void setTeamSize(String teamSize) {
		this.teamSize = teamSize;
	}

	public String getReferralCode() {
		return referralCode;
	}

	public void setReferralCode(String referralCode) {
		this.referralCode = referralCode;
	}
}