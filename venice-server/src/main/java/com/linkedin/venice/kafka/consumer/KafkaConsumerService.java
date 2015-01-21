package com.linkedin.venice.kafka.consumer;

import com.linkedin.venice.server.PartitionNodeAssignmentRepository;
import com.linkedin.venice.server.StoreRepository;
import com.linkedin.venice.server.VeniceConfig;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.storage.VeniceStorageNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.log4j.Logger;

// TODO : Rename this class to StreamConsumerService so Kafka or any other propogation layer cna be plugged  later ?
// TODO later separate kafka global and local configs

/**
 * Acts as the running Kafka interface to Venice. Manages the creation of kafka partitions for all stores hosted by
 * this node.
 */
public class KafkaConsumerService extends AbstractVeniceService {

  private static final Logger logger = Logger.getLogger(KafkaConsumerService.class.getName());

  private final StoreRepository storeRepository;
  private final VeniceConfig veniceConfig;
  private final PartitionNodeAssignmentRepository partitionNodeAssignmentRepository;
  private final ConcurrentMap<String, Properties> storeDefinitionsMap;

  /*A repository of kafka topic to their corresponding partitions and the kafka consumer tasks. This may be used in future for monitoring purposes. etc. */
  private final ConcurrentMap<String, Map<Integer, SimpleKafkaConsumerTask>>
      topicNameToPartitionIdAndKafkaConsumerTasksMap;

  private ExecutorService consumerWorkerThreads;

  //TODO instantiate, populate PartitionNodeAssignmentRepository in VeniceServer and pass it here.
  public KafkaConsumerService(StoreRepository storeRepository, VeniceConfig veniceConfig,
      PartitionNodeAssignmentRepository partitionNodeAssignmentRepository,
      ConcurrentMap<String, Properties> storeDefinitionsMap) {
    super("kafka-consumer-service");
    this.storeRepository = storeRepository;
    this.veniceConfig = veniceConfig;
    this.partitionNodeAssignmentRepository = partitionNodeAssignmentRepository;
    this.storeDefinitionsMap = storeDefinitionsMap;
    this.topicNameToPartitionIdAndKafkaConsumerTasksMap =
        new ConcurrentHashMap<String, Map<Integer, SimpleKafkaConsumerTask>>();
  }

  @Override
  public void startInner()
      throws Exception {
    logger.info("starting all kafka consumer tasks on node: " + veniceConfig.getNodeId());
    consumerWorkerThreads = Executors.newFixedThreadPool(veniceConfig.getKafkaConsumerThreads());
    for (Map.Entry<String, Properties> entry : storeDefinitionsMap.entrySet()) {
      String topic = entry.getKey();
      Properties storeConfig = entry.getValue();
      Map<Integer, SimpleKafkaConsumerTask> partitionIdToKafkaConsumerTaskMap;
      if (!this.topicNameToPartitionIdAndKafkaConsumerTasksMap.containsKey(topic)) {
        partitionIdToKafkaConsumerTaskMap = new HashMap<Integer, SimpleKafkaConsumerTask>();
        this.topicNameToPartitionIdAndKafkaConsumerTasksMap.put(topic, partitionIdToKafkaConsumerTaskMap);
      }
      partitionIdToKafkaConsumerTaskMap = this.topicNameToPartitionIdAndKafkaConsumerTasksMap.get(topic);
      for (int partitionId : partitionNodeAssignmentRepository
          .getLogicalPartitionIds(topic, veniceConfig.getNodeId())) {
        SimpleKafkaConsumerTask kafkaConsumerTask = getConsumerTask(topic, partitionId, entry.getValue());
        consumerWorkerThreads.submit(kafkaConsumerTask);
        partitionIdToKafkaConsumerTaskMap.put(partitionId, kafkaConsumerTask);
      }
      this.topicNameToPartitionIdAndKafkaConsumerTasksMap.put(topic, partitionIdToKafkaConsumerTaskMap);
    }
    logger.info("All kafka consumer tasks started.");
  }

  /**
   * Given a topic and partition id, returns a consumer task that is configured and tied to that specific topic and partition
   * @param topicName - The Kafka topic name also same as the Venice store name
   * @param partition - The specific kafka partition id
   * @param storeConfigs  - These are configs specific to a Kafka topic.
   * @return
   */
  public SimpleKafkaConsumerTask getConsumerTask(String topicName, int partition, Properties storeConfigs) {
    // TODO: Note that the store configs may be used in future iterations
    SimpleKafkaConsumerConfig kafkaConfig = new SimpleKafkaConsumerConfig();
    kafkaConfig.setSeedBrokers(veniceConfig.getBrokerList()); // This is a global kafka config
    return new SimpleKafkaConsumerTask(kafkaConfig, storeRepository.getLocalStorageEngine(topicName), topicName,
        partition, veniceConfig.getKafkaBrokerPort());
  }

  @Override
  public void stopInner()
      throws Exception {
    logger.info("Shutting down Kafka consumer service for node: " + veniceConfig.getNodeId());
    if (consumerWorkerThreads != null) {
      consumerWorkerThreads.shutdown();
    }
    logger.info("Shut down complete");
  }
}
