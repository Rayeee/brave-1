package brave.mysql;

import brave.Span;
import brave.propagation.ThreadLocalSpan;
import com.mysql.jdbc.Connection;
import com.mysql.jdbc.PreparedStatement;
import com.mysql.jdbc.ResultSetInternalMethods;
import com.mysql.jdbc.Statement;
import com.mysql.jdbc.StatementInterceptorV2;
import java.net.URI;
import java.sql.SQLException;
import java.util.Properties;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.TraceKeys;

/**
 * A MySQL statement interceptor that will report to Zipkin how long each statement takes.
 *
 * <p>To use it, append <code>?statementInterceptors=brave.mysql.TracingStatementInterceptor</code>
 * to the end of the connection url.
 */
public class TracingStatementInterceptor implements StatementInterceptorV2 {

  /**
   * Uses {@link ThreadLocalSpan} as there's no attribute namespace shared between callbacks, but
   * all callbacks happen on the same thread.
   *
   * <p>Uses {@link ThreadLocalSpan#CURRENT_TRACER} and this interceptor initializes before tracing.
   */
  @Override
  public ResultSetInternalMethods preProcess(String sql, Statement interceptedStatement,
      Connection connection) throws SQLException {
    // Gets the next span (and places it in scope) so code between here and postProcess can read it
    Span span = ThreadLocalSpan.CURRENT_TRACER.next();
    if (span == null || span.isNoop()) return null;

    // When running a prepared statement, sql will be null and we must fetch the sql from the statement itself
    if (interceptedStatement instanceof PreparedStatement) {
      sql = ((PreparedStatement) interceptedStatement).getPreparedSql();
    }
    int spaceIndex = sql.indexOf(' '); // Allow span names of single-word statements like COMMIT
    span.kind(Span.Kind.CLIENT).name(spaceIndex == -1 ? sql : sql.substring(0, spaceIndex));
    span.tag(TraceKeys.SQL_QUERY, sql);
    parseServerAddress(connection, span);
    span.start();
    return null;
  }

  @Override
  public ResultSetInternalMethods postProcess(String sql, Statement interceptedStatement,
      ResultSetInternalMethods originalResultSet, Connection connection, int warningCount,
      boolean noIndexUsed, boolean noGoodIndexUsed, SQLException statementException)
      throws SQLException {
    Span span = ThreadLocalSpan.CURRENT_TRACER.remove();
    if (span == null || span.isNoop()) return null;

    if (statementException != null) {
      span.tag(Constants.ERROR, Integer.toString(statementException.getErrorCode()));
    }
    span.finish();

    return null;
  }

  /**
   * MySQL exposes the host connecting to, but not the port. This attempts to get the port from the
   * JDBC URL. Ex. 5555 from {@code jdbc:mysql://localhost:5555/database}, or 3306 if absent.
   */
  static void parseServerAddress(Connection connection, Span span) {
    try {
      URI url = URI.create(connection.getMetaData().getURL().substring(5)); // strip "jdbc:"
      int port = url.getPort() == -1 ? 3306 : url.getPort();
      String remoteServiceName = connection.getProperties().getProperty("zipkinServiceName");
      if (remoteServiceName == null || "".equals(remoteServiceName)) {
        String databaseName = connection.getCatalog();
        if (databaseName != null && !databaseName.isEmpty()) {
          remoteServiceName = "mysql-" + databaseName;
        } else {
          remoteServiceName = "mysql";
        }
      }
      Endpoint.Builder builder = Endpoint.builder().serviceName(remoteServiceName).port(port);
      if (!builder.parseIp(connection.getHost())) return;
      span.remoteEndpoint(builder.build());
    } catch (Exception e) {
      // remote address is optional
    }
  }

  @Override public boolean executeTopLevelOnly() {
    return true; // True means that we don't get notified about queries that other interceptors issue
  }

  @Override public void init(Connection conn, Properties props) throws SQLException {
    // Don't care
  }

  @Override public void destroy() {
    // Don't care
  }
}
