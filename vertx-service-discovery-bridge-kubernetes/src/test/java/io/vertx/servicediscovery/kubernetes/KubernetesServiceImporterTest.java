/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.servicediscovery.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.Watcher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.spi.ServicePublisher;
import io.vertx.servicediscovery.spi.ServiceType;
import io.vertx.servicediscovery.types.HttpEndpoint;
import io.vertx.servicediscovery.types.JDBCDataSource;
import io.vertx.servicediscovery.types.MongoDataSource;
import io.vertx.servicediscovery.types.RedisDataSource;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class KubernetesServiceImporterTest {

  @Test
  public void testRecordCreation() {
    ObjectMeta metadata = new ObjectMeta();
    metadata.setName("my-service");
    metadata.setUid("uuid");
    metadata.setNamespace("my-project");

    ServiceSpec spec = new ServiceSpec();
    ServicePort port = new ServicePort();
    port.setTargetPort(new IntOrString(8080));
    port.setPort(1524);
    spec.setPorts(Collections.singletonList(port));

    Service service = mock(Service.class);
    when(service.getMetadata()).thenReturn(metadata);
    when(service.getSpec()).thenReturn(spec);

    Record record = KubernetesServiceImporter.createRecord(service);
    assertThat(record).isNotNull();
    assertThat(record.getName()).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.name")).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.namespace")).isEqualTo("my-project");
    assertThat(record.getMetadata().getString("kubernetes.uuid")).isEqualTo("uuid");
    assertThat(record.getType()).isEqualTo(ServiceType.UNKNOWN);
    assertThat(record.getLocation().getInteger("port")).isEqualTo(1524);
  }

  @Test
  public void testHttpRecordCreation() {
    Service service = getHttpService();

    Record record = KubernetesServiceImporter.createRecord(service);
    assertThat(record).isNotNull();
    assertThat(record.getName()).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.name")).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.namespace")).isEqualTo("my-project");
    assertThat(record.getMetadata().getString("kubernetes.uuid")).isEqualTo("uuid");
    assertThat(record.getType()).isEqualTo(HttpEndpoint.TYPE);
    assertThat(record.getLocation().getInteger("port")).isEqualTo(8080);
    assertThat(record.getLocation().getBoolean("ssl")).isFalse();
  }

  private Service getHttpService() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("service-type", "http-endpoint");

    ObjectMeta metadata = new ObjectMeta();
    metadata.setName("my-service");
    metadata.setUid("uuid");
    metadata.setNamespace("my-project");
    metadata.setLabels(labels);

    ServiceSpec spec = new ServiceSpec();
    ServicePort port = new ServicePort();
    port.setTargetPort(new IntOrString(80));
    port.setPort(8080);
    spec.setPorts(Collections.singletonList(port));

    Service service = mock(Service.class);
    when(service.getMetadata()).thenReturn(metadata);
    when(service.getSpec()).thenReturn(spec);
    return service;
  }

  @Test
  public void testHttpWithSSLRecordCreation() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("service-type", "http-endpoint");
    labels.put("ssl", "true");

    ObjectMeta metadata = new ObjectMeta();
    metadata.setName("my-service");
    metadata.setUid("uuid");
    metadata.setNamespace("my-project");
    metadata.setLabels(labels);

    ServiceSpec spec = new ServiceSpec();
    ServicePort port = new ServicePort();
    port.setTargetPort(new IntOrString(8080));
    port.setPort(8080);
    spec.setPorts(Collections.singletonList(port));

    Service service = mock(Service.class);
    when(service.getMetadata()).thenReturn(metadata);
    when(service.getSpec()).thenReturn(spec);

    Record record = KubernetesServiceImporter.createRecord(service);
    assertThat(record).isNotNull();
    assertThat(record.getName()).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.name")).isEqualTo("my-service");
    assertThat(record.getMetadata().getString("kubernetes.namespace")).isEqualTo("my-project");
    assertThat(record.getMetadata().getString("kubernetes.uuid")).isEqualTo("uuid");
    assertThat(record.getType()).isEqualTo(HttpEndpoint.TYPE);
    assertThat(record.getLocation().getInteger("port")).isEqualTo(8080);
    assertThat(record.getLocation().getBoolean("ssl")).isTrue();
  }

  @Test
  public void testArrivalAndDeparture() {
    Vertx vertx = Vertx.vertx();
    AtomicReference<Record> record = new AtomicReference<>();
    vertx.eventBus().consumer("vertx.discovery.announce", message ->
        record.set(new Record((JsonObject) message.body())));
    ServicePublisher discovery = (ServicePublisher) ServiceDiscovery.create(vertx);
    KubernetesServiceImporter bridge = new KubernetesServiceImporter();
    Future<Void> future = Future.future();
    bridge.start(vertx, discovery, new JsonObject().put("token", "a token"), future);
    future.setHandler(ar -> {

    });
    bridge.eventReceived(Watcher.Action.ADDED, getHttpService());

    await().until(() -> record.get() != null);
    assertThat(record.get().getStatus()).isEqualTo(io.vertx.servicediscovery.Status.UP);

    record.set(null);
    bridge.eventReceived(Watcher.Action.DELETED, getHttpService());
    await().until(() -> record.get() != null);
    assertThat(record.get().getStatus()).isEqualTo(io.vertx.servicediscovery.Status.DOWN);

    bridge.close(v -> {});

  }

  @Test
  public void testServiceTypeDetection() {
    Map<String, String> labels = new LinkedHashMap<>();

    ObjectMeta metadata = new ObjectMeta();
    metadata.setName("my-service");
    metadata.setUid("uuid");
    metadata.setNamespace("my-project");
    metadata.setLabels(labels);

    ServiceSpec spec = new ServiceSpec();
    ServicePort port = new ServicePort();
    port.setTargetPort(new IntOrString(8080));
    port.setPort(8080);
    spec.setPorts(Collections.singletonList(port));

    Service service = mock(Service.class);
    when(service.getMetadata()).thenReturn(metadata);
    when(service.getSpec()).thenReturn(spec);

    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(443);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(433);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(ServiceType.UNKNOWN);

    port.setPort(8888);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(8080);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(9000);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(80);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(HttpEndpoint.TYPE);

    port.setPort(6379);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(RedisDataSource.TYPE);

    port.setPort(3306);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(JDBCDataSource.TYPE);

    port.setPort(27017);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(MongoDataSource.TYPE);

    port.setPort(27018);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(MongoDataSource.TYPE);

    port.setPort(27019);
    assertThat(KubernetesServiceImporter.discoveryType(service)).isEqualTo(MongoDataSource.TYPE);

  }

}
