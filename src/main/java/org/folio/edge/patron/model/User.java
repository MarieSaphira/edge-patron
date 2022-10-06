package org.folio.edge.patron.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import org.folio.edge.core.utils.Mappers;

import java.io.IOException;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "user")
@JsonDeserialize(builder = org.folio.edge.patron.model.User.Builder.class)
public final class User {

  public final boolean active;
  public final String type;
  public final String patronGroup;
  public final String lastName;
  public final String firstName;
  public final String email;

  private User(boolean active, String type, String patronGroup, String lastName, String firstName, String email) {
    this.active = active;
    this.type = type;
    this.patronGroup = patronGroup;
    this.lastName = lastName;
    this.firstName = firstName;
    this.email = email;
  }

  public static org.folio.edge.patron.model.User.Builder builder() {
    return new org.folio.edge.patron.model.User.Builder();
  }

  public static org.folio.edge.patron.model.User fromJson(String json) throws IOException {
    return Mappers.jsonMapper.readValue(json, org.folio.edge.patron.model.User.class);
  }

  public static org.folio.edge.patron.model.User fromXml(String xml) throws IOException {
    return Mappers.xmlMapper.readValue(xml, org.folio.edge.patron.model.User.class);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (active ? 1 : 0);
    result = prime * result + ((type == null) ? 0 : type.hashCode());
    result = prime * result + ((patronGroup == null) ? 0 : patronGroup.hashCode());
    result = prime * result + ((lastName == null) ? 0 : lastName.hashCode());
    result = prime * result + ((firstName == null) ? 0 : firstName.hashCode());
    result = prime * result + ((email == null) ? 0 : email.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    org.folio.edge.patron.model.User user = (org.folio.edge.patron.model.User) o;
    return active == user.active &&
      Objects.equals(type, user.type) &&
      Objects.equals(patronGroup, user.patronGroup) &&
      Objects.equals(lastName, user.lastName) &&
      Objects.equals(firstName, user.firstName) &&
      Objects.equals(email, user.email);
  }

  public String toXml() throws JsonProcessingException {
    return Mappers.XML_PROLOG + Mappers.xmlMapper.writeValueAsString(this);
  }

  public String toJson() throws JsonProcessingException {
    return Mappers.jsonMapper.writeValueAsString(this);
  }

  public static class Builder {

    @JsonProperty("active")
    public boolean active;
    @JsonProperty("type")
    public String type;
    @JsonProperty("patronGroup")
    public String patronGroup;
    @JsonProperty("lastName")
    public String lastName;
    @JsonProperty("firstName")
    public String firstName;
    @JsonProperty("email")
    public String email;

    public org.folio.edge.patron.model.User.Builder active(boolean active) {
      this.active = active;
      return this;
    }

    public org.folio.edge.patron.model.User.Builder type(String type) {
      this.type = type;
      return this;
    }

    public org.folio.edge.patron.model.User.Builder patronGroup(String patronGroup) {
      this.patronGroup = patronGroup;
      return this;
    }

    public org.folio.edge.patron.model.User.Builder lastName(String lastName) {
      this.lastName = lastName;
      return this;
    }

    public org.folio.edge.patron.model.User.Builder firstName(String firstName) {
      this.firstName = firstName;
      return this;
    }

    public org.folio.edge.patron.model.User.Builder email(String email) {
      this.email = email;
      return this;
    }

    public org.folio.edge.patron.model.User build() {
      return new org.folio.edge.patron.model.User(active, type, patronGroup, lastName, firstName, email);
    }
  }
}
