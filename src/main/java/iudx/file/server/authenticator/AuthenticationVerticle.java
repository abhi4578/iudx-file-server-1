package iudx.file.server.authenticator;

import static iudx.file.server.common.Constants.AUTH_SERVICE_ADDRESS;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.serviceproxy.ServiceBinder;
import iudx.file.server.common.WebClientFactory;
import iudx.file.server.common.service.CatalogueService;
import iudx.file.server.common.service.impl.CatalogueServiceImpl;

public class AuthenticationVerticle extends AbstractVerticle {

  private AuthenticationService auth;
  private CatalogueService catalogueService;
  private WebClientFactory webClientFactory;
  private static final String authAddress = AUTH_SERVICE_ADDRESS;
  private ServiceBinder binder;
  private MessageConsumer<JsonObject> consumer;
  private AuthenticationService jwtAuthenticationService;


  @Override
  public void start() {

    webClientFactory = new WebClientFactory(vertx, config());
    catalogueService = new CatalogueServiceImpl(vertx, webClientFactory, config());
   // auth = new AuthenticationServiceImpl(vertx, catalogueService, webClientFactory, config());
    
    JWTAuthOptions jwtAuthOptions = new JWTAuthOptions();
    jwtAuthOptions.addPubSecKey(
        new PubSecKeyOptions()
            .setAlgorithm("ES256")
            .setBuffer("-----BEGIN PUBLIC KEY-----\n" +
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8BKf2HZ3wt6wNf30SIsbyjYPkkTS\n" +
                "GGyyM2/MGF/zYTZV9Z28hHwvZgSfnbsrF36BBKnWszlOYW0AieyAUKaKdg==\n" +
                "-----END PUBLIC KEY-----\n" +
                ""));
    
    jwtAuthOptions.getJWTOptions().setIgnoreExpiration(true);
    JWTAuth jwtAuth = JWTAuth.create(vertx, jwtAuthOptions);

    //@TODO: replace binder with jwt once auth server available.
    jwtAuthenticationService =
        new JwtAuthenticationServiceImpl(vertx, jwtAuth,  config(),catalogueService);
    

    binder = new ServiceBinder(vertx);

    consumer =
        binder.setAddress(authAddress)
            .register(AuthenticationService.class, jwtAuthenticationService);

  }

  @Override
  public void stop() {
    binder.unregister(consumer);
  }
}
