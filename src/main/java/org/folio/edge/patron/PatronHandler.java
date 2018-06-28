package org.folio.edge.patron;

import static org.folio.edge.core.Constants.PARAM_API_KEY;
import static org.folio.edge.core.Constants.TEXT_PLAIN;
import static org.folio.edge.patron.Constants.MSG_ACCESS_DENIED;
import static org.folio.edge.patron.Constants.MSG_INTERNAL_SERVER_ERROR;
import static org.folio.edge.patron.Constants.MSG_REQUEST_TIMEOUT;
import static org.folio.edge.patron.Constants.PARAM_HOLD_ID;
import static org.folio.edge.patron.Constants.PARAM_INCLUDE_CHARGES;
import static org.folio.edge.patron.Constants.PARAM_INCLUDE_HOLDS;
import static org.folio.edge.patron.Constants.PARAM_INCLUDE_LOANS;
import static org.folio.edge.patron.Constants.PARAM_INSTANCE_ID;
import static org.folio.edge.patron.Constants.PARAM_ITEM_ID;
import static org.folio.edge.patron.Constants.PARAM_PATRON_ID;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.folio.edge.core.InstitutionalUserHelper;
import org.folio.edge.core.InstitutionalUserHelper.MalformedApiKeyException;
import org.folio.edge.core.model.ClientInfo;
import org.folio.edge.core.security.SecureStore;
import org.folio.edge.patron.utils.PatronIdHelper;
import org.folio.edge.patron.utils.PatronOkapiClient;
import org.folio.edge.patron.utils.PatronOkapiClientFactory;

import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

public class PatronHandler {
  private static final Logger logger = Logger.getLogger(PatronHandler.class);

  private InstitutionalUserHelper iuHelper;
  private PatronOkapiClientFactory ocf;

  public PatronHandler(SecureStore secureStore, PatronOkapiClientFactory ocf) {
    this.ocf = ocf;
    this.iuHelper = new InstitutionalUserHelper(secureStore);
  }

  private void handleCommon(RoutingContext ctx, String[] requiredParams, String[] optionalParams,
      TwoParamVoidFunction<PatronOkapiClient, Map<String, String>> action) {
    String key = ctx.request().getParam(PARAM_API_KEY);
    if (key == null || key.isEmpty()) {
      accessDenied(ctx);
      return;
    }

    String extPatronId = ctx.request().getParam(PARAM_PATRON_ID);
    if (extPatronId == null || extPatronId.isEmpty()) {
      badRequest(ctx, "Missing required parameter: " + PARAM_PATRON_ID);
      return;
    }

    Map<String, String> params = new HashMap<>(requiredParams.length);
    for (String param : requiredParams) {
      String value = ctx.request().getParam(param);
      if (value == null || value.isEmpty()) {
        badRequest(ctx, "Missing required parameter: " + param);
        return;
      } else {
        params.put(param, value);
      }
    }

    for (String param : optionalParams) {
      params.put(param, ctx.request().getParam(param));
    }

    ClientInfo clientInfo;
    try {
      clientInfo = InstitutionalUserHelper.parseApiKey(key);
    } catch (MalformedApiKeyException e) {
      accessDenied(ctx);
      return;
    }

    final PatronOkapiClient client = ocf.getPatronOkapiClient(clientInfo.tenantId);

    getTokenAndPatron(ctx, client, clientInfo, extPatronId, params,
        () -> action.apply(client, params));
  }

  private void getTokenAndPatron(RoutingContext ctx, PatronOkapiClient client, ClientInfo clientInfo,
      String extPatronId, Map<String, String> params,
      Runnable action) {
    iuHelper.getToken(client,
        clientInfo.clientId,
        clientInfo.tenantId,
        clientInfo.username)
      .thenAcceptAsync(token -> {
        client.setToken(token);
        PatronIdHelper.lookupPatron(client, clientInfo.tenantId, extPatronId)
          .thenAcceptAsync(patronId -> {
            params.put(PARAM_PATRON_ID, patronId);
            action.run();
          })
          .exceptionally(t -> {
            if (t instanceof TimeoutException) {
              requestTimeout(ctx);
            } else {
              notFound(ctx, "Unable to find patron " + extPatronId);
            }
            return null;
          });
      })
      .exceptionally(t -> {
        if (t instanceof TimeoutException) {
          requestTimeout(ctx);
        } else {
          accessDenied(ctx);
        }
        return null;
      });
  }

