package com.linkedin.venice.fastclient.meta;

import static com.linkedin.venice.client.store.ClientConfig.defaultGenericClientConfig;
import static com.linkedin.venice.schema.SchemaData.INVALID_VALUE_SCHEMA_ID;

import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.schema.RouterBackedSchemaReader;
import com.linkedin.venice.client.store.AvroGenericStoreClientImpl;
import com.linkedin.venice.client.store.D2ServiceDiscovery;
import com.linkedin.venice.client.store.InternalAvroStoreClient;
import com.linkedin.venice.client.store.transport.D2TransportClient;
import com.linkedin.venice.client.store.transport.TransportClientResponse;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.exceptions.VeniceUnsupportedOperationException;
import com.linkedin.venice.fastclient.ClientConfig;
import com.linkedin.venice.fastclient.stats.ClusterStats;
import com.linkedin.venice.fastclient.stats.FastClientStats;
import com.linkedin.venice.meta.QueryAction;
import com.linkedin.venice.metadata.response.MetadataResponseRecord;
import com.linkedin.venice.metadata.response.VersionProperties;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.schema.SchemaData;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.utils.PartitionUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Store metadata class that uses the server's endpoint to fetch metadata and keep the local cache up to date.
 */
public class RequestBasedMetadata extends AbstractStoreMetadata {
  private static final Logger LOGGER = LogManager.getLogger(RequestBasedMetadata.class);
  private static final String VERSION_PARTITION_SEPARATOR = "_";
  private static final long ZSTD_DICT_FETCH_TIMEOUT = 10;
  static final long DEFAULT_REFRESH_INTERVAL_IN_SECONDS = 60;
  static final long WARMUP_REFRESH_INTERVAL_IN_SECONDS = 5;
  private long refreshIntervalInSeconds;
  private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  private final AtomicInteger currentVersion = new AtomicInteger();
  private final AtomicInteger latestSuperSetValueSchemaId = new AtomicInteger();
  private final AtomicReference<SchemaData> schemas = new AtomicReference<>();
  private final Map<String, List<String>> readyToServeInstancesMap = new VeniceConcurrentHashMap<>();
  private final Map<Integer, VenicePartitioner> versionPartitionerMap = new VeniceConcurrentHashMap<>();
  private final Map<Integer, Integer> versionPartitionCountMap = new VeniceConcurrentHashMap<>();
  private final Map<Integer, ByteBuffer> versionZstdDictionaryMap = new VeniceConcurrentHashMap<>();
  private final Map<String, Integer> helixGroupInfo = new VeniceConcurrentHashMap<>();
  private final CompressorFactory compressorFactory;
  private final D2TransportClient transportClient;
  private D2ServiceDiscovery d2ServiceDiscovery;
  private final String clusterDiscoveryD2ServiceName;
  private final ClusterStats clusterStats;
  private final FastClientStats clientStats;
  private RouterBackedSchemaReader metadataResponseSchemaReader;
  private volatile boolean isServiceDiscovered;
  private volatile boolean isReady;
  private CountDownLatch isReadyLatch = new CountDownLatch(1);

  public RequestBasedMetadata(ClientConfig clientConfig, D2TransportClient transportClient) {
    super(clientConfig);
    this.refreshIntervalInSeconds = clientConfig.getMetadataRefreshIntervalInSeconds() > 0
        ? clientConfig.getMetadataRefreshIntervalInSeconds()
        : DEFAULT_REFRESH_INTERVAL_IN_SECONDS;
    this.transportClient = transportClient;
    this.d2ServiceDiscovery = new D2ServiceDiscovery();
    this.clusterDiscoveryD2ServiceName = transportClient.getServiceName();
    this.compressorFactory = new CompressorFactory();
    this.clusterStats = clientConfig.getClusterStats();
    this.clientStats = clientConfig.getStats(RequestType.SINGLE_GET);
    InternalAvroStoreClient metadataSchemaResponseStoreClient = new AvroGenericStoreClientImpl(
        // Create a new D2TransportClient since the other one will be set to point to server d2 after cluster discovery
        new D2TransportClient(clusterDiscoveryD2ServiceName, transportClient.getD2Client()),
        false,
        defaultGenericClientConfig(AvroProtocolDefinition.SERVER_METADATA_RESPONSE.getSystemStoreName()));
    this.metadataResponseSchemaReader =
        new RouterBackedSchemaReader(() -> metadataSchemaResponseStoreClient, Optional.empty(), Optional.empty());
  }

