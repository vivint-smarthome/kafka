/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import java.util
import java.util.Properties

import kafka.api.LeaderAndIsr
import kafka.common.TopicAndPartition
import kafka.controller.LeaderIsrAndControllerEpoch
import ly.stealth.mesos.kafka.Util.KafkaZkStringSerializer

import scala.collection.JavaConversions._
import scala.collection.{mutable, Seq, Map}

import org.I0Itec.zkclient.ZkClient

import kafka.admin._
import kafka.utils.{ZkUtils, ZKStringSerializer}
import scala.util.parsing.json.JSONObject
import ly.stealth.mesos.kafka.Topics.Topic
import kafka.log.LogConfig

class Topics {
  private def newZkClient: ZkClient = new ZkClient(Config.zk, 30000, 30000, KafkaZkStringSerializer)

  def getTopic(name: String): Topics.Topic = {
    if (name == null) return null
    val topics: util.List[Topic] = getTopics.filter(_.name == name)
    if (topics.length > 0) topics(0) else null
  }

  def getTopics: util.List[Topics.Topic] = {
    val zkClient = newZkClient
    val zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)

    try {
      val names = zkUtils.getAllTopics()

      val assignments: mutable.Map[String, Map[Int, Seq[Int]]] = zkUtils.getPartitionAssignmentForTopics(names)
      val configs = AdminUtils.fetchAllTopicConfigs(zkUtils)

      val topics = new util.ArrayList[Topics.Topic]
      for (name <- names.sorted)
        topics.add(new Topics.Topic(
          name,
          assignments.getOrElse(name, null).mapValues(brokers => new util.ArrayList[Int](brokers)),
          new util.TreeMap[String, String](propertiesAsScalaMap(configs.getOrElse(name, null)))
        ))

      topics
    } finally {
      zkClient.close()
    }
  }

  private val NoLeader = LeaderIsrAndControllerEpoch(LeaderAndIsr(LeaderAndIsr.NoLeader, -1, List(), -1), -1)

  def getPartitions(topics: util.List[String]): Map[String, Set[Topics.Partition]] = {
    val zkClient = newZkClient
    val zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)

    try {
      // returns topic name -> (partition -> brokers)
      val assignments = zkUtils.getPartitionAssignmentForTopics(topics)
      val topicAndPartitions = assignments.flatMap {
        case (topic, partitions) => partitions.map {
          case (partition, _)  => TopicAndPartition(topic, partition)
        }
      }.toSet
      val leaderAndisr =  zkUtils.getPartitionLeaderAndIsrForTopics(zkClient, topicAndPartitions)

      topicAndPartitions.map(tap => {
        val replicas = assignments(tap.topic).getOrElse(tap.partition, Seq())
        val partitionLeader = leaderAndisr.getOrElse(tap, NoLeader)
        tap.topic -> new Topics.Partition(
          tap.partition,
          replicas,
          partitionLeader.leaderAndIsr.isr,
          partitionLeader.leaderAndIsr.leader,
          replicas.headOption.getOrElse(-1)
        )
      }).groupBy(_._1).mapValues(v => v.map(_._2))
    }
    finally {
      zkClient.close()
    }
  }

  def fairAssignment(partitions: Int = 1, replicas: Int = 1, brokers: util.List[Int] = null): util.Map[Int, util.List[Int]] = {
    var brokers_ = brokers

    if (brokers_ == null) {
      val zkClient = newZkClient
      val zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)
      try { brokers_ = zkUtils.getSortedBrokerList()}
      finally { zkClient.close() }
    }

    AdminUtils.assignReplicasToBrokers(brokers_.map(BrokerMetadata(_, None)), partitions, replicas, 0, 0).mapValues(new util.ArrayList[Int](_))
  }

  def addTopic(name: String, assignment: util.Map[Int, util.List[Int]] = null, options: util.Map[String, String] = null): Topic = {
    var assignment_ = assignment
    if (assignment_ == null) assignment_ = fairAssignment(1, 1, null)

    val config: Properties = new Properties()
    if (options != null)
      for ((k, v) <- options) config.setProperty(k, v)

    val zkClient = newZkClient
    val zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)
    try { AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, name, assignment_.mapValues(_.toList), config) }
    finally { zkClient.close() }

    getTopic(name)
  }

  def updateTopic(topic: Topic, options: util.Map[String, String]): Unit = {
    val config: Properties = new Properties()
    for ((k, v) <- options) config.setProperty(k, v)

    val zkClient = newZkClient
    val zkUtils = ZkUtils(zkClient, isZkSecurityEnabled = false)
    try { AdminUtils.changeTopicConfig(zkUtils, topic.name, config) }
    finally { zkClient.close() }
  }

  def validateOptions(options: util.Map[String, String]): String = {
    val config: Properties = new Properties()
    for ((k, v) <- options) config.setProperty(k, v)

    try { LogConfig.validate(config) }
    catch { case e: IllegalArgumentException => return e.getMessage }
    null
  }
}

object Topics {
  class Exception(message: String) extends java.lang.Exception(message)

  class Partition(
      _id: Int = 0,
      _replicas: util.List[Int] = null,
      _isr: util.List[Int] = null,
      _leader: Int = 0,
      _expectedLeader: Int = 0) {
    var id = _id
    var replicas = _replicas
    var isr = _isr
    var leader = _leader
    var expectedLeader = _expectedLeader

    def fromJson(node: Map[String, Object]): Topics.Partition = {
      id = node("id").asInstanceOf[Int]
      replicas = node("replicas").asInstanceOf[List[Int]]
      isr = node("isr").asInstanceOf[List[Int]]
      leader = node("leader").asInstanceOf[Int]
      expectedLeader = node("expectedLeader").asInstanceOf[Int]

      this
    }

    def toJson: JSONObject = {
      val obj = new mutable.LinkedHashMap[String, Any]()
      obj("id") = id
      obj("replicas") = replicas
      obj("isr") = isr
      obj("leader") = leader
      obj("expectedLeader") = expectedLeader

      new JSONObject(obj.toMap)
    }
  }

  class Topic(
    _name: String = null,
    _partitions: util.Map[Int, util.List[Int]] = new util.HashMap[Int, util.List[Int]](),
    _options: util.Map[String, String] = new util.HashMap[String, String]()
  ) {
    var name: String = _name
    var partitions: util.Map[Int, util.List[Int]] = _partitions
    var options: util.Map[String, String] = _options

    def partitionsState: String = {
      var s: String = ""
      for ((partition, brokers) <- partitions) {
        if (!s.isEmpty) s += ", "
        s += partition + ":[" + brokers.mkString(",") + "]"
      }
      s
    }

    def fromJson(node: Map[String, Object]): Unit = {
      name = node("name").asInstanceOf[String]

      val partitionsObj: Map[String, String] = node("partitions").asInstanceOf[Map[String, String]]
      for ((k, v) <- partitionsObj)
        partitions.put(Integer.parseInt(k), v.split(", ").toList.map(Integer.parseInt))

      options = node("options").asInstanceOf[Map[String, String]]
    }

    def toJson: JSONObject = {
      val obj = new collection.mutable.LinkedHashMap[String, Any]()
      obj("name") = name

      val partitionsObj = new collection.mutable.LinkedHashMap[String, Any]()
      for ((partition, brokers) <- partitions)
        partitionsObj.put("" + partition, brokers.mkString(", "))
      obj("partitions") = new JSONObject(partitionsObj.toMap)

      obj.put("options", new JSONObject(options.toMap))
      new JSONObject(obj.toMap)
    }
  }
}
