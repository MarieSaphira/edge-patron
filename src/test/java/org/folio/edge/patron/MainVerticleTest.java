package org.folio.edge.patron;

import static junit.framework.TestCase.assertTrue;
import static org.folio.edge.core.Constants.APPLICATION_JSON;
import static org.folio.edge.core.Constants.DAY_IN_MILLIS;
import static org.folio.edge.core.Constants.SYS_LOG_LEVEL;
import static org.folio.edge.core.Constants.SYS_OKAPI_URL;
import static org.folio.edge.core.Constants.SYS_PORT;
import static org.folio.edge.core.Constants.SYS_REQUEST_TIMEOUT_MS;
import static org.folio.edge.core.Constants.SYS_SECURE_STORE_PROP_FILE;
import static org.folio.edge.core.Constants.TEXT_PLAIN;
import static org.folio.edge.core.utils.test.MockOkapi.X_DURATION;
import static org.folio.edge.patron.Constants.MSG_ACCESS_DENIED;
import static org.folio.edge.patron.Constants.MSG_REQUEST_TIMEOUT;
import static org.folio.edge.patron.utils.PatronMockOkapi.holdReqTs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.http.HttpHeaders;
import org.apache.log4j.Logger;
import org.folio.edge.core.utils.ApiKeyUtils;
import org.folio.edge.core.utils.test.TestUtils;
import org.folio.edge.patron.model.Account;
import org.folio.edge.patron.model.error.ErrorMessage;
import org.folio.edge.patron.model.Hold;
import org.folio.edge.patron.model.Loan;
import org.folio.edge.patron.utils.PatronMockOkapi;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class MainVerticleTest {

  private static final Logger logger = Logger.getLogger(MainVerticleTest.class);

  private static final String extPatronId = PatronMockOkapi.extPatronId;
  private static final String patronId = PatronMockOkapi.patronId;
  private static final String itemId = UUID.randomUUID().toString();
  private static final String instanceId = UUID.randomUUID().toString();
  private static final String holdId = UUID.randomUUID().toString();
  private static final String apiKey = ApiKeyUtils.generateApiKey(10, "diku", "diku");
  private static final String badApiKey = apiKey + "0000";
  private static final String unknownTenantApiKey = ApiKeyUtils.generateApiKey(10, "bogus", "diku");;

  private static final long requestTimeoutMs = 3000L;

  private static Vertx vertx;
  private static PatronMockOkapi mockOkapi;

  @BeforeClass
  public static void setUpOnce(TestContext context) throws Exception {
    int okapiPort = TestUtils.getPort();
    int serverPort = TestUtils.getPort();

    List<String> knownTenants = new ArrayList<>();
    knownTenants.add(ApiKeyUtils.parseApiKey(apiKey).tenantId);

    mockOkapi = spy(new PatronMockOkapi(okapiPort, knownTenants));
    mockOkapi.start(context);

    vertx = Vertx.vertx();

    System.setProperty(SYS_PORT, String.valueOf(serverPort));
    System.setProperty(SYS_OKAPI_URL, "http://localhost:" + okapiPort);
    System.setProperty(SYS_SECURE_STORE_PROP_FILE, "src/main/resources/ephemeral.properties");
    System.setProperty(SYS_LOG_LEVEL, "DEBUG");
    System.setProperty(SYS_REQUEST_TIMEOUT_MS, String.valueOf(requestTimeoutMs));

    final DeploymentOptions opt = new DeploymentOptions();
    vertx.deployVerticle(MainVerticle.class.getName(), opt, context.asyncAssertSuccess());

    RestAssured.baseURI = "http://localhost:" + serverPort;
    RestAssured.port = serverPort;
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
  }

  @AfterClass
  public static void tearDownOnce(TestContext context) {
    logger.info("Shutting down server");
    final Async async = context.async();
    vertx.close(res -> {
      if (res.failed()) {
        logger.error("Failed to shut down edge-patron server", res.cause());
        fail(res.cause().getMessage());
      } else {
        logger.info("Successfully shut down edge-patron server");
      }

      logger.info("Shutting down mock Okapi");
      mockOkapi.close(context);
      async.complete();
    });
  }

  @Test
  public void testAdminHealth(TestContext context) {
    logger.info("=== Test the health check endpoint ===");

    final Response resp = RestAssured
      .get("/admin/health")
      .then()
      .contentType(TEXT_PLAIN)
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
      .extract()
      .response();

    assertEquals("\"OK\"", resp.body().asString());
  }

  @Test
  public void testGetAccountUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test getAccount with unknown apiKey (tenant) ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s", extPatronId, unknownTenantApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testGetAccountBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test getAccount with malformed apiKey ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s", extPatronId, badApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testGetAccountPatronFound(TestContext context) throws Exception {
    logger.info("=== Test request where patron is found ===");

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s", extPatronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, false, false, false);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test request where patron isn't found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s", PatronMockOkapi.extPatronId_notFound, apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testGetAccountNoApiKey(TestContext context) throws Exception {
    logger.info("=== Test request with malformed apiKey ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s", patronId))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testGetAccountPatronFoundIncludeLoans(TestContext context) throws Exception {
    logger.info("=== Test getAccount/includeLoans ===");

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s&includeLoans=true", patronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, true, false, false);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountPatronFoundIncludeCharges(TestContext context) throws Exception {
    logger.info("=== Test getAccount/includeCharges ===");

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s&includeCharges=true", patronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, false, true, false);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountPatronFoundIncludeHolds(TestContext context) throws Exception {
    logger.info("=== Test getAccount/includeHolds ===");

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s&includeHolds=true", patronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, false, false, true);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountPatronFoundIncludeAll(TestContext context) throws Exception {
    logger.info("=== Test getAccount/includeLoans,includeCharges,includeHolds ===");

    final Response resp = RestAssured
      .get(String.format("/patron/account/%s?apikey=%s&includeLoans=true&includeHolds=true&includeCharges=true",
          patronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, true, true, true);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountEmptyQueryArgs(TestContext context) throws Exception {
    logger.info("=== Test getAccount with empty query args ===");

    final Response resp = RestAssured
      .get(
          String.format("/patron/account/%s?apikey=%s&includeCharges=&includeLoans=&includeCharges=", patronId, apiKey))
      .then()
      .statusCode(200)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    String expected = PatronMockOkapi.getAccountJson(patronId, false, false, false);
    String actual = resp.body().asString();

    assertEquals(expected, actual);
  }

  @Test
  public void testGetAccountRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test getAccount request timeout ===");

    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .header(X_DURATION, requestTimeoutMs * 2)
      .get(String.format("/patron/account/%s?apikey=%s", patronId, apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

      assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
      assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testRenewSuccess(TestContext context) throws Exception {
    logger.info("=== Test successfulrenewal ===");

    final Response resp = RestAssured
      .post(
          String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, itemId, apiKey))
      .then()
      .statusCode(201)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    Loan expected = Loan.fromJson(PatronMockOkapi.getLoanJson(patronId, itemId));
    Loan actual = Loan.fromJson(resp.body().asString());

    assertEquals(expected, actual);
  }

  @Test
  public void testRenewPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test renew w/ patron not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .post(
          String.format("/patron/account/%s/item/%s/renew?apikey=%s", PatronMockOkapi.extPatronId_notFound, itemId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testRenewItemNotFound(TestContext context) throws Exception {
    logger.info("=== Test renew w/ item not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .post(
          String.format("/patron/account/%s/item/%s/renew?apikey=%s", extPatronId, PatronMockOkapi.itemId_notFound,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.itemId_notFound + " not found", msg.message);
    assertEquals(expectedStatusCode,  msg.httpStatusCode);
  }

  @Test
  public void testRenewUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test renew with unknown apiKey (tenant) ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, itemId, unknownTenantApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testRenewBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test renew with malformed apiKey ===");

    final Response resp = RestAssured
      .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, itemId, badApiKey))
      .then()
      .statusCode(401)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(401, msg.httpStatusCode);
  }

  @Test
  public void testRenewRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test renew request timeout ===");

    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .header(X_DURATION, requestTimeoutMs * 2)
      .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, itemId,
          apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

    @Test
    public void testRenewRequesMaxRenewal(TestContext context) throws Exception {
        logger.info("=== Test MAX Renewal ===");

        int expectedStatusCode = 422;

        final Response resp = RestAssured
                .with()
                .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, PatronMockOkapi.itemId_reached_max_renewals,
                        apiKey))
                .then()
                .contentType(APPLICATION_JSON)
                .statusCode(expectedStatusCode)
                .extract()
                .response();

        ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

        assertEquals("loan has reached its maximum number of renewals", msg.message);
        assertEquals(expectedStatusCode, msg.httpStatusCode);
    }

    @Test
    public void testRenewRequesMaxRenewalWithEmptyErrors(TestContext context) throws Exception {
        logger.info("=== Test MAX Renewal With Empty Errors ===");

        int expectedStatusCode = 422;

        final Response resp = RestAssured
                .with()
                .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, PatronMockOkapi.itemId_reached_max_renewals_empty_error_msg,
                        apiKey))
                .then()
                .contentType(APPLICATION_JSON)
                .statusCode(expectedStatusCode)
                .extract()
                .response();

        ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

        assertEquals("No error message found", msg.message);
        assertEquals(expectedStatusCode, msg.httpStatusCode);
    }

    @Test
    public void testRenewRequesMaxRenewalWithBadJson(TestContext context) throws Exception {
        logger.info("=== Test MAX Renewal With Bad Json ===");

        int expectedStatusCode = 422;

        final Response resp = RestAssured
                .with()
                .post(String.format("/patron/account/%s/item/%s/renew?apikey=%s", patronId, PatronMockOkapi.itemId_reached_max_renewals_bad_json_msg,
                        apiKey))
                .then()
                .contentType(APPLICATION_JSON)
                .statusCode(expectedStatusCode)
                .extract()
                .response();

        ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

        assertEquals("A problem encountered when extracting error message", msg.message);
        assertEquals(expectedStatusCode, msg.httpStatusCode);
    }

  @Test
  public void testPlaceInstanceHoldSuccess(TestContext context) throws Exception {
    logger.info("=== Test place instance hold success ===");

    Hold hold = PatronMockOkapi.getHold(instanceId);
    int expectedStatusCode = 501;


    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", patronId, instanceId, apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceInstanceHoldPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test place instance hold w/ patron not found ===");

    Hold hold = PatronMockOkapi.getHold(instanceId);
    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", PatronMockOkapi.extPatronId_notFound,
              instanceId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceInstanceHoldInstanceNotFound(TestContext context) throws Exception {
    logger.info("=== Test place instance hold w/ instance not found ===");

    Hold hold = PatronMockOkapi.getHold(PatronMockOkapi.instanceId_notFound);
    int expectedStatusCode = 501;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", patronId, PatronMockOkapi.instanceId_notFound,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceInstanceHoldUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test place instance hold with unknown apiKey (tenant) ===");

    Hold hold = PatronMockOkapi.getHold(instanceId);
    int statusCode = 401;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", patronId, instanceId, unknownTenantApiKey))
      .then()
      .statusCode(statusCode)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

      assertEquals(MSG_ACCESS_DENIED, msg.message);
      assertEquals(statusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceInstanceHoldBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test place instance hold with malformed apiKey ===");

    Hold hold = PatronMockOkapi.getHold(instanceId);
    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", patronId, instanceId, badApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceInstanceHoldRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test place instance hold request timeout ===");

    Hold hold = PatronMockOkapi.getHold(instanceId);
    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .header(X_DURATION, requestTimeoutMs * 2)
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/instance/%s/hold?apikey=%s", patronId, instanceId,
              apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
    assertEquals(expectedStatusCode,  msg.httpStatusCode);
  }

  @Test
  public void testPlaceItemHoldSuccess(TestContext context) throws Exception {
    logger.info("=== Test successful item hold ===");

    Hold hold = PatronMockOkapi.getHold(itemId, new Date(holdReqTs));

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, itemId, apiKey))
      .then()
      .statusCode(201)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    Hold expected = Hold.fromJson(PatronMockOkapi.getPlacedHoldJson(hold));
    Hold actual = Hold.fromJson(resp.body().asString());
    validateHolds(expected, actual);
  }

  @Test
  public void testPlaceItemHoldWithoutRequestDateSuccess(TestContext context) throws Exception {
    logger.info("=== Test successful item hold ===");

    Hold hold = PatronMockOkapi.getHold(itemId, null);

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
        String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, itemId, apiKey))
      .then()
      .statusCode(201)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    Hold expected = Hold.fromJson(PatronMockOkapi.getPlacedHoldJson(hold));
    Hold actual = Hold.fromJson(resp.body().asString());
    validateHolds(expected, actual);
  }

  @Test
  public void testPlaceItemHoldPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test place item hold w/ patron not found ===");

    Hold hold = PatronMockOkapi.getHold(itemId);
    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", PatronMockOkapi.extPatronId_notFound, itemId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceItemHoldItemNotFound(TestContext context) throws Exception {
    logger.info("=== Test place item hold w/ item not found ===");

    Hold hold = PatronMockOkapi.getHold(PatronMockOkapi.itemId_notFound);
    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, PatronMockOkapi.itemId_notFound, apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.itemId_notFound + " not found", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceItemHoldUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test place item hold with unknown apiKey (tenant) ===");

    Hold hold = PatronMockOkapi.getHold(itemId);
    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, itemId, unknownTenantApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceItemHoldBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test place item hold with malformed apiKey ===");

    Hold hold = PatronMockOkapi.getHold(itemId);
    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, itemId, badApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testPlaceItemHoldRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test place item hold request timeout ===");

    Hold hold = PatronMockOkapi.getHold(itemId);
    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .body(hold.toJson())
      .header(X_DURATION, requestTimeoutMs * 2)
      .contentType(APPLICATION_JSON)
      .post(
          String.format("/patron/account/%s/item/%s/hold?apikey=%s", patronId, itemId,
              apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testRemoveItemHoldSuccess(TestContext context) throws Exception {
    logger.info("=== Test remove item hold success ===");

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", patronId, itemId, holdId, apiKey))
      .then()
      .statusCode(201)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    Hold expected = Hold.fromJson(PatronMockOkapi.getRemovedHoldJson(itemId));
    Hold actual = Hold.fromJson(resp.body().asString());

    assertEquals(expected, actual);
  }

  @Test
  public void testRemoveItemHoldPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test remove item hold w/ patron not found ===");
    int statusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", PatronMockOkapi.extPatronId_notFound, itemId,
              holdId,
              apiKey))
      .then()
      .statusCode(statusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

      assertEquals(statusCode, msg.httpStatusCode);
      assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
  }

  @Test
  public void testRemoveItemHoldItemNotFound(TestContext context) throws Exception {
    logger.info("=== Test remove item hold w/ item not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, PatronMockOkapi.itemId_notFound,
              holdId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.itemId_notFound + " not found", msg.message);
    assertEquals(expectedStatusCode,  msg.httpStatusCode);
  }

  @Test
  public void testRemoveItemHoldHoldNotFound(TestContext context) throws Exception {
    logger.info("=== Test remove item hold w/ hold not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId,
              PatronMockOkapi.holdReqId_notFound,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.holdReqId_notFound  + " not found", msg.message);
    assertEquals(expectedStatusCode,  msg.httpStatusCode);
  }

  @Test
  public void testRemoveItemHoldUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test remove item hold with unknown apiKey (tenant) ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId,
              unknownTenantApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

      ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testRemoveItemHoldBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test remove item hold with malformed apiKey ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .delete(String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId, badApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    try {
        ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

        assertEquals(MSG_ACCESS_DENIED, msg.message);
        assertEquals(expectedStatusCode,  msg.httpStatusCode);
    }
    catch(IOException ex){
        Assert.fail("Exception threw: " + ex.getMessage());
    }
  }

  @Test
  public void testRemoveItemHoldRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test remove item hold request timeout ===");
    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .header(X_DURATION, requestTimeoutMs * 2)
      .delete(String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId,
          apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldSuccess(TestContext context) throws Exception {
    logger.info("=== Test edit item hold success ===");

    int expectedStatusCode = 501;

    Hold hold = PatronMockOkapi.getHold(itemId);

    final Response resp = RestAssured
      .put(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, hold.requestId, apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldPatronNotFound(TestContext context) throws Exception {
    logger.info("=== Test edit item hold w/ patron not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", PatronMockOkapi.extPatronId_notFound, itemId,
              holdId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals("Unable to find patron " + PatronMockOkapi.extPatronId_notFound, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldItemNotFound(TestContext context) throws Exception {
    logger.info("=== Test edit item hold w/ item not found ===");
    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, PatronMockOkapi.itemId_notFound,
              holdId,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.itemId_notFound + " not found", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldHoldNotFound(TestContext context) throws Exception {
    logger.info("=== Test edit item hold w/ hold not found ===");

    int expectedStatusCode = 404;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId,
              PatronMockOkapi.holdReqId_notFound,
              apiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(PatronMockOkapi.holdReqId_notFound + " not found", msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldUnknownApiKey(TestContext context) throws Exception {
    logger.info("=== Test edit item hold with unknown apiKey (tenant) ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .delete(
          String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId,
              unknownTenantApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldBadApiKey(TestContext context) throws Exception {
    logger.info("=== Test edit item hold with malformed apiKey ===");

    int expectedStatusCode = 401;

    final Response resp = RestAssured
      .delete(String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId, badApiKey))
      .then()
      .statusCode(expectedStatusCode)
      .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());

    assertEquals(MSG_ACCESS_DENIED, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testEditItemHoldRequestTimeout(TestContext context) throws Exception {
    logger.info("=== Test edit item hold request timeout ===");

    int expectedStatusCode = 408;

    final Response resp = RestAssured
      .with()
      .header(X_DURATION, requestTimeoutMs * 2)
      .delete(String.format("/patron/account/%s/item/%s/hold/%s?apikey=%s", extPatronId, itemId, holdId,
          apiKey))
      .then()
      .contentType(APPLICATION_JSON)
      .statusCode(expectedStatusCode)
      .extract()
      .response();

    ErrorMessage msg = ErrorMessage.fromJson(resp.body().asString());
    assertEquals(MSG_REQUEST_TIMEOUT, msg.message);
    assertEquals(expectedStatusCode, msg.httpStatusCode);
  }

  @Test
  public void testCachedToken(TestContext context) throws Exception {
    logger.info("=== Test the tokens are cached and reused ===");

    Account expected = Account.fromJson(PatronMockOkapi.getAccountJson(patronId, false, false, false));
    int iters = 5;

    for (int i = 0; i < iters; i++) {
      final Response resp = RestAssured
        .get(String.format("/patron/account/%s?apikey=%s", extPatronId, apiKey))
        .then()
        .contentType(APPLICATION_JSON)
        .statusCode(200)
        .header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .extract()
        .response();

      assertEquals(expected, Account.fromJson(resp.body().asString()));
    }

    verify(mockOkapi).loginHandler(any());
    verify(mockOkapi, atLeast(iters)).getAccountHandler(any());
  }

  private void validateHolds(Hold expectedHolds, Hold actualHolds) {
    assertEquals(expectedHolds.requestId, actualHolds.requestId);
    assertEquals(expectedHolds.pickupLocationId, actualHolds.pickupLocationId);
    assertEquals(expectedHolds.queuePosition, actualHolds.queuePosition);
    assertEquals(expectedHolds.expirationDate, actualHolds.expirationDate);
    assertEquals(expectedHolds.status, actualHolds.status);
    assertEquals(expectedHolds.item, actualHolds.item);

    long expectedRequestDateTs = expectedHolds.requestDate != null
      //have to add 1 day's milliseconds because the Request timestamp is 1 day in the past
      ? expectedHolds.requestDate.getTime() + DAY_IN_MILLIS
      : Instant.now().toEpochMilli();

    long actualRequestDateTs = actualHolds.requestDate != null
      ? actualHolds.requestDate.getTime()
      : Instant.now().toEpochMilli();

    //check that the actual timestamp is within a minute of the expected timestamp
    assertTrue((actualRequestDateTs - expectedRequestDateTs)/1000 < 60);
  }
}