  public void handleGetAccount(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] {},
        new String[] { PARAM_INCLUDE_LOANS, PARAM_INCLUDE_CHARGES, PARAM_INCLUDE_HOLDS },
        (client, params) -> {
          boolean includeLoans = Boolean.parseBoolean(params.get(PARAM_INCLUDE_LOANS));
          boolean includeCharges = Boolean.parseBoolean(params.get(PARAM_INCLUDE_CHARGES));
          boolean includeHolds = Boolean.parseBoolean(params.get(PARAM_INCLUDE_HOLDS));

          client.getAccount(params.get(PARAM_PATRON_ID),
              includeLoans,
              includeCharges,
              includeHolds,
              ctx.request().headers(),
              resp -> handleProxyResponse(ctx, resp),
              t -> handleProxyException(ctx, t));
        });
  }

  public void handleRenew(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_ITEM_ID },
        new String[] {},
        (client, params) -> client.renewItem(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_ITEM_ID),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));

  }

  public void handlePlaceItemHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_ITEM_ID },
        new String[] {},
        (client, params) -> client.placeItemHold(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_ITEM_ID),
            ctx.getBodyAsString(),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));
  }

  public void handleEditItemHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_ITEM_ID, PARAM_HOLD_ID },
        new String[] {},
        (client, params) -> client.editItemHold(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_ITEM_ID),
            params.get(PARAM_HOLD_ID),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));

  }

  public void handleRemoveItemHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_ITEM_ID, PARAM_HOLD_ID },
        new String[] {},
        (client, params) -> client.removeItemHold(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_ITEM_ID),
            params.get(PARAM_HOLD_ID),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));
  }

  public void handlePlaceInstanceHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_INSTANCE_ID },
        new String[] {},
        (client, params) -> client.placeInstanceHold(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_INSTANCE_ID),
            ctx.getBodyAsString(),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));
  }

  public void handleEditInstanceHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_INSTANCE_ID, PARAM_HOLD_ID },
        new String[] {},
        (client, params) -> client.editInstanceHold(
            params.get(PARAM_PATRON_ID),
            params.get(PARAM_INSTANCE_ID),
            params.get(PARAM_HOLD_ID),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));
  }

  public void handleRemoveInstanceHold(RoutingContext ctx) {
    handleCommon(ctx,
        new String[] { PARAM_INSTANCE_ID, PARAM_HOLD_ID },
        new String[] {},
        (client, params) -> client.removeInstanceHold(params.get(PARAM_PATRON_ID),
            params.get(PARAM_INSTANCE_ID),
            params.get(PARAM_HOLD_ID),
            ctx.request().headers(),
            resp -> handleProxyResponse(ctx, resp),
            t -> handleProxyException(ctx, t)));
  }

  private void handleProxyResponse(RoutingContext ctx, HttpClientResponse resp) {
    final StringBuilder body = new StringBuilder();
    resp.handler(buf -> {
      logger.debug("read Bytes: " + buf.toString());
      body.append(buf);
    }).endHandler(v -> {
      ctx.response().setStatusCode(resp.statusCode());

      String contentType = resp.getHeader(HttpHeaders.CONTENT_TYPE);
      if (contentType != null) {
        ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, contentType);
      }

      ctx.response().end(body.toString());
    });
  }

  private void handleProxyException(RoutingContext ctx, Throwable t) {
    logger.error("Exception calling mod-patron (Place Instance Hold)", t);
    if (t instanceof TimeoutException) {
      requestTimeout(ctx);
    } else {
      internalServerError(ctx);
    }
  }

  private void accessDenied(RoutingContext ctx) {
    ctx.response()
      .setStatusCode(401)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(MSG_ACCESS_DENIED);
  }

  private void badRequest(RoutingContext ctx, String body) {
    ctx.response()
      .setStatusCode(400)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(body);
  }

  private void notFound(RoutingContext ctx, String body) {
    ctx.response()
      .setStatusCode(404)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(body);
  }

  private void requestTimeout(RoutingContext ctx) {
    ctx.response()
      .setStatusCode(408)
      .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .end(MSG_REQUEST_TIMEOUT);
  }

  private void internalServerError(RoutingContext ctx) {
    if (!ctx.response().ended()) {
      ctx.response()
        .setStatusCode(500)
        .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
        .end(MSG_INTERNAL_SERVER_ERROR);
    }
  }

  @FunctionalInterface
  private interface TwoParamVoidFunction<A, B> {
    public void apply(A a, B b);
  }
}
