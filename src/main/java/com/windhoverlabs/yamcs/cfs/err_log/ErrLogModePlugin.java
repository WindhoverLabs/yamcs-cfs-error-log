package com.windhoverlabs.yamcs.cfs.err_log;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.http.HttpServer;
import org.yamcs.logging.Log;

public class ErrLogModePlugin implements Plugin {
  private static final Log log = new Log(ErrLogModePlugin.class);

  @Override
  public void onLoad(YConfiguration config) throws PluginException {
    YamcsServer yamcs = YamcsServer.getServer();

    EventProducer eventProducer =
        EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(), 10000);

    eventProducer.sendInfo("EvsCSVModePlugin loaded");
    List<HttpServer> httpServers = yamcs.getGlobalServices(HttpServer.class);
    if (httpServers.isEmpty()) {
      log.warn(
          "Yamcs does not appear to be running an HTTP Server. The EvsCSVModePlugin was not initialized.");
      return;
    }

    HttpServer httpServer = httpServers.get(0);
    // Add API to server
    try (InputStream in = getClass().getResourceAsStream("/yamcs-cfs-error-log.protobin")) {
      httpServer.getProtobufRegistry().importDefinitions(in);
    } catch (IOException e) {
      throw new PluginException(e);
    }

    httpServer.addApi(new org.yamcs.http.api.CfsErrLogFileModeApi());
  }

  /**
   * The workspace directory is the directory that holds all of our yamcs configuration.
   *
   * @return
   */
  public String getWorkspaceDir() {
    return System.getProperty("user.dir");
  }
}
