package iudx.file.server.authenticator;

import static iudx.file.server.authenticator.utilities.Constants.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import iudx.file.server.common.ServerType;
import iudx.file.server.common.WebClientFactory;
import iudx.file.server.common.service.CatalogueService;

public class AuthenticationServiceImpl implements AuthenticationService {


  private static final Logger LOGGER = LogManager.getLogger(AuthenticationServiceImpl.class);
  private final WebClientFactory webClientFactory;
  private final String authHost;
  private final int authPort;
  private final CatalogueService catalogueService;


  private final Cache<String, JsonObject> tipCache = CacheBuilder.newBuilder()
      .maximumSize(1000)
      .expireAfterAccess(CACHE_TIMEOUT, TimeUnit.MINUTES)
      .build();
  // resourceGroupCache will contains ACL info about all resource group in a resource server
  private final Cache<String, String> resourceGroupCache = CacheBuilder.newBuilder()
      .maximumSize(1000)
      .expireAfterAccess(CACHE_TIMEOUT, TimeUnit.MINUTES)
      .build();


  public AuthenticationServiceImpl(Vertx vertx,CatalogueService catalogueService, WebClientFactory webClientFactory,
      JsonObject config) {
    this.webClientFactory = webClientFactory;
    this.catalogueService=catalogueService;
    this.authHost = config.getString("authHost");
    this.authPort = config.getInteger("authPort");
  }

  @Override
  public AuthenticationService tokenInterospect(JsonObject request, JsonObject authenticationInfo,
      Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.info("userRequest : " + request);
    String token = authenticationInfo.getString("token");
    WebClient webClient;
    String idPassed = request.getJsonArray("ids").getString(0);
    if (idPassed.matches(FILE_SERVER_REGEX)) {
      LOGGER.debug("creating web client with file-server cert.");
      webClient = getWebClient(ServerType.FILE_SERVER);
    } else {
      LOGGER.debug("creating web client with rs-server cert.");
      webClient = getWebClient(ServerType.RESOURCE_SERVER);
    }

    TokenInterospectionResultContainer responseContainer =
        new TokenInterospectionResultContainer();
    Future<JsonObject> tipResponseFut = retrieveTipResponse(token, webClient);
    tipResponseFut.compose(tipResponse -> {
      responseContainer.tipResponse = tipResponse;
      LOGGER.debug("Info: TIP Response is : " + tipResponse);
      String id = request.getJsonArray("ids").getString(0);
      return catalogueService.isItemExist(id);
    }).onFailure(failure -> {
      handler.handle(Future.failedFuture("Invalid token"));
    }).onSuccess(success -> {
      responseContainer.isItemExist = success;
      Future<JsonObject> isValid =
          validateAccess(responseContainer.tipResponse, success, authenticationInfo, request);
      isValid.onComplete(validateAccesshandler -> {
        if (validateAccesshandler.succeeded()) {
          handler.handle(Future.succeededFuture());
        } else {
          handler.handle(Future.failedFuture(validateAccesshandler.cause().getMessage()));
        }
      });
    });
    return this;
  }



  private Future<JsonObject> retrieveTipResponse(String token, WebClient webClient) {
    Promise<JsonObject> promise = Promise.promise();
    JsonObject cacheResponse = tipCache.getIfPresent(token);
    if (cacheResponse == null) {
      LOGGER.debug("Cache miss calling auth server");
      // cache miss
      // call cat-server only when token not found in cache.
      JsonObject body = new JsonObject();
      body.put("token", token);
      LOGGER.info(body);
      webClient.post(authPort, authHost, "/auth/v1/token/introspect")
          .expect(ResponsePredicate.JSON)
          .sendJsonObject(body, httpResponseAsyncResult -> {
            if (httpResponseAsyncResult.failed()) {
              LOGGER.info(httpResponseAsyncResult.cause());
              promise.fail(httpResponseAsyncResult.cause());
              return;
            }
            HttpResponse<Buffer> response = httpResponseAsyncResult.result();
            if (response.statusCode() != HttpStatus.SC_OK) {
              LOGGER.error("token interospect response :"+response.bodyAsJsonObject());
              String errorMessage =
                  response.bodyAsJsonObject().getJsonObject("error").getString("message");
              promise.fail(new Throwable(errorMessage));
              return;
            }
            JsonObject responseBody = response.bodyAsJsonObject();
            LOGGER.info(responseBody);
            String cacheExpiry = Instant.now(Clock.systemUTC())
                .plus(CACHE_TIMEOUT, ChronoUnit.MINUTES)
                .toString();
            responseBody.put("cache-expiry", cacheExpiry);
            tipCache.put(token, responseBody);
            promise.complete(responseBody);
          });
    } else {
      LOGGER.debug("Cache Hit");
      promise.complete(cacheResponse);
    }
    return promise.future();
  }

