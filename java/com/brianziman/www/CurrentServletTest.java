package com.brianziman.www;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.UUID;
import org.junit.Test;

/**
 * Unit test for CurrentServlet
 */
public class CurrentServletTest {
  @Test
  public void visit_toJson() {
    CurrentServlet.Visit visit =
        new CurrentServlet.Visit("user id", "a name", "visit id");
    String json = visit.toJson();
    String expected =
        "{ userId: \"user id\", name: \"a name\", visitId: \"visit id\" }";
    assertThat(json).isEqualTo(expected);
  }

  @Test
  public void visit_getVisitIdJson() {
    CurrentServlet.Visit visit =
        new CurrentServlet.Visit("user id", "a name", "visit id");
    String json = visit.getVisitIdJson();
    String expected = "{ visitId: \"visit id\" }";
    assertThat(json).isEqualTo(expected);
  }

  @Test
  public void visit_parse() {
    CurrentServlet.Visit visit =
        CurrentServlet.Visit.parse(
            "{ userId: \"user 1\", name: \"user's name\" }");
    assertThat(visit).isNotNull();
    assertThat(visit.getUserId()).isEqualTo("user 1");
    assertThat(visit.getName()).isEqualTo("user's name");
    // NOTE: UUID.fromString() throws if it's not a valid UUID.
    assertThat(UUID.fromString(visit.getVisitId())).isNotNull();
  }

  @Test
  public void visit_parse_empty() {
    assertThat(CurrentServlet.Visit.parse("")).isNull();
  }

  @Test
  public void visit_parse_missing() {
    assertThat(CurrentServlet.Visit.parse("{ userId: \"foo\" }")).isNull();
    assertThat(CurrentServlet.Visit.parse("{ name: \"foo\" }")).isNull();
  }

  @Test
  public void toJson() {
    ImmutableSet.Builder<CurrentServlet.Visit> builder =
        ImmutableSet.builder();
    builder.add(new CurrentServlet.Visit("user 1", "name 1", "visit 1"));
    builder.add(new CurrentServlet.Visit("user 2", "name 2", "visit 2"));
    builder.add(new CurrentServlet.Visit("user 3", "name 3", "visit 3"));

    String json = CurrentServlet.toJson(builder.build());
    String expected =
        "[{ userId: \"user 1\", name: \"name 1\", visitId: \"visit 1\" }, "
        + "{ userId: \"user 2\", name: \"name 2\", visitId: \"visit 2\" }, "
        + "{ userId: \"user 3\", name: \"name 3\", visitId: \"visit 3\" }]";
    assertThat(json).isEqualTo(expected);
  }

  @Test
  public void normalize() {
    assertThat(CurrentServlet.normalize("Abc!@#034-5 FOO."))
        .isEqualTo("abc0345foo");
  }

  @Test
  public void editDistance() {
    assertThat(CurrentServlet.editDistance("ab", ""))
        .isEqualTo(2);
    assertThat(CurrentServlet.editDistance("abc", "def"))
        .isEqualTo(3);
    assertThat(CurrentServlet.editDistance("abcdef", "def"))
        .isEqualTo(3);
    assertThat(CurrentServlet.editDistance("carnival", "cavernous"))
        .isEqualTo(6);
    assertThat(CurrentServlet.editDistance("close", "closer"))
        .isEqualTo(1);
  }

  @Test
  public void editRatio() {
    assertThat(CurrentServlet.editRatio("abc", "def"))
        .isWithin(0.001f).of(1.0f);
    assertThat(CurrentServlet.editRatio("abcdef", "def"))
        .isWithin(0.001f).of(0.5f);
    assertThat(CurrentServlet.editRatio("carnival", "cavernous"))
        .isWithin(0.001f).of(6/9.0f);
    assertThat(CurrentServlet.editRatio("close", "closer"))
        .isWithin(0.001f).of(1/6.0f);
  }

  @Test
  public void getBestMatch() {
    ImmutableSet<String> names =
      ImmutableSet.of("First Name", "second.name", "3rd name!");

    assertThat(CurrentServlet.getBestMatch(names, "fiRST").get())
        .isEqualTo("First Name");

    assertThat(CurrentServlet.getBestMatch(names, "NAME").get())
        .isEqualTo("3rd name!");

    assertThat(CurrentServlet.getBestMatch(names, "am").isPresent())
        .isFalse();
  }

}
