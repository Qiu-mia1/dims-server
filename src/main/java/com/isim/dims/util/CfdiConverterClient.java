package com.isim.dims.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Path;
import java.util.Map;

/** HTTP client for the internal CFDI converter service. */
@Slf4j
@Component
public class CfdiConverterClient {
    private final RestClient restClient;
    private final String convertUrl;

    public CfdiConverterClient(
            RestClient.Builder restClientBuilder,
            @Value("${dims.cfdi.converter-url:http://cfdiconverter:8098/convert}")
            String convertUrl) {
        this.restClient = restClientBuilder.build();
        this.convertUrl = convertUrl;
    }

    /** Invoke the synchronous CFDI conversion endpoint. */
    public JsonNode convert(Path workDir, Map<String, Object> payload) {
        String requestUrl = UriComponentsBuilder.fromUriString(convertUrl)
                .queryParam("work_dir", workDir.toAbsolutePath().normalize().toString())
                .build()
                .encode()
                .toUriString();
        try {
            JsonNode response = restClient.post()
                    .uri(requestUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            log.info("CFDI HTTP conversion completed: workDir={}", workDir);
            return response;
        } catch (RestClientResponseException e) {
            throw new RuntimeException(
                    "CFDI converter returned HTTP " + e.getStatusCode().value()
                            + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException("CFDI converter is unreachable: " + convertUrl, e);
        }
    }
}
