package in.postkaro.service;

import in.postkaro.entity.User;
import in.postkaro.entity.UserProfile;
import in.postkaro.enums.BusinessCategory;
import in.postkaro.enums.TeamSize;
import in.postkaro.enums.UserType;
import in.postkaro.repository.UserProfileRepository;
import in.postkaro.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OnboardingService {

	private final UserProfileRepository profileRepository;
	private final UserRepository userRepository;

	@Transactional
	public void saveProfile(User user, Map<String, Object> data) {
		String name = str(data, "name");
		String phone = str(data, "phone");
		UserType userType = UserType.valueOf(str(data, "userType").toUpperCase());
		String brandName = str(data, "brandName");
		BusinessCategory category = BusinessCategory.valueOf(str(data, "category").toUpperCase());
		String sourceType = str(data, "sourceType");
		String websiteUrl = str(data, "websiteUrl");
		String description = str(data, "description");
		TeamSize teamSize = parseTeamSize(str(data, "teamSize"));
		String referralCode = str(data, "referralCode");

		UserProfile profile = profileRepository.findByUserId(user.getId())
				.orElse(UserProfile.builder().user(user).build());

		profile.setPhone(phone);
		profile.setUserType(userType);
		profile.setBrandName(brandName.trim());
		profile.setCategory(category);
		profile.setSourceType(sourceType);
		profile.setWebsiteUrl(websiteUrl);
		profile.setDescription(description);
		profile.setTeamSize(teamSize);
		profile.setReferralCode(referralCode);

		profileRepository.save(profile);

		user.setFullName(name.trim());
		userRepository.save(user);
	}

	private String str(Map<String, Object> data, String key) {
		Object val = data.get(key);
		return val != null ? val.toString().trim() : null;
	}

	private TeamSize parseTeamSize(String value) {
		if (value == null)
			return null;
		return switch (value.toLowerCase()) {
		case "solo" -> TeamSize.SOLO;
		case "2-10" -> TeamSize.SMALL;
		case "10-50" -> TeamSize.MEDIUM;
		case "50+" -> TeamSize.LARGE;
		default -> throw new IllegalArgumentException("Invalid team size: " + value);
		};
	}
}