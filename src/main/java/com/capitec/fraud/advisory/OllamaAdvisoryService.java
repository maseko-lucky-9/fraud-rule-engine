package com.capitec.fraud.advisory;

import com.capitec.fraud.domain.Decision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama-backed advisor. Bound only when {@code app.advisory.enabled=true}
 * (Profile=advisory). Sends a structured prompt to the local Ollama HTTP
 * API; expects a strict JSON response matching the schema in
 * {@code prompts/advisory-v1.md}.
 *
 * <p>Resilience contract (Plan §7 Day 5):
 * <ul>
 *   <li><b>2-second timeout.</b> Anything slower → {@code AdvisoryStatus.TIMED_OUT}
 *       with {@code humanReviewRequired=true}.</li>
 *   <li><b>Circuit breaker.</b> On sustained failure the breaker opens and we
 *       return {@code UNAVAILABLE} without touching Ollama until the breaker
 *       half-opens — see Resilience4j config under {@code advisory}.</li>
 *   <li><b>Malformed output.</b> If Ollama returns non-JSON or a JSON
 *       missing required fields → {@code MALFORMED}.</li>
 *   <li><b>Never authoritative.</b> Even {@code OK} responses carry
 *       {@code humanReviewRequired=true} for any non-APPROVE decision; the
 *       deterministic path remains the source of truth.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.advisory", name = "enabled", havingValue = "true")
public class OllamaAdvisoryService implements AdvisoryService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdvisoryService.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final Duration timeout;
    private final String promptTemplate;
    private final Counter okCounter;
    private final Counter timeoutCounter;
    private final Counter malformedCounter;

    public OllamaAdvisoryService(@Value("${app.advisory.ollama.base-url}") String baseUrl,
                                 @Value("${app.advisory.ollama.model}") String model,
                                 @Value("${app.advisory.ollama.timeout-ms:2000}") long timeoutMs,
                                 ObjectMapper json,
                                 MeterRegistry metrics) throws IOException {
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.promptTemplate = loadPrompt();
        // Day-5.5 review: enforce BOTH connect AND read timeout so the
        // 2s promise in ADR-0010 actually holds. Without the read timeout
        // Ollama could accept the TCP connection in 500ms and then take
        // 30s to generate; Resilience4j's @CircuitBreaker would never fire.
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMillis(500))
                        .build());
        factory.setReadTimeout(this.timeout);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.okCounter = Counter.builder("advisory_response_total").tag("status", "OK").register(metrics);
        this.timeoutCounter = Counter.builder("advisory_response_total").tag("status", "TIMED_OUT").register(metrics);
        this.malformedCounter = Counter.builder("advisory_response_total").tag("status", "MALFORMED").register(metrics);
    }

    @Override
    public boolean enabled() { return true; }

    @Override
    @CircuitBreaker(name = "advisory", fallbackMethod = "fallback")
    public AdvisoryResponse adviseOn(Decision decision) {
        String prompt = render(decision);
        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false,
                "format", "json",
                "options", Map.of("temperature", 0.2, "num_predict", 256));
        try {
            String response = http.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (RestClientException e) {
            log.warn("advisory: ollama call failed cause={}", e.toString());
            timeoutCounter.increment();
            return AdvisoryResponse.timedOut();
        }
    }

    /** Resilience4j fallback when the breaker is open or the call panics. */
    @SuppressWarnings("unused")
    public AdvisoryResponse fallback(Decision decision, Throwable t) {
        log.warn("advisory: circuit breaker open / call failed — returning UNAVAILABLE. cause={}", t.toString());
        return AdvisoryResponse.unavailable();
    }

    private AdvisoryResponse parse(String raw) {
        if (raw == null || raw.isBlank()) {
            malformedCounter.increment();
            return AdvisoryResponse.malformed();
        }
        try {
            JsonNode root = json.readTree(raw);
            String inner = root.path("response").asText();
            JsonNode parsed = json.readTree(inner);
            String summary = parsed.path("summary").asText(null);
            double confidence = parsed.path("confidence").asDouble(0.0);
            List<String> concerns = new ArrayList<>();
            parsed.path("concerns").forEach(c -> concerns.add(c.asText()));
            if (summary == null || summary.isBlank()) {
                malformedCounter.increment();
                return AdvisoryResponse.malformed();
            }
            okCounter.increment();
            return new AdvisoryResponse(AdvisoryStatus.OK, summary, concerns, confidence, true);
        } catch (Exception e) {
            log.warn("advisory: parse failure cause={} sample={}", e.toString(),
                    raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
            malformedCounter.increment();
            return AdvisoryResponse.malformed();
        }
    }

    private String render(Decision d) {
        StringBuilder rules = new StringBuilder();
        d.matchedRules().forEach(r -> rules.append(r.ruleId()).append(' '));
        return promptTemplate
                .replace("{status}", d.status().name())
                .replace("{score}", String.format("%.2f", d.score()))
                .replace("{rules}", rules.toString().trim().isEmpty() ? "(none)" : rules.toString().trim());
    }

    private static String loadPrompt() throws IOException {
        try (var in = new ClassPathResource("prompts/advisory-v1.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
