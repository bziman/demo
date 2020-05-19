package com.brianziman.www;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.brianziman.www.VisitException.Status;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

@Singleton
public class CurrentServlet extends HttpServlet {

  @Retention(RUNTIME)
  @Target({FIELD, PARAMETER, METHOD})
  @Qualifier
  public @interface JdbcUrl {}

  private static final String NAME_QUERY =
      "SELECT name, MAX(visitTime) as vt FROM CurrentVisit"
      + " WHERE userId = ?"
      + " GROUP BY name"
      + " ORDER BY vt DESC"
      + " LIMIT 5";

  private static final String VISIT_QUERY =
      "SELECT userId, name, visitId"
      + " FROM CurrentVisit"
      + " WHERE userId = ?"
      + " AND name = ?"
      + " ORDER BY visitTime DESC";

  private static final String VISIT_BY_ID =
      "SELECT userId, name, visitId"
      + " FROM CurrentVisit"
      + " WHERE visitId = ?";

  private static final String VISIT_UPDATE =
      "INSERT INTO CurrentVisit"
      + " (userId, name, visitId, visitTime)"
      + " VALUE (?, ?, ?, ?)";

  private static final Pattern USER_ID_PATTERN =
      Pattern.compile(".*\\buserId:\\s*\"([^\"]*)\".*", Pattern.DOTALL);
  private static final Pattern NAME_PATTERN =
      Pattern.compile(".*\\bname:\\s*\"([^\"]*)\".*", Pattern.DOTALL);

  private static final String QUERY_FAILED = "Query failed";
  private static final String UPDATE_FAILED = "Update failed";
  private static final String INVALID_REQUEST = "Invalid request";

  /** Encapsulates a visit, and provides conversion to JSON. */
  @VisibleForTesting
  static class Visit {
    private final String userId;
    private final String name;
    private final String visitId;
    public Visit(String userId, String name, String visitId) {
      this.userId = userId;
      this.name = name;
      this.visitId = visitId;
    }
    public String getUserId() {
      return userId;
    }
    public String getName() {
      return name;
    }
    public String getVisitId() {
      return visitId;
    }
    public String toJson() {
      return String.format("{ userId: \"%s\", name: \"%s\", visitId: \"%s\" }",
          userId, name, visitId);
    }
    public String getVisitIdJson() {
      return String.format("{ visitId: \"%s\" }", visitId);
    }

    /** Parses the given JSON string into a Visit object. */
    @VisibleForTesting
    static Visit parse(String json) {
      Matcher userIdMatcher = USER_ID_PATTERN.matcher(json);
      Matcher nameMatcher = NAME_PATTERN.matcher(json);

      if (!userIdMatcher.matches() || !nameMatcher.matches()) {
        return null;
      }

      String userId = userIdMatcher.group(1);
      String name = nameMatcher.group(1);
      String visitId = UUID.randomUUID().toString();
      return new Visit(userId, name, visitId);
    }
  }

  private final String jdbcUrl;

  @Inject
  CurrentServlet(@JdbcUrl String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
  }

