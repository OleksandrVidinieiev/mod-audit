package org.folio.rest.impl;

import static io.vertx.core.Future.succeededFuture;
import static java.util.concurrent.CompletableFuture.allOf;
import static org.folio.HttpStatus.HTTP_INTERNAL_SERVER_ERROR;
import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.rest.impl.CirculationLogsService.LOGS_TABLE_NAME;
import static org.folio.util.ErrorUtils.buildError;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.io.IOUtils;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.LogRecord;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.util.OkapiConnectionParams;
import org.folio.util.pubsub.PubSubClientUtils;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ModTenantService extends TenantAPI {
  private static final Logger log = LoggerFactory.getLogger(ModTenantService.class);
  private static final String PARAMETER_LOAD_SAMPLE = "loadSample";
  private static final String SAMPLES_PATH = "samples";

  private List<String> samples = Arrays.asList("fee_fine.json", "item_block.json", "loan.json", "manual_block.json",
    "notice.json", "patron_block.json", "request.json");

  @Override
  public void postTenant(TenantAttributes tenantAttributes, Map<String, String> headers,
    Handler<AsyncResult<Response>> handlers, Context context) {
    super.postTenant(tenantAttributes, headers, res -> {
      if (res.failed() || (res.succeeded() && (res.result().getStatus() < 200 || res.result().getStatus() > 299))) {
        handlers.handle(res);
        return;
      }
      registerModuleToPubSub(headers, context.owner())
        .thenCompose(vVoid -> loadSampleData(tenantAttributes, headers, context))
        .thenAccept(vVoid -> handlers.handle(res))
        .exceptionally(throwable -> {
          handlers.handle(succeededFuture(PostTenantResponse
            .respond500WithTextPlain(buildError(HTTP_INTERNAL_SERVER_ERROR.toInt(), throwable.getLocalizedMessage()))));
          return null;
        });
    }, context);
  }

  private CompletableFuture<Void> loadSampleData(TenantAttributes tenantAttributes, Map<String, String> headers,
    Context context) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (isLoadSample(tenantAttributes)) {
      log.info("Loading sample data...");

      String tenantId = TenantTool.calculateTenantId(headers.get(RestVerticle.OKAPI_HEADER_TENANT));

      allOf(samples.stream()
        .map(fileName -> loadSample(LOGS_TABLE_NAME, fileName, context, tenantId))
        .toArray(CompletableFuture[]::new))
        .thenAccept(vVoid -> {
          log.info("Sample data loaded successfully");
          future.complete(null);
        })
        .exceptionally(throwable -> {
          future.completeExceptionally(throwable);
          return null;
        });
    } else {
      future.complete(null);
    }
    return future;
  }

  private CompletableFuture<Void> loadSample(String tableName, String sampleFileName, Context context, String tenantId) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    try {
      PostgresClient.getInstance(context.owner(), tenantId)
        .save(tableName, getSampleAsJson(SAMPLES_PATH + "/" + sampleFileName).mapTo(LogRecord.class), reply -> {
          if (reply.succeeded()) {
            future.complete(null);
          } else {
            future.completeExceptionally(reply.cause());
          }
        });
    } catch (Exception e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  @Override
  public void deleteTenant(Map<String, String> headers, Handler<AsyncResult<Response>> handlers, Context context) {
    super.deleteTenant(headers, res -> {
      Vertx vertx = context.owner();
      String tenantId = TenantTool.tenantId(headers);
      PostgresClient.getInstance(vertx, tenantId)
        .closeClient(event -> handlers.handle(res));
    }, context);
  }

  private boolean isLoadSample(TenantAttributes tenantAttributes) {
    // if a system parameter is passed from command line, ex: loadSample=true
    // that value is considered,Priority of Parameters:
    // Tenant Attributes > command line parameter > default(false)
    boolean loadSample = Boolean.parseBoolean(MODULE_SPECIFIC_ARGS.getOrDefault(PARAMETER_LOAD_SAMPLE,
      "false"));
    List<Parameter> parameters = tenantAttributes.getParameters();
    for (Parameter parameter : parameters) {
      if (PARAMETER_LOAD_SAMPLE.equals(parameter.getKey())) {
        loadSample = Boolean.parseBoolean(parameter.getValue());
      }
    }
    return loadSample;
  }

  private JsonObject getSampleAsJson(String fullPath) throws IOException {
    log.info("Using mock datafile: " + fullPath);
    try (InputStream resourceAsStream = ModTenantService.class.getClassLoader().getResourceAsStream(fullPath)) {
      if (resourceAsStream != null) {
        return new JsonObject(IOUtils.toString(resourceAsStream, StandardCharsets.UTF_8));
      }
    }
    throw new IOException("Error loading sample file");
  }

  private CompletableFuture<Void> registerModuleToPubSub(Map<String, String> headers, Vertx vertx) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    CompletableFuture.supplyAsync(() -> PubSubClientUtils.registerModule(new OkapiConnectionParams(headers, vertx)))
      .thenAccept(registered -> future.complete(null))
      .exceptionally(throwable -> {
        future.completeExceptionally(throwable);
        return null;
      });
    return future;
  }
}