  // For unit tests only
  synchronized void setMetadataResponseSchemaReader(RouterBackedSchemaReader metadataResponseSchemaReader) {
    this.metadataResponseSchemaReader = metadataResponseSchemaReader;
  }

  @Override
  public int getCurrentStoreVersion() {
    return currentVersion.get();
  }

  @Override
  public int getPartitionId(int version, ByteBuffer key) {
    VenicePartitioner partitioner = versionPartitionerMap.get(version);
    if (partitioner == null) {
      throw new VeniceClientException("Unknown version number: " + version + " for store: " + storeName);
    }
    return partitioner.getPartitionId(key, versionPartitionCountMap.get(version));
  }

  @Override
  public List<String> getReplicas(int version, int partitionId) {
    String key = getVersionPartitionMapKey(version, partitionId);
    return readyToServeInstancesMap.getOrDefault(key, Collections.emptyList());
  }

  private String getVersionPartitionMapKey(int version, int partition) {
    return version + VERSION_PARTITION_SEPARATOR + partition;
  }

  /**
   * Given a version partitioner key formatted as "1_0" which represents partition 0 of version 1, return the version
   * number.
   * @param key
   * @return version number
   */
  private int getVersionFromKey(String key) {
    return Integer.parseInt(key.split(VERSION_PARTITION_SEPARATOR)[0]);
  }

  @Override
  public void start() {
    // perform cluster discovery work upfront and retrieve the server d2 service name
    discoverD2Service();

    // build a base for future metadata updates then start periodic refresh
    refresh();
    try {
      // wait till metadata is warmed up
      isReadyLatch.await();
    } catch (InterruptedException e) {
      // if there is an InterruptedException, let the periodic refresh continue with updating the metadata
      LOGGER.error(
          "Metadata warmup failed and will be retried every {} seconds. Read requests will throw exception until then",
          WARMUP_REFRESH_INTERVAL_IN_SECONDS,
          e);
    }
  }

  void discoverD2Service() {
    if (isServiceDiscovered) {
      return;
    }
    synchronized (this) {
      if (isServiceDiscovered) {
        return;
      }
      transportClient.setServiceName(clusterDiscoveryD2ServiceName);
      String serverD2ServiceName = d2ServiceDiscovery.find(transportClient, storeName, true).getServerD2Service();
      transportClient.setServiceName(serverD2ServiceName);
      isServiceDiscovered = true;
    }
  }