  /**
   * Returns a JDBC connection from the driver pool.
   */
  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl);
  }

  private void insertVisit(Visit visit) {
    try (
        Connection c = getConnection();
        PreparedStatement ps = c.prepareStatement(VISIT_UPDATE)) {
      ps.setString(1, visit.getUserId());
      ps.setString(2, visit.getName());
      ps.setString(3, visit.getVisitId());
      ps.setLong(4, System.currentTimeMillis());
      int result = ps.executeUpdate();
      if (result != 1) {
        throw new VisitException(Status.MY_BAD, UPDATE_FAILED);
      }
    } catch (SQLException e) {
      e.printStackTrace();
      throw new VisitException(Status.MY_BAD, UPDATE_FAILED);
    }
  }

  /**
   * Queries the database for the last (up to) five places visited
   * by the given user and returns the names of those places,
   * if any.
   */
  private ImmutableSet<String> getNames(String userId) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    try (
        Connection c = getConnection();
        PreparedStatement ps = c.prepareStatement(NAME_QUERY)) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          builder.add(rs.getString("name"));
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
      throw new VisitException(Status.MY_BAD, QUERY_FAILED);
    }
    return builder.build();
  }

  /**
   * Return all visits where the given user to the exactly named place.
   */
  private ImmutableSet<Visit> getVisits(String userId, String name) {
    ImmutableSet.Builder<Visit> builder = ImmutableSet.builder();
    try (
        Connection c = getConnection();
        PreparedStatement ps = c.prepareStatement(VISIT_QUERY)) {
      ps.setString(1, userId);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Visit visit =
              new Visit(
                  rs.getString("userId"),
                  rs.getString("name"),
                  rs.getString("visitId"));
          builder.add(visit);
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
      throw new VisitException(Status.MY_BAD, QUERY_FAILED);
    }
    return builder.build();
  }

  /**
   * Return visit by ID. Returns a set for convenience, but the
   * set will contain at most one entry.
   */
  private ImmutableSet<Visit> getVisitById(String visitId) {
    try (
        Connection c = getConnection();
        PreparedStatement ps = c.prepareStatement(VISIT_BY_ID)) {
      ps.setString(1, visitId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Visit visit =
              new Visit(
                  rs.getString("userId"),
                  rs.getString("name"),
                  rs.getString("visitId"));
          return ImmutableSet.of(visit);
        }
      }
    } catch (SQLException e) {
      throw new VisitException(Status.MY_BAD, QUERY_FAILED);
    }
    return ImmutableSet.of();
  }

  /**
   * Builds a JSON string for the given set of visits.
   */
  @VisibleForTesting
  static String toJson(Collection<Visit> visits) {
    return visits.stream()
        .map(Visit::toJson)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /**
   * Remove non-letters, convert to lowercase.
   */
  @VisibleForTesting
  static String normalize(String name) {
    return name.toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  /** Calculate Levenshtein Distance of two strings */
  @VisibleForTesting
  static int editDistance(String a, String b) {
    return editDistance(a, a.length(), b, b.length(), new HashMap<>());
  }

  /** Key used for memoizing editDistance calls */
  private static class Key {
    private final int a;
    private final int b;
    Key(int a, int b) {
      this.a = a;
      this.b = b;
    }
    @Override
    public int hashCode() {
      return 37 * a + b;
    }
    @Override
    public boolean equals(Object o) {
      if (o == null || !(o instanceof Key)) {
        return false;
      }
      Key k = (Key) o;
      return a == k.a && b == k.b;
    }
  }

  private static int editDistance(
      String a, int ai, String b, int bj,
      Map<Key, Integer> cache) {
    if (ai == 0) {
      return bj;
    }
    if (bj == 0) {
      return ai;
    }
    Key key = new Key(ai, bj);
    Integer result = cache.get(key);
    if (result != null) {
      return result;
    }

    int x = editDistance(a, ai - 1, b, bj, cache) + 1;
    int y = editDistance(a, ai, b, bj - 1, cache) + 1;
    int z = editDistance(a, ai - 1, b, bj - 1, cache)
              + (a.charAt(ai - 1) == b.charAt(bj - 1) ? 0 : 1);
    result = Arrays.stream(new int[] { x, y, z }).min().getAsInt();
    cache.put(key, result);
    return result;
  }

  /**
   * Return the ratio of the edit distance to the maximum
   * of the length of thw two strings.
   */
  @VisibleForTesting
  static float editRatio(String a, String b) {
    return editDistance(a, b) / (float) Math.max(a.length(), b.length());
  }

  /**
   * Given a list of names and a query, return
   * the name that best matches the query, or
   * empty, if none matches at least 50%.
   */
  @VisibleForTesting
  static Optional<String> getBestMatch(
      ImmutableSet<String> names, String query) {
    query = normalize(query);
    // Map of distance to name
    Map<Float, String> distances = new TreeMap<>();
    for (String name : names) {
      String n = normalize(name);
      float d = editRatio(query, n);
      if (d < 0.5) {
        // If we had to change more than half the chars
        // then it's probably not a good match.
        distances.put(d, name);
      }
    }
    return distances.values().stream().findFirst();
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) 
      throws ServletException, IOException {
    try {
      Reader reader = request.getReader();
      StringBuilder sb = new StringBuilder();
      char[] buffer = new char[1024];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        sb.append(buffer, 0, count);
      }
      String json = sb.toString();
      Visit visit = Visit.parse(json);

      if (visit == null) {
        throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
      }
      insertVisit(visit);

      response.setContentType("application/json");
      PrintWriter out = response.getWriter();
      out.println(visit.getVisitIdJson());
    } catch (VisitException e) {
      final int code;
      if (e.getStatus() == Status.MY_BAD) {
        code = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
      } else {
        code = HttpServletResponse.SC_BAD_REQUEST;
      }
      response.sendError(code, e.getMessage());
    }
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) 
      throws ServletException, IOException {
    // params are visitId or userId/searchString
    try {
      String query = request.getQueryString();
      if (query == null || query.isEmpty()) {
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println(
            "<h1>Merchant Visit Api Demo</h1><p>Please send well-formed"
            + " requests that conform with the provided specification.</p>");
        return;
      }
      String[] q = query.split("&");
      ImmutableSet<Visit> visits;
      if (q.length == 1) {
        String[] qq = q[0].split("=", 2);
        if (!"visitId".equals(qq[0])) {
          throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
        }
        if (qq.length < 2) {
          throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
        }
        String visitId = qq[1];
        visits = getVisitById(visitId);
      } else if (q.length == 2) {
        String userId = null;
        String searchString = null;
        for (String pair : q) {
          String[] pp = pair.split("=", 2);
          if (pp.length < 2) {
            throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
          }
          if ("userId".equals(pp[0])) {
            if (userId != null) {
              throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
            }
            userId = pp[1];
          } else if ("searchString".equals(pp[0])) {
            if (searchString != null) {
              throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
            }
            searchString = URLDecoder.decode(pp[1], "UTF8");
          } else {
            throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
          }
        }
        final String user = userId; // required for lambda
        visits =
          getBestMatch(getNames(userId), searchString)
          .map(name -> getVisits(user, name))
          .orElse(ImmutableSet.of());
      } else {
        throw new VisitException(Status.YOUR_BAD, INVALID_REQUEST);
      }
      String json = toJson(visits);
      response.setContentType("application/json");
      PrintWriter out = response.getWriter();
      out.println(json);
    } catch (VisitException e) {
      final int code;
      if (e.getStatus() == Status.MY_BAD) {
        code = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
      } else {
        code = HttpServletResponse.SC_BAD_REQUEST;
      }
      response.sendError(code, e.getMessage());
    }
  }
}

