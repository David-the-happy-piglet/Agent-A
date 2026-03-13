package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches real RSS headlines for a given category.
 *
 * Feed sources are taken from the TECH_FEEDS / FULL_FEEDS configuration.
 * Network I/O is blocking — caller must invoke execute() on a background thread.
 * (AgentChatActivity.executePlan() handles this via bgExecutor.)
 *
 * Supported categories: tech, ai, finance, politics
 */
public class NewsFeedTool implements Tool {

    private static final int MAX_HEADLINES = 5;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    // Feed registry: category → list of { "Source Name", "RSS URL" }
    // URLs taken directly from TECH_FEEDS / FULL_FEEDS in the RTF config file.
    private static final Map<String, List<String[]>> FEEDS = new LinkedHashMap<>();

    static {
        // General top headlines — used when no specific category is requested
        FEEDS.put("general", Arrays.asList(
                new String[]{"BBC World",      "https://feeds.bbci.co.uk/news/world/rss.xml"},
                new String[]{"AP News",        "https://news.google.com/rss/search?q=site:apnews.com&hl=en-US&gl=US&ceid=US:en"},
                new String[]{"Reuters World",  "https://news.google.com/rss/search?q=site:reuters.com+world&hl=en-US&gl=US&ceid=US:en"},
                new String[]{"NPR News",       "https://feeds.npr.org/1001/rss.xml"},
                new String[]{"Guardian World", "https://www.theguardian.com/world/rss"}
        ));

        FEEDS.put("tech", Arrays.asList(
                new String[]{"TechCrunch",     "https://techcrunch.com/feed/"},
                new String[]{"The Verge",      "https://www.theverge.com/rss/index.xml"},
                new String[]{"Ars Technica",   "https://feeds.arstechnica.com/arstechnica/technology-lab"},
                new String[]{"Hacker News",    "https://hnrss.org/frontpage"},
                new String[]{"MIT Tech Review","https://www.technologyreview.com/feed/"}
        ));

        FEEDS.put("ai", Arrays.asList(
                new String[]{"VentureBeat AI", "https://venturebeat.com/category/ai/feed/"},
                new String[]{"The Verge AI",   "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml"},
                new String[]{"ArXiv AI",       "https://export.arxiv.org/rss/cs.AI"},
                new String[]{"AI News",        "https://news.google.com/rss/search?q=(OpenAI+OR+Anthropic+OR+Google+AI+OR+ChatGPT)+when:2d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("finance", Arrays.asList(
                new String[]{"CNBC",           "https://www.cnbc.com/id/100003114/device/rss/rss.html"},
                new String[]{"Yahoo Finance",  "https://finance.yahoo.com/news/rssindex"},
                new String[]{"Reuters Biz",    "https://news.google.com/rss/search?q=site:reuters.com+business+markets&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("politics", Arrays.asList(
                new String[]{"BBC World",      "https://feeds.bbci.co.uk/news/world/rss.xml"},
                new String[]{"NPR News",       "https://feeds.npr.org/1001/rss.xml"},
                new String[]{"Guardian World", "https://www.theguardian.com/world/rss"}
        ));

        FEEDS.put("crypto", Arrays.asList(
                new String[]{"CoinDesk",       "https://www.coindesk.com/arc/outboundfeeds/rss/"},
                new String[]{"Cointelegraph",  "https://cointelegraph.com/rss"},
                new String[]{"Crypto News",    "https://news.google.com/rss/search?q=(bitcoin+OR+ethereum+OR+crypto+OR+%22digital+assets%22)+when:1d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("science", Arrays.asList(
                new String[]{"MIT Research",   "https://news.mit.edu/rss/research"},
                new String[]{"ArXiv ML",       "https://export.arxiv.org/rss/cs.LG"},
                new String[]{"Science News",   "https://news.google.com/rss/search?q=(science+OR+research+OR+discovery+OR+study)+breakthrough+when:3d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("security", Arrays.asList(
                new String[]{"Krebs Security", "https://krebsonsecurity.com/feed/"},
                new String[]{"The Hacker News","https://feeds.feedburner.com/TheHackersNews"},
                new String[]{"Dark Reading",   "https://www.darkreading.com/rss.xml"}
        ));

        FEEDS.put("startups", Arrays.asList(
                new String[]{"TechCrunch",     "https://techcrunch.com/category/startups/feed/"},
                new String[]{"VentureBeat",    "https://venturebeat.com/feed/"},
                new String[]{"Crunchbase",     "https://news.crunchbase.com/feed/"},
                new String[]{"VC News",        "https://news.google.com/rss/search?q=(%22Series+A%22+OR+%22Series+B%22+OR+%22funding+round%22+OR+%22venture+capital%22)+when:7d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("energy", Arrays.asList(
                new String[]{"Oil & Gas",      "https://news.google.com/rss/search?q=(oil+price+OR+OPEC+OR+%22natural+gas%22+OR+%22crude+oil%22)+when:2d&hl=en-US&gl=US&ceid=US:en"},
                new String[]{"Nuclear Energy", "https://news.google.com/rss/search?q=(%22nuclear+energy%22+OR+%22nuclear+power%22+OR+uranium)+when:3d&hl=en-US&gl=US&ceid=US:en"},
                new String[]{"Reuters Energy", "https://news.google.com/rss/search?q=site:reuters.com+(oil+OR+gas+OR+energy+OR+OPEC)+when:3d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("asia", Arrays.asList(
                new String[]{"BBC Asia",       "https://feeds.bbci.co.uk/news/world/asia/rss.xml"},
                new String[]{"Reuters Asia",   "https://news.google.com/rss/search?q=site:reuters.com+(China+OR+Japan+OR+Taiwan+OR+Korea)+when:3d&hl=en-US&gl=US&ceid=US:en"},
                new String[]{"CNA",            "https://www.channelnewsasia.com/api/v1/rss-outbound-feed?_format=xml"},
                new String[]{"Asia News",      "https://news.google.com/rss/search?q=(China+OR+Japan+OR+Korea+OR+India+OR+ASEAN)+when:2d&hl=en-US&gl=US&ceid=US:en"}
        ));

        FEEDS.put("middleeast", Arrays.asList(
                new String[]{"BBC Middle East","https://feeds.bbci.co.uk/news/world/middle_east/rss.xml"},
                new String[]{"Al Jazeera",     "https://www.aljazeera.com/xml/rss/all.xml"},
                new String[]{"Guardian ME",    "https://www.theguardian.com/world/middleeast/rss"}
        ));
    }

    // ── Tool interface ────────────────────────────────────────────────────

    @Override
    public String name() { return "news.fetch"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("category", "String — 'tech' | 'ai' | 'finance' | 'politics'");
        return new ToolSpec(
                "news.fetch",
                "Fetches the latest news headlines for the requested category.",
                params,
                RiskLevel.LOW);
    }

    /**
     * Blocking network call — must be called from a background thread.
     * Iterates feed sources in order, stops once MAX_HEADLINES is reached.
     * If a source fails it is silently skipped so other sources still work.
     */
    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String category = args.containsKey("category")
                ? args.get("category").toLowerCase().trim()
                : "tech";

        List<String[]> sources = FEEDS.containsKey(category)
                ? FEEDS.get(category)
                : FEEDS.get("tech");

        List<String> headlines = new ArrayList<>();

        for (String[] source : sources) {
            if (headlines.size() >= MAX_HEADLINES) break;
            String sourceName = source[0];
            String feedUrl   = source[1];
            try {
                int remaining = MAX_HEADLINES - headlines.size();
                List<String> items = fetchTitles(feedUrl, remaining);
                for (String title : items) {
                    headlines.add("[" + sourceName + "] " + title);
                }
            } catch (Throwable ignored) {
                // skip failing source (timeout, 403, parse error, etc.), try next one
            }
        }

        if (headlines.isEmpty()) {
            return ToolResult.fail("Could not fetch news. Please check your internet connection.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(categoryEmoji(category))
          .append(" ").append(capitalize(category)).append(" Headlines:\n");
        for (int i = 0; i < headlines.size(); i++) {
            sb.append(i + 1).append(". ").append(headlines.get(i)).append("\n");
        }
        return ToolResult.success(sb.toString().trim());
    }

    // ── RSS / Atom parser ─────────────────────────────────────────────────

    /**
     * Fetches up to {@code limit} article titles from an RSS or Atom feed URL.
     * Uses Android's built-in XmlPullParser — no third-party XML library needed.
     */
    private List<String> fetchTitles(String feedUrl, int limit)
            throws IOException, XmlPullParserException {

        List<String> titles = new ArrayList<>();
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(feedUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // Some feeds block default Java UA; a generic browser string avoids 403s
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AgentA/1.0; RSS reader)");
            conn.setRequestProperty("Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml");

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return titles;
            }

            InputStream in = conn.getInputStream();
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null); // encoding auto-detected from XML declaration

            boolean insideItem  = false;
            boolean nextIsTitle = false;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG: {
                        String tag = parser.getName();
                        if ("item".equalsIgnoreCase(tag) || "entry".equalsIgnoreCase(tag)) {
                            insideItem = true;
                        } else if (insideItem && "title".equalsIgnoreCase(tag)) {
                            nextIsTitle = true;
                        }
                        break;
                    }
                    case XmlPullParser.TEXT: {
                        if (nextIsTitle) {
                            String text = parser.getText().trim();
                            if (!text.isEmpty()) {
                                titles.add(text);
                                if (titles.size() >= limit) return titles;
                            }
                            nextIsTitle = false;
                        }
                        break;
                    }
                    case XmlPullParser.END_TAG: {
                        String tag = parser.getName();
                        if ("item".equalsIgnoreCase(tag) || "entry".equalsIgnoreCase(tag)) {
                            insideItem  = false;
                            nextIsTitle = false;
                        }
                        break;
                    }
                }
                eventType = parser.next();
            }

        } finally {
            if (conn != null) conn.disconnect();
        }

        return titles;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String categoryEmoji(String category) {
        switch (category) {
            case "general":    return "🌐";
            case "ai":         return "🤖";
            case "finance":    return "💰";
            case "politics":   return "🏛️";
            case "crypto":     return "₿";
            case "science":    return "🔬";
            case "security":   return "🔐";
            case "startups":   return "🚀";
            case "energy":     return "⚡";
            case "asia":       return "🌏";
            case "middleeast": return "🌍";
            default:           return "📱";
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
