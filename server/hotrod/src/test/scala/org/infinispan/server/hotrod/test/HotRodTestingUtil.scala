package org.infinispan.server.hotrod.test

import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Method
import org.infinispan.server.hotrod.OperationStatus._
import org.infinispan.server.hotrod._
import logging.Log
import org.infinispan.manager.EmbeddedCacheManager
import java.util.Arrays
import org.infinispan.commons.api.BasicCacheContainer
import org.infinispan.commons.util.Util
import org.infinispan.commons.equivalence.ByteArrayEquivalence
import org.testng.Assert._
import org.infinispan.notifications.Listener
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent
import org.infinispan.remoting.transport.Address
import java.util.concurrent.CountDownLatch
import org.jboss.netty.channel.ChannelFuture
import org.infinispan.configuration.cache.ConfigurationBuilder
import scala.{Array, Byte}
import scala.collection.JavaConversions._
import org.infinispan.test.TestingUtil
import org.infinispan.statetransfer.StateTransferManager
import org.infinispan.server.hotrod.configuration.HotRodServerConfigurationBuilder
import org.infinispan.marshall.core.JBossMarshaller
import org.testng.AssertJUnit.assertEquals
import org.infinispan.container.entries.InternalCacheEntry
import org.infinispan.Cache

/**
 * Test utils for Hot Rod tests.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
object HotRodTestingUtil extends Log {

   val EXPECTED_HASH_FUNCTION_VERSION: Byte = 2

   def host = "127.0.0.1"

   def startHotRodServer(manager: EmbeddedCacheManager): HotRodServer =
      startHotRodServer(manager, UniquePortThreadLocal.get.intValue)

   def startHotRodServer(manager: EmbeddedCacheManager, defaultCacheName: String): HotRodServer =
      startHotRodServer(manager, UniquePortThreadLocal.get.intValue, 0, host, UniquePortThreadLocal.get.intValue, 0, defaultCacheName)

   def startHotRodServer(manager: EmbeddedCacheManager, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, UniquePortThreadLocal.get.intValue, 0, proxyHost, proxyPort)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int): HotRodServer =
      startHotRodServer(manager, port, 0)

   def startHotRodServer(manager: EmbeddedCacheManager, port:Int, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, port, 0, proxyHost, proxyPort)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int): HotRodServer =
      startHotRodServer(manager, port, idleTimeout, host, port)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, port, idleTimeout, proxyHost, proxyPort, -1)

   def startHotRodServerWithDelay(manager: EmbeddedCacheManager, port: Int, delay: Long): HotRodServer =
      startHotRodServer(manager, port, 0, host, port, delay)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int,
                         proxyHost: String, proxyPort: Int, delay: Long, defaultCacheName: String = BasicCacheContainer.DEFAULT_CACHE_NAME): HotRodServer = {
      val builder = new HotRodServerConfigurationBuilder
      builder.proxyHost(proxyHost).proxyPort(proxyPort).idleTimeout(idleTimeout).defaultCacheName(defaultCacheName)
      startHotRodServer(manager, port, delay, builder)
   }

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, builder: HotRodServerConfigurationBuilder): HotRodServer =
      startHotRodServer(manager, port, 0, builder)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, delay: Long, builder: HotRodServerConfigurationBuilder): HotRodServer = {
      info("Start server in port %d", port)
      val server = new HotRodServer {
         override protected def createTopologyCacheConfig(distSyncTimeout: Long): ConfigurationBuilder = {
            if (delay > 0)
               Thread.sleep(delay)

            val cfg = super.createTopologyCacheConfig(distSyncTimeout)
            cfg.transaction().syncCommitPhase(false).syncRollbackPhase(false)
            cfg
         }
      }
      builder.host(host).port(port).workerThreads(2)
      server.start(builder.build(), manager)

      server
   }

   def startCrashingHotRodServer(manager: EmbeddedCacheManager, port: Int): HotRodServer = {
      val server = new HotRodServer {
         override protected def createTopologyCacheConfig(distSyncTimeout: Long): ConfigurationBuilder = {
            val cfg = super.createTopologyCacheConfig(distSyncTimeout)
            cfg.transaction().syncCommitPhase(false).syncRollbackPhase(false)
            cfg
         }
      }
      server.start(new HotRodServerConfigurationBuilder().proxyHost(host).proxyPort(port).host(host).port(port).idleTimeout(0).build(), manager)
      server
   }

   def k(m: Method, prefix: String): Array[Byte] = {
      val bytes: Array[Byte] = (prefix + m.getName).getBytes
      trace("String %s is converted to %s bytes", prefix + m.getName, Util.printArray(bytes, true))
      bytes
   }

   def v(m: Method, prefix: String): Array[Byte] = k(m, prefix)

   def k(m: Method): Array[Byte] = k(m, "k-")

   def v(m: Method): Array[Byte] = v(m, "v-")

   def assertStatus(resp: TestResponse, expected: OperationStatus): Boolean = {
      val status = resp.status
      val isSuccess = status == expected
      resp match {
         case e: TestErrorResponse =>
            assertTrue(isSuccess,
               "Status should have been '%s' but instead was: '%s', and the error message was: %s"
               .format(expected, status, e.msg))
         case _ => assertTrue(isSuccess,
               "Status should have been '%s' but instead was: '%s'"
               .format(expected, status))
      }
      isSuccess
   }

   def assertSuccess(resp: TestGetResponse, expected: Array[Byte]): Boolean = {
      assertStatus(resp, Success)
      val isArrayEquals = Arrays.equals(expected, resp.data.get)
      assertTrue(isArrayEquals, "Retrieved data should have contained " + Util.printArray(expected, true)
            + " (" + new String(expected) + "), but instead we received " + Util.printArray(resp.data.get, true) + " (" +  new String(resp.data.get) +")")
      isArrayEquals
   }

   def assertSuccess(resp: TestGetWithVersionResponse, expected: Array[Byte], expectedVersion: Int): Boolean = {
      assertTrue(resp.version != expectedVersion)
      assertSuccess(resp, expected)
   }

   def assertSuccess(resp: TestGetWithMetadataResponse, expected: Array[Byte], expectedLifespan: Int, expectedMaxIdle: Int): Boolean = {
      assertEquals(resp.lifespan, expectedLifespan)
      assertEquals(resp.maxIdle, expectedMaxIdle)
      assertSuccess(resp, expected)
   }

   def assertSuccess(resp: TestResponseWithPrevious, expected: Array[Byte]): Boolean = {
      assertStatus(resp, Success)
      val isSuccess = Arrays.equals(expected, resp.previous.get)
      assertTrue(isSuccess)
      isSuccess
   }

   def assertKeyDoesNotExist(resp: TestGetResponse): Boolean = {
      val status = resp.status
      assertTrue(status == KeyDoesNotExist, "Status should have been 'KeyDoesNotExist' but instead was: " + status)
      assertEquals(resp.data, None)
      status == KeyDoesNotExist
   }

   def assertTopologyReceived(resp: AbstractTestTopologyAwareResponse, servers: List[HotRodServer],
                              expectedTopologyId : Int) {
      assertEquals(resp.topologyId, expectedTopologyId)
      resp match {
         case h10: TestHashDistAware10Response =>
            assertEquals(h10.members.size, servers.size)
            assertEquals(h10.members.toSet, servers.map(_.getAddress).toSet)
         case h11: TestHashDistAware11Response =>
            assertEquals(h11.membersToHash.size, servers.size)
            assertEquals(h11.membersToHash.keySet, servers.map(_.getAddress).toSet)
         case t: TestTopologyAwareResponse =>
            assertEquals(t.members.size, servers.size)
            assertEquals(t.members.toSet, servers.map(_.getAddress).toSet)
      }
   }

   def assertHashTopology10Received(topoResp: AbstractTestTopologyAwareResponse, servers: List[HotRodServer],
                                    cacheName: String, expectedTopologyId : Int) {
      assertHashTopology10Received(topoResp, servers, cacheName, 2,
            EXPECTED_HASH_FUNCTION_VERSION, Integer.MAX_VALUE, expectedTopologyId)
   }

   def assertNoHashTopologyReceived(topoResp: AbstractTestTopologyAwareResponse, servers: List[HotRodServer],
                                    cacheName: String, expectedTopologyId : Int) {
      assertHashTopology10Received(topoResp, servers, cacheName, 0, 0, 0, expectedTopologyId)
   }

   def assertHashTopology10Received(topoResp: AbstractTestTopologyAwareResponse,
                                    servers: List[HotRodServer], cacheName: String,
                                    expectedNumOwners: Int, expectedHashFct: Int, expectedHashSpace: Int,
                                    expectedTopologyId : Int) {
      val hashTopologyResp = topoResp.asInstanceOf[TestHashDistAware10Response]
      assertEquals(hashTopologyResp.topologyId, expectedTopologyId)
      assertEquals(hashTopologyResp.members.size, servers.size)
      hashTopologyResp.members.foreach(member => servers.map(_.getAddress).exists(_ == member))
      assertEquals(hashTopologyResp.numOwners, expectedNumOwners)
      assertEquals(hashTopologyResp.hashFunction, expectedHashFct)
      assertEquals(hashTopologyResp.hashSpace, expectedHashSpace)
      if (expectedNumOwners != 0) // Hash ids worth comparing
         assertHashIds(hashTopologyResp.hashIds, servers, cacheName)
   }


   def assertHashTopologyReceived(topoResp: AbstractTestTopologyAwareResponse,
                                  servers: List[HotRodServer], cacheName : String,
                                  expectedNumOwners: Int, expectedVirtualNodes: Int,
                                  expectedTopologyId : Int) {
      val hashTopologyResp = topoResp.asInstanceOf[TestHashDistAware11Response]
      assertEquals(hashTopologyResp.topologyId, expectedTopologyId)
      assertEquals(hashTopologyResp.membersToHash.size, servers.size)
      hashTopologyResp.membersToHash.foreach(member => servers.map(_.getAddress).exists(_ == member))
      assertEquals(hashTopologyResp.numOwners, expectedNumOwners)
      assertEquals(hashTopologyResp.hashFunction,
         if (expectedNumOwners != 0) EXPECTED_HASH_FUNCTION_VERSION else 0)
      assertEquals(hashTopologyResp.hashSpace,
         if (expectedNumOwners != 0) Integer.MAX_VALUE else 0)
      assertEquals(hashTopologyResp.numVirtualNodes, expectedVirtualNodes)
   }

   def assertHashIds(hashIds: Map[ServerAddress, Seq[Int]], servers: List[HotRodServer], cacheName: String) {
      val cache = servers.head.getCacheManager.getCache(cacheName)
      val stateTransferManager = TestingUtil.extractComponent(cache, classOf[StateTransferManager])
      val consistentHash = stateTransferManager.getCacheTopology.getCurrentCH
      val numSegments = consistentHash.getNumSegments
      val numOwners = consistentHash.getNumOwners
      assertEquals(hashIds.size, servers.size)

      val segmentSize = math.ceil(Int.MaxValue.toDouble / numSegments).toInt
      val owners = new Array[collection.mutable.Map[Int, ServerAddress]](numSegments)

      for ((serverAddress, serverHashIds) <- hashIds) {
         for (hashId <- serverHashIds) {
            val segmentIdx = (hashId / segmentSize + numSegments - 1) % numSegments
            val ownerIdx = hashId % segmentSize;
            if (owners(segmentIdx) == null) {
               owners(segmentIdx) = collection.mutable.Map[Int, ServerAddress]()
            }
            owners(segmentIdx) += (ownerIdx -> serverAddress)
         }
      }

      for (i <- 0 until numSegments) {
         val segmentOwners = owners(i).toList.sortBy(_._1).map(_._2)
         assertEquals(segmentOwners.size, numOwners)
         val chOwners = consistentHash.locateOwnersForSegment(i)
               .map(a => clusterAddressToServerAddress(servers, a))
         assertEquals(segmentOwners, chOwners)
      }
   }

   def assertReplicatedHashIds(hashIds: Map[ServerAddress, Seq[Int]], servers: List[HotRodServer], cacheName: String) {
      val cache = servers.head.getCacheManager.getCache(cacheName)
      val stateTransferManager = TestingUtil.extractComponent(cache, classOf[StateTransferManager])
      val consistentHash = stateTransferManager.getCacheTopology.getCurrentCH
      val numSegments = consistentHash.getNumSegments
      val numOwners = consistentHash.getNumOwners

      // replicated responses have just one segment, and each server should have only one hash id: 0
      assertEquals(hashIds.size, servers.size)
      assertEquals(numSegments, 1)

      for ((serverAddress, serverHashIds) <- hashIds) {
         assertEquals(serverHashIds.size, 1)
         assertEquals(serverHashIds(0), 0)
      }
   }

   def clusterAddressToServerAddress(servers : List[HotRodServer], clusterAddress : Address) : ServerAddress = {
      return servers.find(_.getCacheManager.getAddress == clusterAddress).get.getAddress
   }

   def getServerTopologyId(cm: EmbeddedCacheManager, cacheName: String): Int = {
      cm.getCache(cacheName).getAdvancedCache.getRpcManager.getTopologyId
   }

   def killClient(client: HotRodClient): ChannelFuture = {
      try {
         if (client != null) client.stop
         else null
      }
      catch {
         case t: Throwable => {
            error("Error stopping client", t)
            null
         }
      }
   }

   def hotRodCacheConfiguration(): ConfigurationBuilder =
      hotRodCacheConfiguration(new ConfigurationBuilder())

   def hotRodCacheConfiguration(base: ConfigurationBuilder): ConfigurationBuilder = {
      base.dataContainer()
              .keyEquivalence(ByteArrayEquivalence.INSTANCE)
              .valueEquivalence(ByteArrayEquivalence.INSTANCE)
      base
   }

   def assertHotRodEquals(cm: EmbeddedCacheManager,
           key: Array[Byte], expectedValue: Array[Byte]): InternalCacheEntry =
      assertHotRodEquals(cm, cm.getCache[Array[Byte], Array[Byte]](), key, expectedValue)

   def assertHotRodEquals(cm: EmbeddedCacheManager, cacheName: String,
           key: Array[Byte], expectedValue: Array[Byte]): InternalCacheEntry =
      assertHotRodEquals(cm, cm.getCache[Array[Byte], Array[Byte]](cacheName), key, expectedValue)

   def assertHotRodEquals(cm: EmbeddedCacheManager,
           key: String, expectedValue: String): InternalCacheEntry =
      assertHotRodEquals(cm, cm.getCache[Array[Byte], Array[Byte]](),
         marshall(key), marshall(expectedValue))

   def assertHotRodEquals(cm: EmbeddedCacheManager,
           cacheName: String, key: String, expectedValue: String): InternalCacheEntry =
      assertHotRodEquals(cm, cm.getCache[Array[Byte], Array[Byte]](cacheName),
         marshall(key), marshall(expectedValue))

   private def assertHotRodEquals(cm: EmbeddedCacheManager,
           cache: Cache[Array[Byte], Array[Byte]],
           key: Array[Byte], expectedValue: Array[Byte]): InternalCacheEntry = {
      val entry = cache.getAdvancedCache.getDataContainer.get(key)
      // Assert based on passed parameters
      if (expectedValue == null) {
         assertNull(entry)
      } else {
         val value =
            if (entry == null) cache.get(key)
            else entry.getValue.asInstanceOf[Array[Byte]]

         assertEquals(expectedValue, value)
      }

      entry
   }

   def marshall(obj: String): Array[Byte] =
      if (obj == null) null else new JBossMarshaller().objectToByteBuffer(obj, 64)

   def unmarshall[T](key: Array[Byte]): T =
      new JBossMarshaller().objectFromByteBuffer(key).asInstanceOf[T]

   @Listener
   private class AddressRemovalListener(latch: CountDownLatch) {

      @CacheEntryRemoved
      def addressRemoved(event: CacheEntryRemovedEvent[Address, ServerAddress]) {
         if (!event.isPre) // Only count down latch after address has been removed
            latch.countDown()
      }

   }

}

object UniquePortThreadLocal extends ThreadLocal[Int] {

   private val uniqueAddr = new AtomicInteger(12311)

   override def initialValue: Int = {
      HotRodTestingUtil.debug("Before incrementing, server port is: %d", uniqueAddr.get())
      val port = uniqueAddr.getAndAdd(100)
      HotRodTestingUtil.debug("For next thread, server port will be: %d", uniqueAddr.get())
      port
   }

}