  private Future<JsonObject> validateAccess(JsonObject tipResponse, boolean isExist,
      JsonObject authenticationInfo, JsonObject userRequest) {
    Promise<JsonObject> promise = Promise.promise();

    JsonArray tipResponseRequestAttribte = tipResponse.getJsonArray("request");

    List<String> allowedIds = extractAllowedIds(tipResponse);
    List<String> allowedGroupIds = allowedIds.stream()
        .map(id -> id.substring(0, id.lastIndexOf("/")))
        .collect(Collectors.toList());


    List<String> requestedIds = toList(userRequest.getJsonArray("ids"));
    List<String> requestedGroupIds = requestedIds.stream()
        .map(id -> isResourceLevelId(id) ? id.substring(0, id.lastIndexOf("/")) : id)
        .collect(Collectors.toList());


    String endpoint = authenticationInfo.getString("apiEndpoint");

    if (isExist) {
      if (isAllowedId(allowedIds, requestedIds)
          && isAllowedEndpoint(endpoint, tipResponseRequestAttribte, requestedGroupIds)) {
        promise.complete();
      } else {
        promise.fail("Operation not allowed.");
      }
    } else {
      LOGGER.debug("id doesn't exist in Catalogue server");
      promise.fail("not found in catalogue.");
    }
    return promise.future();
  }

  public boolean isAllowedId(List<String> allowedIds, List<String> requestedIds) {

    List<String> requestedGroupIDs = requestedIds.stream()
        .map(id -> isResourceLevelId(id) ? id.substring(0, id.lastIndexOf("/")) : id)
        .collect(Collectors.toList());

    List<String> allowedGroupIDs = allowedIds.stream()
        .map(id -> id.substring(0, id.lastIndexOf("/")))
        .collect(Collectors.toList());

    LOGGER.debug("allowed ids : " + allowedIds);
    LOGGER.debug("allowed group id : " + allowedGroupIDs);
    LOGGER.debug("requested id :" + requestedIds);
    LOGGER.debug("requested group id : " + requestedGroupIDs);

    return requestedGroupIDs.stream().anyMatch(item -> allowedGroupIDs.contains(item));
  }

  public boolean isAllowedEndpoint(List<String> allowedEndpoints, String endpoint) {
    return allowedEndpoints.contains(endpoint);
  }

  private boolean isAllowedEndpoint(String endpoint, JsonArray tipResponseRequestArray,
      List<String> requestedGroupIds) {
    LOGGER.debug("isAllowedEndpoint");
    boolean isAllowed = false;
    if (tipResponseRequestArray != null) {
      for (int i = 0; i < tipResponseRequestArray.size(); i++) {
        JsonObject json = tipResponseRequestArray.getJsonObject(i);
        String id = json.getString("id");
        LOGGER.debug("id :" + id);
        JsonArray allowedApis = json.getJsonArray("apis");
        LOGGER.debug("apis :" + allowedApis);
        String allowedGroupId = id.substring(0, id.lastIndexOf("/"));
        LOGGER.debug("allowedGroupId :" + allowedGroupId);
        if (requestedGroupIds.contains(allowedGroupId)) {
          if (allowedApis.contains(endpoint)) {
            isAllowed = true;
            break;
          }
        }
      }
    }
    return isAllowed;
  }

  private List<String> extractAllowedIds(JsonObject json) {
    List<String> allowedIds = new ArrayList<String>();
    json.getJsonArray("request").forEach(requestJson -> {
      JsonObject idJson = (JsonObject) requestJson;
      allowedIds.add(idJson.getString("id"));
    });
    return allowedIds;
  }

  private class TokenInterospectionResultContainer {
    JsonObject tipResponse;
    HashMap<String, Boolean> catResponse;
    Boolean isItemExist;
  }

  private <T> List<T> toList(JsonArray arr) {
    if (arr == null) {
      return null;
    } else {
      return (List<T>) arr.getList();
    }
  }


  public boolean isResourceLevelId(String id) {
    return id.split("/").length >= 5;
  }

  public WebClient getWebClient(ServerType serverType) {
    return webClientFactory.getWebClientFor(serverType);
  }

}
