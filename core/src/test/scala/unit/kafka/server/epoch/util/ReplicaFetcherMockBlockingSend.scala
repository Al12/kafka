/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package kafka.server.epoch.util

import java.util
import java.util.Collections

import kafka.cluster.BrokerEndPoint
import kafka.server.BlockingSend
import org.apache.kafka.clients.MockClient.MockMetadataUpdater
import org.apache.kafka.clients.{ClientRequest, ClientResponse, MockClient}
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.record.Records
import org.apache.kafka.common.requests.AbstractRequest.Builder
import org.apache.kafka.common.requests.{AbstractRequest, EpochEndOffset, FetchResponse, OffsetsForLeaderEpochResponse, FetchMetadata => JFetchMetadata}
import org.apache.kafka.common.utils.{SystemTime, Time}
import org.apache.kafka.common.{Node, TopicPartition}

/**
  * Stub network client used for testing the ReplicaFetcher, wraps the MockClient used for consumer testing
  *
  * The common case is that there is only one OFFSET_FOR_LEADER_EPOCH request/response. So, the
  * response to OFFSET_FOR_LEADER_EPOCH is 'offsets' map. If the test needs to set another round of
  * OFFSET_FOR_LEADER_EPOCH with different offsets in response, it should update offsets using
  * setOffsetsForNextResponse
  */
class ReplicaFetcherMockBlockingSend(offsets: java.util.Map[TopicPartition, EpochEndOffset], destination: BrokerEndPoint, time: Time) extends BlockingSend {
  private val client = new MockClient(new SystemTime, new MockMetadataUpdater {
    override def fetchNodes(): util.List[Node] = Collections.emptyList()
    override def isUpdateNeeded: Boolean = false
    override def update(time: Time, update: MockClient.MetadataUpdate): Unit = {}
  })
  var fetchCount = 0
  var epochFetchCount = 0
  var lastUsedOffsetForLeaderEpochVersion = -1
  var callback: Option[() => Unit] = None
  var currentOffsets: java.util.Map[TopicPartition, EpochEndOffset] = offsets

  def setEpochRequestCallback(postEpochFunction: () => Unit){
    callback = Some(postEpochFunction)
  }

  def setOffsetsForNextResponse(newOffsets: java.util.Map[TopicPartition, EpochEndOffset]): Unit = {
    currentOffsets = newOffsets
  }

  override def sendRequest(requestBuilder: Builder[_ <: AbstractRequest]): ClientResponse = {

    //Send the request to the mock client
    val clientRequest = request(requestBuilder)
    client.send(clientRequest, time.absoluteMilliseconds())

    //Create a suitable response based on the API key
    val response = requestBuilder.apiKey() match {
      case ApiKeys.OFFSET_FOR_LEADER_EPOCH =>
        callback.foreach(_.apply())
        epochFetchCount += 1
        lastUsedOffsetForLeaderEpochVersion = requestBuilder.latestAllowedVersion()
        new OffsetsForLeaderEpochResponse(currentOffsets)

      case ApiKeys.FETCH =>
        fetchCount += 1
        new FetchResponse(Errors.NONE, new java.util.LinkedHashMap[TopicPartition, FetchResponse.PartitionData[Records]], 0,
          JFetchMetadata.INVALID_SESSION_ID)

      case _ =>
        throw new UnsupportedOperationException
    }

    //Use mock client to create the appropriate response object
    client.respondFrom(response, new Node(destination.id, destination.host, destination.port))
    client.poll(30, time.absoluteMilliseconds()).iterator().next()
  }

  private def request(requestBuilder: Builder[_ <: AbstractRequest]): ClientRequest = {
    client.newClientRequest(
      destination.id.toString,
      requestBuilder,
      time.absoluteMilliseconds(),
      true)
  }

  override def initiateClose(): Unit = {}

  override def close(): Unit = {}
}
