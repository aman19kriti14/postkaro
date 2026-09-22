//package in.postkaro.service;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//
//import org.springframework.http.HttpStatus;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.support.TransactionTemplate;
//import org.springframework.web.server.ResponseStatusException;
//
//import in.postkaro.dto.request.BusinessDtos.BusinessSaved;
//import in.postkaro.dto.request.BusinessDtos.BusinessUpdate;
//import in.postkaro.dto.request.BusinessDtos.BusinessView;
//import in.postkaro.entity.User;
//import in.postkaro.entity.UserProfile;
//import in.postkaro.enums.BusinessCategory;
//import in.postkaro.repository.UserProfileRepository;
//import in.postkaro.repository.UserRepository;
//import in.postkaro.service.WebsiteScraper.ScrapeException;
//import lombok.RequiredArgsConstructor;
//
///**
// * Settings › Business: edit what was entered during onboarding. If anything the
// * brand analysis depends on changes (website, description, name, category) we
// * re-learn the brand in the background.
// */
//@Service
//@RequiredArgsConstructor
//public class BusinessProfileService {
//
//	private static final List<String> CATEGORIES = Arrays.stream(BusinessCategory.values()).map(Enum::name).toList();
//
//	private final UserRepository users;
//	private final UserProfileRepository profiles;
//	private final BrandProfileService brandProfile;
//	private final TransactionTemplate tx;
//
//	// ---------- read ----------
//
//	public BusinessView get(UUID userId) {
//		User u = users.findById(userId)
//				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
//		UserProfile p = profiles.findByUserId(userId).orElse(null);
//
//		return new BusinessView(u.getFullName(), p == null ? null : p.getPhone(),
//				p == null || p.getUserType() == null ? null : p.getUserType().name(),
//				p == null ? null : p.getBrandName(),
//				p == null || p.getCategory() == null ? null : p.getCategory().name(),
//				p == null ? null : p.getWebsiteUrl(), p == null ? null : p.getDescription(), CATEGORIES,
//				p == null ? null : p.getUpdatedAt());
//	}
//
//	// ---------- save ----------
//
//	public BusinessSaved update(UUID userId, BusinessUpdate in) {
//		BusinessCategory category;
//		try {
//			category = BusinessCategory.valueOf(in.category().trim().toUpperCase());
//		} catch (Exception e) {
//			throw bad("Pick a category");
//		}
//
//		String website = blankToNull(in.websiteUrl());
//		if (website != null) {
//			try {
//				WebsiteScraper.normalize(website); // same check the scraper uses
//			} catch (ScrapeException e) {
//				throw bad(e.getMessage());
//			}
//		}
//		String description = blankToNull(in.description());
//		String brandName = in.brandName().trim();
//
//		Boolean brandChanged = tx.execute(s -> {
//			UserProfile p = profiles.findByUserId(userId)
//					.orElseThrow(() -> bad("Finish onboarding first"));
//			User u = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
//
//			boolean changed = !Objects.equals(p.getWebsiteUrl(), website)
//					|| !Objects.equals(p.getDescription(), description)
//					|| !Objects.equals(p.getBrandName(), brandName) || p.getCategory() != category;
//
//			p.setBrandName(brandName);
//			p.setCategory(category);
//			p.setWebsiteUrl(website);
//			p.setDescription(description);
//			String phone = blankToNull(in.phone());
//			if (phone != null)
//				p.setPhone(phone); // phone is required on the profile — keep the old one if cleared
//			profiles.save(p);
//
//			u.setFullName(in.fullName().trim());
//			users.save(u);
//			return changed;
//		});
//
//		boolean relearning = Boolean.TRUE.equals(brandChanged);
//		if (relearning)
//			brandProfile.startQuietly(userId); // after commit, so it reads the new website
//
//		return new BusinessSaved(get(userId), relearning);
//	}
//
//	// ---------- helpers ----------
//
//	private static String blankToNull(String s) {
//		return s == null || s.isBlank() ? null : s.trim();
//	}
//
//	private static ResponseStatusException bad(String m) {
//		return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
//	}
//}