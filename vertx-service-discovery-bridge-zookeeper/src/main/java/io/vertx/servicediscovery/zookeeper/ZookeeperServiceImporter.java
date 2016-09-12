package io.vertx.servicediscovery.zookeeper;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.spi.ServiceImporter;
import io.vertx.servicediscovery.spi.ServicePublisher;
import io.vertx.servicediscovery.spi.ServiceType;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.zookeeper.KeeperException;

import java.util.*;

/**
 * A service importer retrieving services from Zookeeper.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class ZookeeperServiceImporter implements ServiceImporter, TreeCacheListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperServiceImporter.class);
  private ServicePublisher publisher;
  private CuratorFramework client;
  private ServiceDiscovery<JsonObject> discovery;
  private TreeCache cache;
  private volatile boolean started;

  private Set<RegistrationHolder<ServiceInstance<JsonObject>>> registrations = new ConcurrentHashSet<>();

  @Override
  public void start(Vertx vertx, ServicePublisher publisher, JsonObject configuration, Future<Void> future) {
    this.publisher = publisher;

    String connection = Objects.requireNonNull(configuration.getString("connection"));
    int maxRetries = configuration.getInteger("maxRetries", 3);
    int baseGraceBetweenRetries = configuration.getInteger("baseSleepTimeBetweenRetries", 1000);
    String basePath = configuration.getString("basePath", "/discovery");

    vertx.<Void>executeBlocking(
        f -> {
          try {
            client = CuratorFrameworkFactory.newClient(connection,
                new ExponentialBackoffRetry(baseGraceBetweenRetries, maxRetries));
            client.start();
            client.blockUntilConnected();

            discovery = ServiceDiscoveryBuilder.builder(JsonObject.class)
                .client(client)
                .basePath(basePath)
                .serializer(new JsonObjectSerializer())
                .watchInstances(true)
                .build();

            discovery.start();

            cache = TreeCache.newBuilder(client, basePath).build();
            cache.start();
            cache.getListenable().addListener(this);

            f.complete();
          } catch (Exception e) {
            future.fail(e);
          }
        },
        ar -> {
          if (ar.failed()) {
            future.fail(ar.cause());
          } else {
            Future<Void> f = Future.future();
            f.setHandler(x -> {
              if (x.failed()) {
                future.fail(x.cause());
              } else {
                started = true;
                future.complete(null);
              }
            });
            compute(f);
          }
        }
    );
  }

  private synchronized void compute(Future<Void> done) {
    List<ServiceInstance<JsonObject>> instances = new ArrayList<>();
    try {
      Collection<String> names = discovery.queryForNames();
      for (String name : names) {
        instances.addAll(discovery.queryForInstances(name));
      }
    } catch (KeeperException.NoNodeException e) {
      // no services
      // Continue
    } catch (Exception e) {
      if (done != null) {
        done.fail(e);
      } else {
        LOGGER.error("Unable to retrieve service instances from Zookeeper", e);
        return;
      }
    }
    // Current set
    Set<RegistrationHolder<ServiceInstance<JsonObject>>> registered
        = new HashSet<>(registrations);
    Set<ServiceInstance<JsonObject>> remote = new HashSet<>(instances);

    List<Future> actions = new ArrayList<>();

    RegistrationHolder.filter(registered, instances)
        .stream()
        .map(reg -> {
          Future<Void> future = Future.future();
          publisher.unpublish(reg.record().getRegistration(), v -> {
            registrations.remove(reg);
            if (v.succeeded()) {
              future.complete(null);
            } else {
              future.fail(v.cause());
            }
          });
          return future;
        }).forEach(actions::add);

    RegistrationHolder.filter(remote, registrations)
        .stream()
        .map(instance -> {
          Future<Void> future = Future.future();
          publisher.publish(createRecordForInstance(instance), v -> {
            if (v.succeeded()) {
              registrations.add(new RegistrationHolder<>(v.result(), instance));
              future.complete(null);
            } else {
              future.fail(v.cause());
            }
          });
          return future;
        }).forEach(actions::add);

    if (done != null) {
      CompositeFuture.all(actions).setHandler(ar -> {
        if (ar.succeeded()) {
          done.complete(null);
        } else {
          done.fail(ar.cause());
        }
      });
    }

  }

  static Record createRecordForInstance(ServiceInstance<JsonObject> instance) {
    Record record = new Record();
    record.setName(instance.getName());
    JsonObject payload = instance.getPayload();
    record.setMetadata(payload);
    record.getMetadata().put("zookeeper-service-type", instance.getServiceType().toString());
    record.getMetadata().put("zookeeper-address", instance.getAddress());
    record.getMetadata().put("zookeeper-registration-time",
        instance.getRegistrationTimeUTC());
    record.getMetadata().put("zookeeper-port", instance.getPort());
    record.getMetadata().put("zookeeper-ssl-port", instance.getSslPort());
    record.getMetadata().put("zookeeper-id", instance.getId());

    record.setLocation(new JsonObject());
    if (instance.getUriSpec() != null) {
      String uri = instance.buildUriSpec();
      record.getLocation().put("endpoint", uri);
    } else {
      String uri = "http";
      if (instance.getSslPort() != null) {
        uri += "s://" + instance.getAddress() + ":" + instance.getSslPort();
      } else if (instance.getPort() != null) {
        uri += "s://" + instance.getAddress() + ":" + instance.getPort();
      } else {
        uri += "://" + instance.getAddress();
      }
      record.getLocation().put("endpoint", uri);
    }
    if (instance.getPort() != null) {
      record.getLocation().put("port", instance.getPort());
    }
    if (instance.getSslPort() != null) {
      record.getLocation().put("ssl-port", instance.getSslPort());
    }
    if (instance.getAddress() != null) {
      record.getLocation().put("address", instance.getAddress());
    }

    record.setType(payload.getString("service-type", ServiceType.UNKNOWN));

    return record;
  }


  @Override
  public void close(Handler<Void> closeHandler) {
    Future<Void> done = Future.future();
    unregisterAllServices(done);

    done.setHandler(v -> {
      try {
        cache.close();
        discovery.close();
        client.close();
      } catch (Exception e) {
        // Ignore them
      }
      closeHandler.handle(null);
    });
  }

  @Override
  public void childEvent(CuratorFramework curatorFramework,
                         TreeCacheEvent treeCacheEvent) throws Exception {
    if (started) {
      compute(null);
    }
  }

  private synchronized void unregisterAllServices(Future<Void> done) {
    List<Future> list = new ArrayList<>();

    new HashSet<>(registrations).forEach(reg -> {
      Future<Void> unreg = Future.future();
      publisher.unpublish(reg.record().getRegistration(), unreg.completer());
    });
    registrations.clear();

    CompositeFuture.all(list).setHandler(x -> {
      if (x.failed()) {
        done.fail(x.cause());
      } else {
        done.complete();
      }
    });
  }

}