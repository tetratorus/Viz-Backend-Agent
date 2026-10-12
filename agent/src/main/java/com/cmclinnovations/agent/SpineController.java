package com.cmclinnovations.agent;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cmclinnovations.agent.model.response.ErrorPayload;
import com.cmclinnovations.agent.model.response.StandardApiResponse;
import com.cmclinnovations.agent.service.spine.SpineGoldService;
import com.cmclinnovations.agent.service.spine.SpineGoldService.SpineException;

/**
 * Read-only proxy onto sg-urban-spine `spine serve` (OGC API Features), so the
 * viz can load scenario Gold layers through the same origin as the KG routes.
 */
@RestController
@RequestMapping("/spine")
public class SpineController {
  private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");
  private static final String BASELINE = "baseline";

  private final SpineGoldService spineGoldService;
  private final String apiVersion;

  public SpineController(SpineGoldService spineGoldService, @Value("${app.version:}") String apiVersion) {
    this.spineGoldService = spineGoldService;
    this.apiVersion = apiVersion;
  }

  @GetMapping("/collections")
  public ResponseEntity<?> getCollections() {
    return this.respond(MediaType.APPLICATION_JSON, this.spineGoldService::getCollections);
  }

  @GetMapping("/collections/{collectionId}/items")
  public ResponseEntity<?> getItems(@PathVariable String collectionId,
      @RequestParam(defaultValue = BASELINE) String scenario,
      @RequestParam(required = false) Integer limit) {
    return this.respond(GEO_JSON, () -> this.spineGoldService.getItems(collectionId, scenario, limit));
  }

  @GetMapping("/scenarios")
  public ResponseEntity<?> getScenarios() {
    return this.respond(MediaType.APPLICATION_JSON, this.spineGoldService::getScenarios);
  }

  @GetMapping("/lineage/{scenario}/{transformId}")
  public ResponseEntity<?> getLineage(@PathVariable String scenario, @PathVariable String transformId) {
    return this.respond(MediaType.APPLICATION_JSON, () -> this.spineGoldService.getLineage(scenario, transformId));
  }

  private ResponseEntity<?> respond(MediaType mediaType, Supplier<String> body) {
    try {
      return ResponseEntity.ok().contentType(mediaType).body(body.get());
    } catch (SpineException e) {
      return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON)
          .body(new StandardApiResponse<>(this.apiVersion, null,
              new ErrorPayload(e.getStatus().value(), e.getMessage())));
    }
  }
}
