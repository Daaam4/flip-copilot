package com.flipcopilot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Blocking client for the OSRS Wiki real-time prices API (the same source {@code tools/osrs.py} uses).
 * Every method does network IO: call only from the plugin executor, never from the client thread.
 */
@Slf4j
@Singleton
public class WikiPricesClient
{
	static final String BASE = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String USER_AGENT = "flip-copilot RuneLite plugin - github.com/Daaam4/flip-copilot";
	private static final long MAPPING_TTL_MS = 24L * 3600 * 1000;

	private final OkHttpClient http;
	private final Gson gson;
	private final File dir;

	@Inject
	WikiPricesClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
		this.dir = new File(RuneLite.RUNELITE_DIR, "flip-copilot");
	}

	File dataDir()
	{
		return dir;
	}

	private JsonElement get(String path, Map<String, String> query) throws IOException
	{
		HttpUrl.Builder url = HttpUrl.parse(BASE + path).newBuilder();
		if (query != null)
		{
			query.forEach(url::addQueryParameter);
		}
		Request req = new Request.Builder().url(url.build()).header("User-Agent", USER_AGENT).build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				throw new IOException("HTTP " + res.code() + " for " + path);
			}
			return gson.fromJson(res.body().string(), JsonElement.class);
		}
	}

	/** /mapping, cached on disk for 24h (it changes weekly at most). */
	public Map<Integer, ItemMeta> mapping() throws IOException
	{
		File cache = new File(dir, "mapping.json");
		JsonElement root = null;
		if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < MAPPING_TTL_MS)
		{
			try
			{
				root = gson.fromJson(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8), JsonElement.class);
			}
			catch (Exception ex)
			{
				log.debug("flip-copilot: mapping cache unreadable, refetching", ex);
			}
		}
		if (root == null)
		{
			root = get("/mapping", null);
			try
			{
				dir.mkdirs();
				Files.write(cache.toPath(), gson.toJson(root).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException ex)
			{
				log.debug("flip-copilot: could not cache mapping", ex);
			}
		}
		Map<Integer, ItemMeta> out = new HashMap<>();
		for (JsonElement e : root.getAsJsonArray())
		{
			JsonObject o = e.getAsJsonObject();
			int id = o.get("id").getAsInt();
			out.put(id, new ItemMeta(id, o.get("name").getAsString(), intOr(o, "limit", 0), o.has("members") && o.get("members").getAsBoolean()));
		}
		return out;
	}

	/** /latest only: cheap, used every minute for the advisor and offer-setup overlay. */
	public Map<Integer, Quote> latest() throws IOException
	{
		JsonObject data = get("/latest", null).getAsJsonObject().getAsJsonObject("data");
		Map<Integer, Quote> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : data.entrySet())
		{
			JsonObject o = e.getValue().getAsJsonObject();
			out.put(Integer.parseInt(e.getKey()), new Quote(intOr(o, "high", 0), longOr(o, "highTime", 0), intOr(o, "low", 0), longOr(o, "lowTime", 0)));
		}
		return out;
	}

	/** /latest + /5m + /1h + /24h. */
	public MarketSnapshot snapshot() throws IOException
	{
		Map<Integer, Quote> latest = latest();
		Map<Integer, Bucket> m5 = averaged("/5m");
		Map<Integer, Bucket> h1 = averaged("/1h");
		Map<Integer, Bucket> h24 = averaged("/24h");
		return new MarketSnapshot(Instant.now().getEpochSecond(), latest, m5, h1, h24);
	}

	private Map<Integer, Bucket> averaged(String path) throws IOException
	{
		JsonObject root = get(path, null).getAsJsonObject();
		long ts = longOr(root, "timestamp", 0);
		Map<Integer, Bucket> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("data").entrySet())
		{
			out.put(Integer.parseInt(e.getKey()), bucket(e.getValue().getAsJsonObject(), ts));
		}
		return out;
	}

	/** /timeseries?timestep=5m|1h|6h&id=N, oldest first. */
	public List<Bucket> timeseries(int itemId, String timestep) throws IOException
	{
		Map<String, String> q = new HashMap<>();
		q.put("timestep", timestep);
		q.put("id", Integer.toString(itemId));
		JsonArray data = get("/timeseries", q).getAsJsonObject().getAsJsonArray("data");
		List<Bucket> out = new ArrayList<>(data.size());
		for (JsonElement e : data)
		{
			JsonObject o = e.getAsJsonObject();
			out.add(bucket(o, longOr(o, "timestamp", 0)));
		}
		return out;
	}

	private static Bucket bucket(JsonObject o, long ts)
	{
		return new Bucket(ts, intOrNull(o, "avgHighPrice"), longOr(o, "highPriceVolume", 0), intOrNull(o, "avgLowPrice"), longOr(o, "lowPriceVolume", 0));
	}

	private static int intOr(JsonObject o, String k, int d)
	{
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? d : e.getAsInt();
	}

	private static Integer intOrNull(JsonObject o, String k)
	{
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsInt();
	}

	private static long longOr(JsonObject o, String k, long d)
	{
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? d : e.getAsLong();
	}
}
