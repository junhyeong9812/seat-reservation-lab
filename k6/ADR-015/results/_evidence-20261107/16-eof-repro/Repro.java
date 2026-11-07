import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
public class Repro {
  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    Tomcat t = new Tomcat();
    t.setBaseDir(a[2]);
    Connector c = new Connector("org.apache.coyote.http11.Http11NioProtocol");
    c.setPort(port);
    c.setProperty("address", "127.0.0.1");
    c.setProperty("maxConnections", "20000");
    if (!a[1].equals("default")) ((AbstractProtocol<?>) c.getProtocolHandler()).setKeepAliveTimeout(Integer.parseInt(a[1]));
    t.setConnector(c);
    var ctx = t.addContext("", null);
    AtomicLong posts = new AtomicLong();
    Tomcat.addServlet(ctx, "s", new HttpServlet() {
      protected void service(HttpServletRequest q, HttpServletResponse r) throws IOException {
        if (q.getMethod().equals("POST")) { q.getInputStream().readAllBytes(); posts.incrementAndGet(); }
        r.setContentType("text/plain"); r.getWriter().write("ok");
      }
    });
    ctx.addServletMappingDecoded("/*", "s");
    t.start();
    var p = (AbstractProtocol<?>) c.getProtocolHandler();
    System.out.println("STARTED connectionTimeout=" + p.getConnectionTimeout() + " keepAliveTimeout=" + p.getKeepAliveTimeout() + " maxKeepAliveRequests=" + ((org.apache.coyote.http11.AbstractHttp11Protocol<?>)p).getMaxKeepAliveRequests());
    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("POSTS_PROCESSED=" + posts.get())));
    t.getServer().await();
  }
}