  /**
   * Update is only performed if the version from the fetched metadata is different from the local version. We evict
   * old values as we perform updates, while making sure we keep all currently active versions.
   * @param onDemandRefresh
   * @return if the fetched metadata was an updated version
   */
  synchronized void updateCache(boolean onDemandRefresh) throws InterruptedException {
    long currentTimeMs = System.currentTimeMillis();
    // call the METADATA endpoint
    try {
      TransportClientResponse transportClientResponse = fetchMetadata().get();
      // Metadata response schema forward compatibility support via router backed schema reader
      int writerSchemaId = transportClientResponse.getSchemaId();
      Schema writerSchema = metadataResponseSchemaReader.getValueSchema(writerSchemaId);
      byte[] body = transportClientResponse.getBody();
      RecordDeserializer<MetadataResponseRecord> metadataResponseDeserializer =
          FastSerializerDeserializerFactory.getFastAvroSpecificDeserializer(writerSchema, MetadataResponseRecord.class);
      MetadataResponseRecord metadataResponse = metadataResponseDeserializer.deserialize(body);
      VersionProperties versionMetadata = metadataResponse.getVersionMetadata();

      int fetchedVersion = versionMetadata.getCurrentVersion();

      // call the DICTIONARY endpoint if needed
      CompletableFuture<TransportClientResponse> dictionaryFetchFuture = null;
      if (!versionZstdDictionaryMap.containsKey(fetchedVersion)
          && versionMetadata.getCompressionStrategy() == CompressionStrategy.ZSTD_WITH_DICT.getValue()) {
        dictionaryFetchFuture = fetchCompressionDictionary(fetchedVersion);
      }

      // Update partitioner pair map (versionPartitionerMap)
      int partitionCount = versionMetadata.getPartitionCount();
      Properties params = new Properties();
      params.putAll(versionMetadata.getPartitionerParams());
      VenicePartitioner partitioner = PartitionUtils.getVenicePartitioner(
          versionMetadata.getPartitionerClass().toString(),
          versionMetadata.getAmplificationFactor(),
          new VeniceProperties(params));
      versionPartitionerMap.put(fetchedVersion, partitioner);
      versionPartitionCountMap.put(fetchedVersion, partitionCount);

      // Update readyToServeInstanceMap
      Map<Integer, List<String>> routingInfo = metadataResponse.getRoutingInfo()
          .entrySet()
          .stream()
          .collect(
              Collectors.toMap(
                  e -> Integer.valueOf(e.getKey().toString()),
                  e -> e.getValue().stream().map(CharSequence::toString).collect(Collectors.toList())));

      for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
        String key = getVersionPartitionMapKey(fetchedVersion, partitionId);
        readyToServeInstancesMap.put(key, routingInfo.get(partitionId));
      }

      // Update schemas
      Map.Entry<CharSequence, CharSequence> lastEntry = null;
      for (Map.Entry<CharSequence, CharSequence> entry: metadataResponse.getKeySchema().entrySet()) {
        lastEntry = entry;
      }
      SchemaEntry keySchema = lastEntry == null
          ? null
          : new SchemaEntry(Integer.parseInt(lastEntry.getKey().toString()), lastEntry.getValue().toString());
      SchemaData schemaData = new SchemaData(storeName, keySchema);
      for (Map.Entry<CharSequence, CharSequence> entry: metadataResponse.getValueSchemas().entrySet()) {
        schemaData
            .addValueSchema(new SchemaEntry(Integer.parseInt(entry.getKey().toString()), entry.getValue().toString()));
      }
      schemas.set(schemaData);

      // Update helix group info
      for (Map.Entry<CharSequence, Integer> entry: metadataResponse.getHelixGroupInfo().entrySet()) {
        helixGroupInfo.put(entry.getKey().toString(), entry.getValue());
      }
      routingStrategy.updateHelixGroupInfo(helixGroupInfo);

      latestSuperSetValueSchemaId.set(metadataResponse.getLatestSuperSetValueSchemaId());
      // Wait for dictionary fetch to finish if there is one
      try {
        if (dictionaryFetchFuture != null) {
          dictionaryFetchFuture.get(ZSTD_DICT_FETCH_TIMEOUT, TimeUnit.SECONDS);
        }
      } catch (ExecutionException | TimeoutException e) {
        LOGGER.warn(
            "Dictionary fetch operation could not complete in time for some of the versions. "
                + "Will be retried on next refresh",
            e);
        return;
      }
      // Evict entries from inactive versions
      Set<Integer> activeVersions = new HashSet<>(metadataResponse.getVersions());
      readyToServeInstancesMap.entrySet()
          .removeIf(entry -> !activeVersions.contains(getVersionFromKey(entry.getKey())));
      versionPartitionerMap.entrySet().removeIf(entry -> !activeVersions.contains(entry.getKey()));
      versionPartitionCountMap.entrySet().removeIf(entry -> !activeVersions.contains(entry.getKey()));
      versionZstdDictionaryMap.entrySet().removeIf(entry -> !activeVersions.contains(entry.getKey()));
      currentVersion.set(fetchedVersion);
      clusterStats.updateCurrentVersion(getCurrentStoreVersion());
      // Update the metadata timestamp only if all updates are successful
      clientStats.updateCacheTimestamp(currentTimeMs);
    } catch (ExecutionException e) {
      // perform an on demand refresh if update fails in case of store migration
      // TODO: need a better way to handle store migration
      if (!onDemandRefresh) {
        LOGGER.warn("Metadata fetch operation has failed with exception {}", e.getMessage());
        isServiceDiscovered = false;
        discoverD2Service();
        updateCache(true);
      } else {
        // pass the error along if the on demand refresh also fails
        clusterStats.recordVersionUpdateFailure();
        throw new VeniceClientException("Metadata fetch retry has failed", e.getCause());
      }
    }
  }

  private void refresh() {
    try {
      updateCache(false);
      if (!isReady) {
        isReadyLatch.countDown();
        isReady = true;
        LOGGER.info("Metadata warmup completed successfully");
      }
    } catch (Exception e) {
      // Catch all errors so periodic refresh doesn't break on transient errors.
      LOGGER.error(
          "Metadata periodic refresh encountered unexpected error, will be retried in {} seconds",
          isReady ? refreshIntervalInSeconds : WARMUP_REFRESH_INTERVAL_IN_SECONDS,
          e);
    } finally {
      scheduler.schedule(
          this::refresh,
          isReady ? refreshIntervalInSeconds : WARMUP_REFRESH_INTERVAL_IN_SECONDS,
          TimeUnit.SECONDS);
    }
  }

  @Override
  public void close() throws IOException {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    readyToServeInstancesMap.clear();
    versionPartitionerMap.clear();
    Utils.closeQuietlyWithErrorLogged(metadataResponseSchemaReader);
    Utils.closeQuietlyWithErrorLogged(compressorFactory);
  }

  private CompletableFuture<TransportClientResponse> fetchMetadata() {
    CompletableFuture<TransportClientResponse> metadataFuture = new CompletableFuture<>();
    String url = QueryAction.METADATA.toString().toLowerCase() + "/" + storeName;

    LOGGER.info("Fetching metadata for store {} from URL {} ", storeName, url);
    transportClient.get(url).whenComplete((response, throwable) -> {
      if (throwable != null) {
        String message = String.format("Problem fetching metadata from URL:%s for store:%s ", url, storeName);
        LOGGER.warn(message, throwable);
        metadataFuture.completeExceptionally(throwable);
      } else {
        metadataFuture.complete(response);
      }
    });
    return metadataFuture;
  }

  private CompletableFuture<TransportClientResponse> fetchCompressionDictionary(int version) {
    CompletableFuture<TransportClientResponse> compressionDictionaryFuture = new CompletableFuture<>();
    String url = QueryAction.DICTIONARY.toString().toLowerCase() + "/" + storeName + "/" + version;

    LOGGER.info("Fetching compression dictionary for version {} from URL {} ", version, url);
    transportClient.get(url).whenComplete((response, throwable) -> {
      if (throwable != null) {
        String message = String.format(
            "Problem fetching zstd compression dictionary from URL:%s for store:%s , version:%d",
            url,
            storeName,
            version);
        LOGGER.warn(message, throwable);
        compressionDictionaryFuture.completeExceptionally(throwable);
      } else {
        byte[] dictionary = response.getBody();
        versionZstdDictionaryMap.put(version, ByteBuffer.wrap(dictionary));
        compressionDictionaryFuture.complete(response);
      }
    });
    return compressionDictionaryFuture;
  }

  @Override
  public VeniceCompressor getCompressor(CompressionStrategy compressionStrategy, int version) {
    return getCompressor(compressionStrategy, version, compressorFactory, versionZstdDictionaryMap);
  }

  @Override
  public Schema getKeySchema() {
    return schemas.get().getKeySchema().getSchema();
  }

  @Override
  public Schema getValueSchema(int id) {
    return schemas.get().getValueSchema(id).getSchema();
  }

  @Override
  public int getValueSchemaId(Schema schema) {
    SchemaEntry schemaEntry = new SchemaEntry(INVALID_VALUE_SCHEMA_ID, schema);
    return schemas.get().getSchemaID(schemaEntry);
  }

  @Override
  public Schema getLatestValueSchema() {
    return schemas.get().getValueSchema(getLatestValueSchemaId()).getSchema();
  }

  @Override
  public Integer getLatestValueSchemaId() {
    int latestValueSchemaId = latestSuperSetValueSchemaId.get();
    if (latestValueSchemaId == INVALID_VALUE_SCHEMA_ID) {
      latestValueSchemaId = schemas.get().getMaxValueSchemaId();
    }
    return latestValueSchemaId;
  }

  @Override
  public Schema getUpdateSchema(int valueSchemaId) {
    // Ideally, we can fetch this information from the SchemaData object, but we're not yet populating these schemas
    throw new VeniceUnsupportedOperationException("getUpdateSchema");
  }

  @Override
  public DerivedSchemaEntry getLatestUpdateSchema() {
    // Ideally, we can fetch this information from the SchemaData object, but we're not yet populating these schemas
    throw new VeniceUnsupportedOperationException("getLatestUpdateSchema");
  }

  @Override
  public boolean isReady() {
    return isReady;
  }

  /**
   * Used for test only
   * @param d2ServiceDiscovery
   */
  public synchronized void setD2ServiceDiscovery(D2ServiceDiscovery d2ServiceDiscovery) {
    this.d2ServiceDiscovery = d2ServiceDiscovery;
  }

  public void setScheduler(ScheduledExecutorService scheduler) {
    this.scheduler = scheduler;
  }

  public ScheduledExecutorService getScheduler() {
    return scheduler;
  }

  public void setIsReadyLatch(CountDownLatch isReadyLatch) {
    this.isReadyLatch = isReadyLatch;
  }

  public CountDownLatch getIsReadyLatch() {
    return this.isReadyLatch;
  }

  public void setRefreshIntervalInSeconds(long refreshIntervalInSeconds) {
    this.refreshIntervalInSeconds = refreshIntervalInSeconds;
  }

  public long getRefreshIntervalInSeconds() {
    return refreshIntervalInSeconds;
  }
}
