package org.folio.edge.patron.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.log4j.Logger;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class UserTest {

  private static final Logger logger = Logger.getLogger(org.folio.edge.patron.model.UserTest.class);
  private static final String SCHEMA = "ramls/user.json";
  private static final String XSD = "ramls/patron.xsd"; //TODO add user

  private org.everit.json.schema.Schema jsonValidator;
  private Validator xmlValidator;

  private User user;

  @Before
  public void setUp() throws Exception {
    user = User.builder()
      .active(true)
      .type("patron")
      .patronGroup(UUID.randomUUID().toString())
      .lastName("Doe")
      .firstName("Jane")
      .email("jane.doe@test.com")
      .build();

    SchemaFactory schemaFactory = SchemaFactory
      .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    Schema schema = schemaFactory.newSchema(new File(XSD));
    xmlValidator = schema.newValidator();

    JSONObject schemaJson = new JSONObject(new JSONTokener(new FileInputStream(new File(SCHEMA))));
    jsonValidator = SchemaLoader.load(schemaJson);
  }

  @Test
  public void testEqualsContract() {
    EqualsVerifier.forClass(User.class).verify();
  }

  @Test
  public void testToFromJson() throws IOException {
    String json = user.toJson();
    logger.info("JSON: " + json);

    jsonValidator.validate(new JSONObject(new JSONTokener(json)));

    User fromJson = User.fromJson(json);
    assertEquals(user, fromJson);
  }

  @Test
  public void testToFromXml() throws IOException {
    String xml = user.toXml();
    logger.info("XML: " + xml);

    Source source = new StreamSource(new StringReader(xml));
    try {
      xmlValidator.validate(source);
    } catch (SAXException e) {
      fail("XML validation failed: " + e.getMessage());
    }

    User fromXml = User.fromXml(xml);
    assertEquals(user, fromXml);
  }

  @Test
  public void testJsonToXml() throws IOException {
    String json = user.toJson();
    User fromJson = User.fromJson(json);
    String xml = fromJson.toXml();
    User fromXml = User.fromXml(xml);

    logger.info(json);
    logger.info(xml);

    assertEquals(user, fromJson);
    assertEquals(user, fromXml);
  }

  @Test(expected = SAXException.class)
  public void testEmpty() throws Exception {
    String xml = User.builder().build().toXml();
    logger.info("XML: " + xml);

    Source source = new StreamSource(new StringReader(xml));
    xmlValidator.validate(source);
  }
}
