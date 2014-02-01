package com.zenika.nc.angular.mybottles;

import com.google.common.base.Strings;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restx.server.JettyWebServer;
import restx.server.WebServer;
import restx.server.WebServerSupplier;
import restx.server.WebServers;

import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkNotNull;
import static restx.common.MoreFiles.checkFileExists;
import static restx.common.MoreIO.checkCanOpenSocket;

/**
 * Created by Yoann on 01/02/14.
 */
public class NonBlockingJettyWebServer implements WebServer {
    private static final AtomicLong SERVER_ID = new AtomicLong();

    private static final Logger logger = LoggerFactory.getLogger(JettyWebServer.class);

    private Server server;
    private int port;
    private String bindInterface;
    private String appBase;
    private String webInfLocation;
    private String serverId;


    public NonBlockingJettyWebServer(String appBase, int aPort) {
        this(null, appBase, aPort, null);
    }

    public NonBlockingJettyWebServer(String webInfLocation, String appBase, int port, String bindInterface) {
        checkFileExists(checkNotNull(appBase));

        if (webInfLocation != null) {
            checkFileExists(webInfLocation);
        }

        this.port = port;
        this.bindInterface = bindInterface;
        this.appBase = appBase;
        this.webInfLocation = webInfLocation;
        this.serverId = "Jetty#" + SERVER_ID.incrementAndGet();
    }

    @Override
    public String getServerId() {
        return serverId;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String baseUrl() {
        return WebServers.baseUri("127.0.0.1", port);
    }

    @Override
    public synchronized void start() throws Exception {
        checkCanOpenSocket(port);

        server = new Server();
        WebServers.register(this);

        server.setThreadPool(createThreadPool());
        server.addConnector(createConnector());
        server.setHandler(createHandlers(createContext()));
        server.setStopAtShutdown(true);

        server.start();
    }

    @Override
    public void startAndAwait() throws Exception {
        start();
        await();
    }

    @Override
    public void await() throws InterruptedException {
        server.join();
    }

    @Override
    public synchronized void stop() throws Exception {
        server.stop();
        server = null;
        WebServers.unregister(serverId);
    }

    @Override
    public synchronized boolean isStarted() {
        return server != null;
    }

    private ThreadPool createThreadPool() {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMinThreads(1);
        threadPool.setMaxThreads(10);
        return threadPool;
    }

    private SelectChannelConnector createConnector() {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(port);
        connector.setHost(bindInterface);
        connector.setUseDirectBuffers(false);
        return connector;
    }

    private HandlerCollection createHandlers(WebAppContext webAppContext) {

        HandlerList contexts = new HandlerList();
        contexts.setHandlers(new Handler[]{webAppContext});

        HandlerCollection result = new HandlerCollection();
        result.setHandlers(new Handler[]{contexts});

        return result;
    }

    private WebAppContext createContext() {
        final WebAppContext ctx = new WebAppContext();
        ctx.setContextPath("/");
        ctx.setWar(appBase);

        if (!Strings.isNullOrEmpty(webInfLocation)) {
            ctx.setDescriptor(webInfLocation);
        }
        // configure security to avoid err println "Null identity service, trying login service:"
        // but I've found no way to get rid of LoginService=xxx log on system err :(
        HashLoginService loginService = new HashLoginService();
        loginService.setIdentityService(new DefaultIdentityService());
        ctx.getSecurityHandler().setLoginService(loginService);
        ctx.getSecurityHandler().setIdentityService(loginService.getIdentityService());

        ctx.addLifeCycleListener(new AbstractLifeCycle.AbstractLifeCycleListener() {
            @Override
            public void lifeCycleStarting(LifeCycle event) {
                ctx.getServletContext().setInitParameter("restx.baseServerUri", baseUrl());
                ctx.getServletContext().setInitParameter("restx.serverId", getServerId());
            }
        });

        return ctx;
    }

    public static WebServerSupplier jettyWebServerSupplier(final String webInfLocation, final String appBase) {
        return new WebServerSupplier() {
            @Override
            public WebServer newWebServer(int port) {
                return new JettyWebServer(webInfLocation, appBase, port, "0.0.0.0");
            }
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: jetty-run <appbase> [<port>]");
            System.exit(1);
        }

        String appBase = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8086;
        new JettyWebServer(appBase + "WEB-INF/web.xml", appBase, port, "0.0.0.0").startAndAwait();
    }
}
