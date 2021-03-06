package org.triplea.server.user.account.login;

import es.moki.ratelimij.dropwizard.annotation.Rate;
import es.moki.ratelimij.dropwizard.annotation.RateLimited;
import es.moki.ratelimij.dropwizard.filter.KeyPart;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import lombok.Builder;
import org.triplea.http.client.SystemIdHeader;
import org.triplea.http.client.lobby.login.LobbyLoginClient;
import org.triplea.http.client.lobby.login.LobbyLoginResponse;
import org.triplea.http.client.lobby.login.LoginRequest;
import org.triplea.server.http.HttpController;

@Builder
public class LoginController extends HttpController {
  @Nonnull private final LoginModule loginModule;

  @RateLimited(
      keys = {KeyPart.IP},
      rates = {@Rate(limit = 20, duration = 1, timeUnit = TimeUnit.HOURS)})
  @POST
  @Path(LobbyLoginClient.LOGIN_PATH)
  public LobbyLoginResponse login(
      @Context final HttpServletRequest request, final LoginRequest loginRequest) {
    return loginModule.doLogin(
        loginRequest, request.getHeader(SystemIdHeader.SYSTEM_ID_HEADER), request.getRemoteAddr());
  }
}
