package com.cmclinnovations.agent.service.spine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only adapter onto an sg-urban-spine `spine serve` instance (OGC API
 * Features). Views read spine Gold/Silver through this service; it never calls
 * a model. Gold features are enriched with the KG IRI of their hub when the
 * Silver `id_bridge` maps the hub id onto the configured KG source.
 */
@Service
public class SpineGoldService {
  private static final Logger LOGGER = LogManager.getLogger(SpineGoldService.class);

  static final String ID_BRIDGE_COLLECTION = "silver.id_bridge";
  private static final String[] HUB_KEYS = { "plot", "building", "district", "road", "station", "cell" };
  private static final Pattern COLLECTION_ID = Pattern.compile("^(silver\\.[a-z0-9_]+|gold(\\.[a-z0-9_]+){3,})$");
  private static final Pattern SCENARIO_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
  private static final Pattern TRANSFORM_ID = Pattern.compile("^[a-z0-9_]+(\\.[a-z0-9_]+)+$");
  private static final int MAX_LIMIT = 100000;

  private final String baseUrl;
  private final String iriSource;
  private final HttpClient client;

  public SpineGoldService(@Value("${SPINE_URL:}") String baseUrl,
      @Value("${SPINE_IRI_SOURCE:twa_kg}") String iriSource) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    this.iriSource = iriSource;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public String getCollections() {
    return this.fetch("/collections", Map.of());
  }

  public String getScenarios() {
    return this.fetch("/scenarios", Map.of());
  }

  public String getLineage(String scenario, String transformId) {
    requireMatch(SCENARIO_ID, scenario, "scenario");
    requireMatch(TRANSFORM_ID, transformId, "transform id");
    return this.fetch("/lineage/" + scenario + "/" + transformId, Map.of());
  }

  /**
   * Returns the GeoJSON FeatureCollection of a spine collection for a scenario,
   * with `iri` added to each feature whose hub is bridged onto the KG.
   */
  public String getItems(String collectionId, String scenario, Integer limit) {
    requireMatch(COLLECTION_ID, collectionId, "collection id");
    requireMatch(SCENARIO_ID, scenario, "scenario");
    Map<String, String> query = new LinkedHashMap<>();
    query.put("scenario", scenario);
    if (limit != null) {
      if (limit < 1 || limit > MAX_LIMIT) {
        throw new SpineException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
      }
      query.put("limit", limit.toString());
    }
    String body = this.fetch("/collections/" + collectionId + "/items", query);
    if (ID_BRIDGE_COLLECTION.equals(collectionId)) {
      return body;
    }
    Map<String, String> bridge = this.loadIriBridge(scenario);
    if (bridge.isEmpty()) {
      return body;
    }
    JSONObject collection = new JSONObject(body);
    JSONArray features = collection.optJSONArray("features");
    if (features != null) {
      for (int i = 0; i < features.length(); i++) {
        JSONObject properties = features.getJSONObject(i).optJSONObject("properties");
        if (properties != null && !properties.has("iri")) {
          enrich(properties, bridge);
        }
      }
    }
    return collection.toString();
  }

  private static void enrich(JSONObject properties, Map<String, String> bridge) {
    for (String hub : HUB_KEYS) {
      Object hubId = properties.opt(hub + "_id");
      if (hubId != null) {
        String iri = bridge.get(hub + "|" + hubId);
        if (iri != null) {
          properties.put("iri", iri);
          return;
        }
      }
    }
  }

  /** Maps "hub|hub_id" to the KG source_id (an IRI) for the configured source. */
  private Map<String, String> loadIriBridge(String scenario) {
    Map<String, String> bridge = new HashMap<>();
    String body;
    try {
      body = this.fetch("/collections/" + ID_BRIDGE_COLLECTION + "/items", Map.of("scenario", scenario));
    } catch (SpineException e) {
      LOGGER.debug("No id_bridge available for scenario {}: {}", scenario, e.getMessage());
      return bridge;
    }
    JSONArray rows = new JSONObject(body).optJSONArray("features");
    if (rows == null) {
      return bridge;
    }
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i).optJSONObject("properties");
      if (row != null && this.iriSource.equals(row.optString("source"))) {
        bridge.put(row.optString("hub") + "|" + row.optString("hub_id"), row.optString("source_id"));
      }
    }
    return bridge;
  }

  private String fetch(String path, Map<String, String> query) {
    if (this.baseUrl.isEmpty()) {
      throw new SpineException(HttpStatus.SERVICE_UNAVAILABLE, "SPINE_URL is not configured for this agent.");
    }
    String queryString = query.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
    URI uri = URI.create(this.baseUrl + path + (queryString.isEmpty() ? "" : "?" + queryString));
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
        .header("Accept", "application/geo+json, application/json").GET().build();
    HttpResponse<String> response;
    try {
      response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      LOGGER.error("Unable to reach spine at {}: {}", uri, e.getMessage());
      throw new SpineException(HttpStatus.BAD_GATEWAY, "Unable to reach spine serve.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpineException(HttpStatus.BAD_GATEWAY, "Request to spine serve was interrupted.");
    }
    if (response.statusCode() >= 400) {
      HttpStatus status = response.statusCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
      throw new SpineException(status, "spine serve returned " + response.statusCode() + " for " + path);
    }
    return response.body();
  }

  private static void requireMatch(Pattern pattern, String value, String label) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new SpineException(HttpStatus.BAD_REQUEST, "Invalid " + label + ": " + value);
    }
  }

  public static class SpineException extends RuntimeException {
    private final HttpStatus status;

    public SpineException(HttpStatus status, String message) {
      super(message);
      this.status = status;
    }

    public HttpStatus getStatus() {
      return this.status;
    }
  }
}
