package deba.demo;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Properties;

import javax.servlet.Servlet;

import org.apache.tomcat.util.scan.StandardJarScanner;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.jsp.JettyJspServlet;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class JettyMain {

	public static void main(String[] args) throws Exception {

		Server server = new Server(8080);
		
		Properties props = System.getProperties(); 
		
		System.err.println("Properties in use ") ;
		
		for ( Object prop : props.keySet()) {
			
			System.err.println(prop.toString() + " - "+ props.get(prop));
			
		}
		
		System.setProperty("java.io.tmpdir" , "/Users/_dga/tmp");

		// Enable annotation scanning for webapps

		Configuration.ClassList classlist = Configuration.ClassList.setServerDefault(server);

		classlist.addBefore(JettyWebXmlConfiguration.class.getCanonicalName(),
				AnnotationConfiguration.class.getCanonicalName(), WebInfConfiguration.class.getCanonicalName(),
				WebXmlConfiguration.class.getCanonicalName(), FragmentConfiguration.class.getCanonicalName(),
				EnvConfiguration.class.getCanonicalName(), PlusConfiguration.class.getCanonicalName(),
				MetaInfConfiguration.class.getCanonicalName());

		// Find webapp - exploded directory or war or shaded jar file
		URI webResourceBase = findWebResourceBase(server.getClass().getClassLoader());
		System.err.println("Using BaseResource: " + webResourceBase);

		WebAppContext rootContext = new WebAppContext();
		rootContext.setBaseResource(Resource.newResource(webResourceBase));
		rootContext.setContextPath("/");
		rootContext.setWelcomeFiles(new String[] { "index.html", "welcome.html" });

		// Make webapp use embedded classloader
		rootContext.setParentLoaderPriority(true);

		rootContext.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
				".*/target/WEB-INF/classes/|.*\\.jar");

		// Handle static resources, e.g. html files.
		rootContext.addServlet((Class<? extends Servlet>) DefaultServlet.class, "/");

		// Configure JSP support.
		enableEmbeddedJspSupport(rootContext);
		// Start Server
		server.setHandler(rootContext);
		server.start();
		server.join();

	}

	static URI findWebResourceBase(ClassLoader classLoader) {

		String webResourceRef = "WEB-INF/web.xml";

		try {
			// Look for resource in classpath (best choice when working with archive jar/war
			// file)
			URL webXml = classLoader.getResource('/' + webResourceRef);
			if (webXml != null) {
				URI uri = webXml.toURI().resolve("..").normalize();
				System.err.printf("WebResourceBase (Using ClassLoader reference) %s%n", uri);
				return uri;
			}
		} catch (URISyntaxException e) {
			throw new RuntimeException("Bad ClassPath reference for: " + webResourceRef, e);
		}

		// Look for resource in common file system paths
		try {
			Path pwd = new File(System.getProperty("user.dir")).toPath().toRealPath();
			FileSystem fs = pwd.getFileSystem();

			// Try the generated maven path first
			PathMatcher matcher = fs.getPathMatcher("glob:**/embedded-servlet-*");
			try (DirectoryStream<Path> dir = Files.newDirectoryStream(pwd.resolve("target"))) {
				for (Path path : dir) {
					if (Files.isDirectory(path) && matcher.matches(path)) {
						// Found a potential directory
						Path possible = path.resolve(webResourceRef);
						// Does it have what we need?
						if (Files.exists(possible)) {
							URI uri = path.toUri();
							System.err.printf("WebResourceBase (Using discovered /target/ Path) %s%n", uri);
							return uri;
						}
					}
				}
			}
			// Try the source path next
			Path srcWebapp = pwd.resolve("src/main/webapp/" + webResourceRef);
			if (Files.exists(srcWebapp)) {
				URI uri = srcWebapp.getParent().toUri();
				System.err.printf("WebResourceBase (Using /src/main/webapp/ Path) %s%n", uri);
				return uri;
			}
		} catch (Throwable t) {
			throw new RuntimeException("Unable to find web resource in file system: " + webResourceRef, t);
		}

		throw new RuntimeException("Unable to find web resource ref: " + webResourceRef);

	}

	/**
	 * Setup JSP Support for ServletContextHandlers.
	 * <p>
	 * NOTE: This is not required or appropriate if using a WebAppContext.
	 * </p>
	 *
	 * @param servletContextHandler the ServletContextHandler to configure
	 * @throws IOException if unable to configure
	 */
	private static void enableEmbeddedJspSupport(ServletContextHandler servletContextHandler) throws IOException {
		// Establish Scratch directory for the servlet context (used by JSP compilation)
		File tempDir = new File(System.getProperty("java.io.tmpdir"));
		File scratchDir = new File(tempDir.toString(), "embedded-jetty-jsp");

		if (!scratchDir.exists()) {
			if (!scratchDir.mkdirs()) {
				throw new IOException("Unable to create scratch directory: " + scratchDir);
			}
		}
		servletContextHandler.setAttribute("javax.servlet.context.tempdir", scratchDir);

		// Set Classloader of Context to be sane (needed for JSTL)
		// JSP requires a non-System classloader, this simply wraps the
		// embedded System classloader in a way that makes it suitable
		// for JSP to use
		ClassLoader jspClassLoader = new URLClassLoader(new URL[0], JettyMain.class.getClassLoader());
		servletContextHandler.setClassLoader(jspClassLoader);

		// Manually call JettyJasperInitializer on context startup
		servletContextHandler.addBean(new JspStarter(servletContextHandler));

		// Create / Register JSP Servlet (must be named "jsp" per spec)
		ServletHolder holderJsp = new ServletHolder("jsp", JettyJspServlet.class);
		holderJsp.setInitOrder(0);
		holderJsp.setInitParameter("logVerbosityLevel", "DEBUG");
		holderJsp.setInitParameter("fork", "false");
		holderJsp.setInitParameter("xpoweredBy", "false");
		holderJsp.setInitParameter("compilerTargetVM", "1.8");
		holderJsp.setInitParameter("compilerSourceVM", "1.8");
		holderJsp.setInitParameter("keepgenerated", "true");
		servletContextHandler.addServlet(holderJsp, "*.jsp");
	}

	/**
	 * JspStarter for embedded ServletContextHandlers
	 *
	 * This is added as a bean that is a jetty LifeCycle on the
	 * ServletContextHandler. This bean's doStart method will be called as the
	 * ServletContextHandler starts, and will call the ServletContainerInitializer
	 * for the jsp engine.
	 *
	 */
	public static class JspStarter extends AbstractLifeCycle
			implements ServletContextHandler.ServletContainerInitializerCaller {

		JettyJasperInitializer sci;
		ServletContextHandler context;

		public JspStarter(ServletContextHandler context) {
			this.sci = new JettyJasperInitializer();
			this.context = context;
			this.context.setAttribute("org.apache.tomcat.JarScanner", new StandardJarScanner());
		}

		@Override
		protected void doStart() throws Exception {
			ClassLoader old = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(context.getClassLoader());
			try {
				sci.onStartup(null, context.getServletContext());
				super.doStart();
			} finally {
				Thread.currentThread().setContextClassLoader(old);
			}
		}
	}

}
