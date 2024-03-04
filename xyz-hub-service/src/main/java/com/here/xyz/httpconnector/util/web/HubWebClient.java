/*
 * Copyright (C) 2017-2024 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.httpconnector.util.web;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static com.here.xyz.XyzSerializable.deserialize;
import static java.net.http.HttpClient.Redirect.NORMAL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.here.xyz.XyzSerializable;
import com.here.xyz.events.ContextAwareEvent.SpaceContext;
import com.here.xyz.httpconnector.CService;
import com.here.xyz.hub.connectors.models.Connector;
import com.here.xyz.hub.connectors.models.Space;
import com.here.xyz.models.hub.Tag;
import com.here.xyz.responses.StatisticsResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

public class HubWebClient {
  private static ExpiringMap<String, Connector> connectorCache = ExpiringMap.builder()
      .expirationPolicy(ExpirationPolicy.CREATED)
      .expiration(3, TimeUnit.MINUTES)
      .build();

  public static Space loadSpace(String spaceId) throws HubWebClientException {
    try {
      return deserialize(request(HttpRequest.newBuilder()
          .uri(uri("/spaces/" + spaceId))
          .build()).body(), Space.class);
    }
    catch (JsonProcessingException e) {
      throw new HubWebClientException("Error deserializing response", e);
    }
  }

  public static void patchSpace(String spaceId, Map<String, Object> spaceUpdates) throws HubWebClientException {
    request(HttpRequest.newBuilder()
        .uri(uri("/spaces/" + spaceId))
        .header(CONTENT_TYPE, JSON_UTF_8.toString())
        .method("PATCH", BodyPublishers.ofByteArray(XyzSerializable.serialize(spaceUpdates).getBytes()))
        .build());
  }

  public static StatisticsResponse loadSpaceStatistics(String spaceId, SpaceContext context) throws HubWebClientException {
    try {
      return deserialize(request(HttpRequest.newBuilder()
          .uri(uri("/spaces/" + spaceId + "/statistics" + (context == null ? "" : "?context=" + context)))
          .build()).body(), StatisticsResponse.class);
    }
    catch (JsonProcessingException e) {
      throw new HubWebClientException("Error deserializing response", e);
    }
  }

  public static StatisticsResponse loadSpaceStatistics(String spaceId) throws HubWebClientException {
    return loadSpaceStatistics(spaceId, null);
  }

  public static Connector loadConnector(String connectorId) throws HubWebClientException {
    Connector cachedConnector = connectorCache.get(connectorId);
    if (cachedConnector != null)
      return cachedConnector;
    try {
      Connector connector = deserialize(request(HttpRequest.newBuilder()
          .uri(uri("/connectors/" + connectorId))
          .build()).body(), Connector.class);
      connectorCache.put(connectorId, connector);
      return connector;
    }
    catch (JsonProcessingException e) {
      throw new HubWebClientException("Error deserializing response", e);
    }
  }

  public static List<Connector> loadConnectors() throws HubWebClientException {
    //TODO: Add caching also here
    try {
      return deserialize(request(HttpRequest.newBuilder()
          .uri(uri("/connectors"))
          .build()).body(), new TypeReference<>() {});
    }
    catch (JsonProcessingException e) {
      throw new HubWebClientException("Error deserializing response", e);
    }
  }

  public static void postTag(String spaceId, Tag tag) throws HubWebClientException {
    request(HttpRequest.newBuilder()
        .uri(uri("/spaces/" + spaceId + "/tags"))
        .header(CONTENT_TYPE, JSON_UTF_8.toString())
        .method("POST", BodyPublishers.ofByteArray(tag.serialize().getBytes()))
        .build());
  }

  public static Tag deleteTag(String spaceId, String tagId) throws HubWebClientException {
    try {
     return 
      deserialize(
       request(HttpRequest.newBuilder()
         .DELETE()
         .uri(uri("/spaces/" + spaceId + "/tags/" + tagId ))
         .header(CONTENT_TYPE, JSON_UTF_8.toString())
         .build()).body(),Tag.class);
    }
    catch (JsonProcessingException e) {
      throw new HubWebClientException("Error deserializing response", e);
    }
  }

  private static URI uri(String path) {
      return URI.create(CService.configuration.HUB_ENDPOINT + path);
  }

  private static HttpClient client() {
    return HttpClient.newBuilder().followRedirects(NORMAL).build();
  }

  private static HttpResponse<byte[]> request(HttpRequest request) throws HubWebClientException {
    try {
      HttpResponse<byte[]> response = client().send(request, BodyHandlers.ofByteArray());
      if (response.statusCode() >= 400)
        throw new ErrorResponseException("Received error response with status code: " + response.statusCode(), response);
      return response;
    }
    catch (IOException e) {
      throw new HubWebClientException("Error sending the request to hub or receiving the response", e);
    }
    catch (InterruptedException e) {
      throw new HubWebClientException("Request was interrupted.", e);
    }
  }

  public static class HubWebClientException extends Exception {

    public HubWebClientException(String message) {
      super(message);
    }

    public HubWebClientException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class ErrorResponseException extends HubWebClientException {
    private HttpResponse<byte[]> errorResponse;
    public ErrorResponseException(String message, HttpResponse<byte[]> errorResponse) {
      super(message);
      this.errorResponse = errorResponse;
    }

    public HttpResponse<byte[]> getErrorResponse() {
      return errorResponse;
    }
  }
}
