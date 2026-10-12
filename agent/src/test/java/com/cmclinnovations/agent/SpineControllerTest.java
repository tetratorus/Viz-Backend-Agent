package com.cmclinnovations.agent;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cmclinnovations.agent.service.spine.SpineGoldService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Exercises the spine proxy against a stub `spine serve` on a random port. */
class SpineControllerTest {
  private static final String GOLD = "gold.example.plot_capacity.plot_capacity";
  private static final String GOLD_ITEMS = "{\"type\":\"FeatureCollection\",\"features\":["
      + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"plot_id\":\"P1\",\"gfa_m2\":4500.0}},"
      + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"plot_id\":\"P2\",\"gfa_m2\":3000.0}}],"
      + "\"numberReturned\":2,\"scenario\":\"rezone_p1\"}";
  private static final String BRIDGE_ITEMS = "{\"type\":\"FeatureCollection\",\"features\":["
      + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"source\":\"twa_kg\",\"source_id\":"
      + "\"https://www.theworldavatar.com/kg/plot/P1\",\"hub\":\"plot\",\"hub_id\":\"P1\"}},"
      + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"source\":\"osm\",\"source_id\":\"way/1\","
      + "\"hub\":\"plot\",\"hub_id\":\"P2\"}}]}";

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile boolean bridgeAvailable = true;

  @BeforeEach
  void startStub() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  @AfterEach
  void stopStub() {
    this.server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getRawQuery();
    this.requests.add(path + (query == null ? "" : "?" + query));
    String body;
    int code = 200;
    if (path.equals("/collections")) {
      body = "{\"collections\":[{\"id\":\"" + GOLD + "\",\"title\":\"" + GOLD + "\"}]}";
    } else if (path.equals("/scenarios")) {
      body = "{\"scenarios\":[\"baseline\",\"rezone_p1\"]}";
    } else if (path.equals("/collections/" + GOLD + "/items")) {
      body = GOLD_ITEMS;
    } else if (path.equals("/collections/silver.id_bridge/items") && this.bridgeAvailable) {
      body = BRIDGE_ITEMS;
    } else if (path.equals("/lineage/rezone_p1/example.plot_capacity")) {
      body = "{\"transform_id\":\"example.plot_capacity\"}";
    } else {
      code = 404;
      body = "{\"error\":\"not found\"}";
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private MockMvc mvc(String baseUrl) {
    return MockMvcBuilders
        .standaloneSetup(new SpineController(new SpineGoldService(baseUrl, "twa_kg"), "test")).build();
  }

  private MockMvc mvc() {
    return this.mvc("http://127.0.0.1:" + this.server.getAddress().getPort() + "/");
  }

  @Test
  void collectionsAndScenariosArePassedThrough() throws Exception {
    this.mvc().perform(get("/spine/collections")).andExpect(status().isOk())
        .andExpect(jsonPath("$.collections[0].id").value(GOLD));
    this.mvc().perform(get("/spine/scenarios")).andExpect(status().isOk())
        .andExpect(jsonPath("$.scenarios", hasItem("rezone_p1")));
  }

  @Test
  void itemsForwardScenarioAndAddKgIriFromIdBridge() throws Exception {
    this.mvc().perform(get("/spine/collections/" + GOLD + "/items").param("scenario", "rezone_p1").param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/geo+json"))
        .andExpect(jsonPath("$.scenario").value("rezone_p1"))
        .andExpect(jsonPath("$.features[0].properties.iri").value("https://www.theworldavatar.com/kg/plot/P1"))
        .andExpect(jsonPath("$.features[1].properties.iri").doesNotExist())
        .andExpect(jsonPath("$.features[1].properties.gfa_m2").value(3000.0));
    assertEquals("/collections/" + GOLD + "/items?scenario=rezone_p1&limit=50", this.requests.get(0));
    assertEquals("/collections/silver.id_bridge/items?scenario=rezone_p1", this.requests.get(1));
  }

  @Test
  void itemsDefaultToBaselineAndSurviveMissingIdBridge() throws Exception {
    this.bridgeAvailable = false;
    this.mvc().perform(get("/spine/collections/" + GOLD + "/items")).andExpect(status().isOk())
        .andExpect(jsonPath("$.features[0].properties.iri").doesNotExist());
    assertEquals("/collections/" + GOLD + "/items?scenario=baseline", this.requests.get(0));
  }

  @Test
  void lineageIsPassedThrough() throws Exception {
    this.mvc().perform(get("/spine/lineage/rezone_p1/example.plot_capacity")).andExpect(status().isOk())
        .andExpect(jsonPath("$.transform_id").value("example.plot_capacity"));
  }

  @Test
  void rejectsNonGoldOrSilverCollectionsAndBadScenarios() throws Exception {
    this.mvc().perform(get("/spine/collections/bronze.raw/items")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(400));
    this.mvc().perform(get("/spine/collections/gold.unknown/items")).andExpect(status().isBadRequest());
    this.mvc().perform(get("/spine/collections/" + GOLD + "/items").param("scenario", "a&b=c"))
        .andExpect(status().isBadRequest());
    this.mvc().perform(get("/spine/collections/" + GOLD + "/items").param("limit", "0"))
        .andExpect(status().isBadRequest());
    assertEquals(0, this.requests.size());
  }

  @Test
  void unknownCollectionIsNotFound() throws Exception {
    this.mvc().perform(get("/spine/collections/gold.example.missing.table/items")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value(404));
  }

  @Test
  void unconfiguredSpineIsServiceUnavailable() throws Exception {
    this.mvc("").perform(get("/spine/scenarios")).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.message").value("SPINE_URL is not configured for this agent."));
  }
}
