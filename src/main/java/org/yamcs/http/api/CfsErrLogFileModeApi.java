package org.yamcs.http.api;

import com.windhoverlabs.yamcs.cfs.err_log.api.AbstractCfsErrLogFileModeApi;
import com.windhoverlabs.yamcs.cfs.err_log.api.CfsErrLogFileModeConfig;
import com.windhoverlabs.yamcs.cfs.err_log.api.SetCFSErrLogFileModeRequest;
import org.yamcs.YamcsServer;
import org.yamcs.api.Observer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.http.Context;
import org.yamcs.tctm.Link;

public class CfsErrLogFileModeApi extends AbstractCfsErrLogFileModeApi<Context> {

  private EventProducer eventProducer =
      EventProducerFactory.getEventProducer(null, this.getClass().getSimpleName(), 10000);

  @Override
  public void setMode(
      Context c, SetCFSErrLogFileModeRequest request, Observer<CfsErrLogFileModeConfig> observer) {
    Link l =
        YamcsServer.getServer()
            .getInstance(request.getInstance())
            .getLinkManager()
            .getLink(request.getLinkName());

    if (l == null) {
      eventProducer.sendInfo("Link:" + request.getLinkName() + " does not exist");
      observer.complete();
      return;
    }
    ((com.windhoverlabs.yamcs.cfs.err_log.CfsErrLogPlugin) l).setMode(request.getMode());
    observer.complete(CfsErrLogFileModeConfig.newBuilder().build());
  }
}
