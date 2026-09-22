package in.postkaro.service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads a brand's public website: homepage + a few key pages (about, products,
 * menu, services, blog). Returns cleaned text, logo, colours.
 *
 * Safety: only public http(s) hosts; private/internal IPs are refused on every
 * hop (redirects are followed manually so they can't sneak past the check).
 */
@Slf4j
@Service
public class WebsiteScraper {

	public record WebsiteSnapshot(String url, String title, String description, String siteName, String logoUrl,
			String ogImage, List<String> colors, String text, int pagesRead, boolean usedReader) {
	}

	public static class ScrapeException extends RuntimeException {
		public ScrapeException(String m) {
			super(m);
		}
	}

	private static final String UA = "Mozilla/5.0 (compatible; PostKaroBot/1.0; +https://postkaro.in)";
	private static final int TIMEOUT_MS = 10_000;
	private static final int MAX_BODY = 2 * 1024 * 1024;
	private static final int MAX_EXTRA_PAGES = 4;
	private static final int MAX_CHARS_PER_PAGE = 4_000;
	private static final int MAX_TOTAL_CHARS = 14_000;
	private static final int MIN_USEFUL_CHARS = 400; // below this the site is probably JS-rendered

	private static final Pattern USEFUL_PATH = Pattern.compile(
			"about|story|who-we-are|product|shop|store|collection|catalog|menu|service|pricing|offer|course|treatment|blog",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SKIP_PATH = Pattern.compile(
			"cart|checkout|login|signin|sign-in|register|account|wishlist|privacy|terms|policy|refund|shipping|cdn-cgi|wp-admin|\\.(pdf|jpe?g|png|gif|webp|svg|zip|mp4|mp3|xml)$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b");

	// Free without a key (rate-limited). Handles JS-rendered sites (Wix, React…).
	@Value("${scraper.reader-url:https://r.jina.ai/}")
	private String readerUrl;

	@Value("${scraper.reader-key:}")
	private String readerKey;

	private final RestClient http = RestClient.create();

	// ---------- public ----------

	public WebsiteSnapshot scrape(String rawUrl) {
		URI start = normalize(rawUrl);
		Set<String> disallowed = robotsDisallow(start);

		Document home = fetch(start);
		URI base = URI.create(home.location());

		String title = firstNonBlank(meta(home, "og:title"), home.title());
		String description = firstNonBlank(meta(home, "og:description"), meta(home, "description"));
		String siteName = meta(home, "og:site_name");
		String ogImage = abs(home, meta(home, "og:image"));
		String logo = findLogo(home);
		List<String> colors = findColors(home);

		StringBuilder text = new StringBuilder();
		appendPage(text, "Home", home);

		int pages = 1;
		for (URI link : pickLinks(home, base, disallowed)) {
			if (pages > MAX_EXTRA_PAGES || text.length() >= MAX_TOTAL_CHARS)
				break;
			try {
				Document d = fetch(link);
				appendPage(text, link.getPath(), d);
				pages++;
			} catch (Exception e) {
				log.debug("Skipping {}: {}", link, e.getMessage());
			}
		}

		boolean usedReader = false;
		if (text.length() < MIN_USEFUL_CHARS) {
			String rendered = readViaReader(base.toString());
			if (rendered != null && rendered.length() > text.length()) {
				text = new StringBuilder(rendered);
				usedReader = true;
			}
		}

		if (text.length() < 80) {
			throw new ScrapeException("We couldn't read any text on this website.");
		}

		return new WebsiteSnapshot(base.toString(), trim(title, 200), trim(description, 500), trim(siteName, 100), logo,
				ogImage, colors, trim(text.toString(), MAX_TOTAL_CHARS), pages, usedReader);
	}

	// ---------- fetching ----------

	/** GET with manual redirects so every hop goes through the IP check. */
	private Document fetch(URI uri) {
		URI current = uri;
		for (int hop = 0; hop < 4; hop++) {
			assertPublic(current);
			try {
				Connection.Response res = Jsoup.connect(current.toString()).userAgent(UA).timeout(TIMEOUT_MS)
						.maxBodySize(MAX_BODY).followRedirects(false).ignoreHttpErrors(true).ignoreContentType(true)
						.header("Accept", "text/html,application/xhtml+xml")
						.header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8").execute();

				int code = res.statusCode();
				if (code >= 300 && code < 400 && res.header("Location") != null) {
					current = current.resolve(res.header("Location").trim());
					continue;
				}
				if (code >= 400) {
					throw new ScrapeException("The website returned an error (" + code + ").");
				}
				String type = res.contentType() == null ? "" : res.contentType().toLowerCase(Locale.ROOT);
				if (!type.contains("html")) {
					throw new ScrapeException("That link isn't a web page.");
				}
				// res.url() == current (no auto-redirects), so d.location() is the real page
				// URL
				return res.parse();
			} catch (ScrapeException e) {
				throw e;
			} catch (Exception e) {
				throw new ScrapeException("We couldn't open the website. Check the address and try again.");
			}
		}
		throw new ScrapeException("The website redirected too many times.");
	}

	private String readViaReader(String url) {
		if (readerUrl == null || readerUrl.isBlank())
			return null;
		try {
			var req = http.get().uri(URI.create(readerUrl + url)).header("Accept", "text/plain")
					.header("X-Return-Format", "text");
			if (readerKey != null && !readerKey.isBlank())
				req = req.header("Authorization", "Bearer " + readerKey);
			String body = req.retrieve().body(String.class);
			return body == null ? null : trim(body.strip(), MAX_TOTAL_CHARS);
		} catch (Exception e) {
			log.info("Reader fallback failed for {}: {}", url, e.getMessage());
			return null;
		}
	}

	/** Minimal robots.txt: Disallow rules under "User-agent: *". */
	private Set<String> robotsDisallow(URI site) {
		Set<String> out = new LinkedHashSet<>();
		try {
			URI robots = site.resolve("/robots.txt");
			assertPublic(robots);
			String body = Jsoup.connect(robots.toString()).userAgent(UA).timeout(5_000).ignoreContentType(true)
					.followRedirects(false).ignoreHttpErrors(true).maxBodySize(200_000).execute().body();
			boolean inStar = false;
			for (String line : body.split("\\R")) {
				String l = line.strip();
				String lower = l.toLowerCase(Locale.ROOT);
				if (lower.startsWith("user-agent:")) {
					inStar = l.substring(11).trim().equals("*");
				} else if (inStar && lower.startsWith("disallow:")) {
					String p = l.substring(9).trim();
					if (!p.isEmpty())
						out.add(p);
				}
			}
		} catch (Exception ignored) {
			// no robots.txt = allowed
		}
		return out;
	}

	// ---------- extraction ----------

	private void appendPage(StringBuilder out, String label, Document d) {
		StringBuilder page = new StringBuilder();

		// Structured data first — Shopify/WooCommerce put products + prices here
		for (Element ld : d.select("script[type=application/ld+json]")) {
			String j = ld.data().replaceAll("\\s+", " ").trim();
			if (j.contains("Product") || j.contains("Organization") || j.contains("LocalBusiness")
					|| j.contains("Restaurant")) {
				page.append("[structured data] ").append(trim(j, 1500)).append('\n');
			}
		}

		Document c = d.clone();
		c.select("script, style, noscript, svg, iframe, form, footer, [aria-hidden=true]").remove();

		Set<String> seen = new LinkedHashSet<>();
		for (Element e : c
				.select("h1, h2, h3, h4, p, li, blockquote, figcaption, [class*=price], [class*=product-title]")) {
			String t = e.text().replaceAll("\\s+", " ").trim();
			if (t.length() < 3 || t.length() > 600 || !seen.add(t))
				continue;
			String tag = e.tagName();
			if (tag.matches("h[1-4]"))
				page.append("\n## ");
			page.append(t).append('\n');
			if (page.length() > MAX_CHARS_PER_PAGE)
				break;
		}

		if (page.length() == 0)
			return;
		out.append("\n=== Page: ").append(label.isBlank() ? "/" : label).append(" ===\n")
				.append(trim(page.toString(), MAX_CHARS_PER_PAGE)).append('\n');
	}

	private List<URI> pickLinks(Document d, URI base, Set<String> disallowed) {
		String host = bareHost(base.getHost());
		Map<String, URI> picked = new LinkedHashMap<>();
		for (Element a : d.select("a[href]")) {
			String href = a.absUrl("href");
			if (href.isBlank())
				continue;
			try {
				URI u = URI.create(href.split("#")[0]);
				if (u.getHost() == null || !bareHost(u.getHost()).equals(host))
					continue;
				String path = u.getPath() == null ? "" : u.getPath();
				if (path.isBlank() || path.equals("/"))
					continue;
				String hay = path + " " + a.text();
				if (!USEFUL_PATH.matcher(hay).find() || SKIP_PATH.matcher(path).find())
					continue;
				if (disallowed.stream().anyMatch(path::startsWith))
					continue;
				// one page per section: /products/a and /products/b → keep the first
				String section = path.replaceAll("^/([^/]+).*", "$1").toLowerCase(Locale.ROOT);
				picked.putIfAbsent(section, new URI(u.getScheme(), u.getAuthority(), path, null, null));
			} catch (Exception ignored) {
			}
		}
		return new ArrayList<>(picked.values());
	}

	private String findLogo(Document d) {
		for (Element img : d.select("header img, img")) {
			String hay = (img.attr("src") + " " + img.attr("alt") + " " + img.className() + " " + img.id())
					.toLowerCase(Locale.ROOT);
			if (hay.contains("logo")) {
				String src = img.absUrl("src");
				if (!src.isBlank() && !src.startsWith("data:"))
					return src;
			}
		}
		Element touch = d.selectFirst("link[rel~=apple-touch-icon]");
		if (touch != null && !touch.absUrl("href").isBlank())
			return touch.absUrl("href");
		Element icon = d.selectFirst("link[rel~=icon]");
		return icon == null || icon.absUrl("href").isBlank() ? null : icon.absUrl("href");
	}

	/** theme-color first, then the most-used non-grey hex colours in the CSS. */
	private List<String> findColors(Document d) {
		Map<String, Integer> counts = new HashMap<>();
		String theme = meta(d, "theme-color");
		if (theme != null && HEX.matcher(theme).matches())
			counts.merge(expandHex(theme), 1000, Integer::sum);

		StringBuilder css = new StringBuilder();
		d.select("style").forEach(s -> css.append(s.data()).append('\n'));
		d.select("[style]").forEach(e -> css.append(e.attr("style")).append('\n'));

		// + up to 2 same-site stylesheets (that's where most brand colours live)
		String host = bareHost(URI.create(d.location()).getHost());
		int sheets = 0;
		for (Element link : d.select("link[rel=stylesheet][href]")) {
			if (sheets >= 2)
				break;
			try {
				URI u = URI.create(link.absUrl("href"));
				if (u.getHost() == null || !bareHost(u.getHost()).equals(host))
					continue;
				assertPublic(u);
				css.append(Jsoup.connect(u.toString()).userAgent(UA).timeout(6_000).ignoreContentType(true)
						.followRedirects(false).maxBodySize(400_000).execute().body());
				sheets++;
			} catch (Exception ignored) {
			}
		}

		Matcher m = HEX.matcher(css);
		while (m.find()) {
			String hex = expandHex(m.group());
			if (!isGreyish(hex))
				counts.merge(hex, 1, Integer::sum);
		}

		return counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4).map(Map.Entry::getKey)
				.toList();
	}

	// ---------- URL safety ----------

	static URI normalize(String raw) {
		if (raw == null || raw.isBlank())
			throw new ScrapeException("Add your website address first.");
		String s = raw.trim();
		if (!s.matches("(?i)^https?://.*"))
			s = "https://" + s;
		try {
			URI u = new URI(s);
			if (u.getHost() == null)
				throw new ScrapeException("That doesn't look like a website address.");
			String host = IDN.toASCII(u.getHost().toLowerCase(Locale.ROOT));
			if (u.getPort() != -1 && u.getPort() != 80 && u.getPort() != 443)
				throw new ScrapeException("Only normal website addresses are supported.");
			String path = u.getRawPath() == null || u.getRawPath().isBlank() ? "/" : u.getRawPath();
			return URI.create(u.getScheme().toLowerCase(Locale.ROOT) + "://" + host + path);
		} catch (ScrapeException e) {
			throw e;
		} catch (Exception e) {
			throw new ScrapeException("That doesn't look like a website address.");
		}
	}

	private static void assertPublic(URI u) {
		String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https"))
			throw new ScrapeException("Only http(s) websites are supported.");
		String host = u.getHost();
		if (host == null || host.equalsIgnoreCase("localhost") || host.endsWith(".internal") || host.endsWith(".local"))
			throw new ScrapeException("That website address isn't allowed.");
		try {
			for (InetAddress a : InetAddress.getAllByName(host)) {
				if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
						|| a.isMulticastAddress() || isCgnatOrUla(a)) {
					throw new ScrapeException("That website address isn't allowed.");
				}
			}
		} catch (ScrapeException e) {
			throw e;
		} catch (Exception e) {
			throw new ScrapeException("We couldn't find that website. Check the address.");
		}
	}

	private static boolean isCgnatOrUla(InetAddress a) {
		byte[] b = a.getAddress();
		if (b.length == 4)
			return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
		return (b[0] & 0xfe) == 0xfc; // fc00::/7
	}

	// ---------- helpers ----------

	private static String meta(Document d, String name) {
		Element e = d.selectFirst("meta[property=\"" + name + "\"], meta[name=\"" + name + "\"]");
		if (e == null)
			return null;
		String v = e.attr("content").trim();
		return v.isEmpty() ? null : v;
	}

	private static String abs(Document d, String url) {
		if (url == null)
			return null;
		try {
			return URI.create(d.location()).resolve(url).toString();
		} catch (Exception e) {
			return url;
		}
	}

	private static String bareHost(String h) {
		String x = h == null ? "" : h.toLowerCase(Locale.ROOT);
		return x.startsWith("www.") ? x.substring(4) : x;
	}

	private static String expandHex(String hex) {
		String h = hex.replace("#", "").toUpperCase(Locale.ROOT);
		if (h.length() == 3)
			h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
		return "#" + h;
	}

	private static boolean isGreyish(String hex) {
		int r = Integer.parseInt(hex.substring(1, 3), 16);
		int g = Integer.parseInt(hex.substring(3, 5), 16);
		int b = Integer.parseInt(hex.substring(5, 7), 16);
		int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		return max - min < 24; // white, black, greys
	}

	private static String firstNonBlank(String... xs) {
		for (String x : xs)
			if (x != null && !x.isBlank())
				return x.trim();
		return null;
	}

	private static String trim(String s, int max) {
		if (s == null)
			return null;
		return s.length() <= max ? s : s.substring(0, max);
	}
}
