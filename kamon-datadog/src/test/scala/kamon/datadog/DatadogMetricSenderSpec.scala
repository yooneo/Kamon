/*
 * =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.datadog

import akka.testkit.{ TestKitBase, TestProbe }
import akka.actor.{Props, ActorRef, ActorSystem}
import kamon.metrics.instruments.CounterRecorder
import org.scalatest.{ Matchers, WordSpecLike }
import kamon.metrics._
import akka.io.Udp
import org.HdrHistogram.HdrRecorder
import kamon.metrics.Subscriptions.TickMetricSnapshot
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import com.typesafe.config.ConfigFactory

class DatadogMetricSenderSpec extends TestKitBase with WordSpecLike with Matchers {
  implicit lazy val system = ActorSystem("datadog-metric-sender-spec",
    ConfigFactory.parseString("kamon.datadog.max-packet-size = 256 bytes"))

  "the DataDogMetricSender" should {
    "flush the metrics data after processing the tick, even if the max-packet-size is not reached" in new UdpListenerFixture {
      val testMetricName = "test-metric"
      val testMetricKey = buildMetricKey(testMetricName)
      val testRecorder = HdrRecorder(1000L, 2, Scale.Unit)
      testRecorder.record(10L)

      val udp = setup(Map(testMetricName -> testRecorder.collect()))
      val Udp.Send(data, _, _) = udp.expectMsgType[Udp.Send]

      data.utf8String should be(s"$testMetricKey:10|ms")
    }

    "include the correspondent sampling rate when rendering multiple occurrences of the same value" in new UdpListenerFixture {
      val testMetricName = "test-metric"
      val testMetricKey = buildMetricKey(testMetricName)
      val testRecorder = HdrRecorder(1000L, 2, Scale.Unit)
      testRecorder.record(10L)
      testRecorder.record(10L)

      val udp = setup(Map(testMetricName -> testRecorder.collect()))
      val Udp.Send(data, _, _) = udp.expectMsgType[Udp.Send]

      data.utf8String should be(s"$testMetricKey:10|ms|@0.5")
    }

    "flush the packet when the max-packet-size is reached" in new UdpListenerFixture {
      val testMetricName = "test-metric"
      val testMetricKey = buildMetricKey(testMetricName)
      val testRecorder = HdrRecorder(testMaxPacketSize, 3, Scale.Unit)

      var bytes = 0//testMetricKey.length
      var level = 0
      while (bytes <= testMaxPacketSize) {
        level += 1
        testRecorder.record(level)
        bytes += s"$testMetricKey:$level|ms".length
      }

      val udp = setup(Map(testMetricName -> testRecorder.collect()))
      udp.expectMsgType[Udp.Send] // let the first flush pass
      val Udp.Send(data, _, _) = udp.expectMsgType[Udp.Send]

      data.utf8String should be(s"$testMetricKey:$level|ms")
    }

    "render multiple keys in the same packet using newline as separator" in new UdpListenerFixture {
      val firstTestMetricName = "first-test-metric"
      val firstTestMetricKey = buildMetricKey(firstTestMetricName)
      val secondTestMetricName = "second-test-metric"
      val secondTestMetricKey = buildMetricKey(secondTestMetricName)
      val thirdTestMetricName = "third-test-metric"
      val thirdTestMetricKey = buildMetricKey(thirdTestMetricName)

      val firstTestRecorder = HdrRecorder(1000L, 2, Scale.Unit)
      val secondTestRecorder = HdrRecorder(1000L, 2, Scale.Unit)
      val thirdTestRecorder = CounterRecorder()

      firstTestRecorder.record(10L)
      firstTestRecorder.record(10L)

      secondTestRecorder.record(21L)

      thirdTestRecorder.record(1L)
      thirdTestRecorder.record(1L)
      thirdTestRecorder.record(1L)
      thirdTestRecorder.record(1L)

      val t = thirdTestRecorder.collect()
      val udp = setup(Map(
        firstTestMetricName -> firstTestRecorder.collect(),
        secondTestMetricName -> secondTestRecorder.collect(),
        thirdTestMetricName -> t))
      val Udp.Send(data, _, _) = udp.expectMsgType[Udp.Send]

      data.utf8String should be(s"$firstTestMetricKey:10|ms|@0.5\n$secondTestMetricKey:21|ms\n$thirdTestMetricKey:4|c")
    }
  }

  trait UdpListenerFixture {
    val localhostName = ManagementFactory.getRuntimeMXBean.getName.split('@')(1)
    val testMaxPacketSize = system.settings.config.getBytes("kamon.datadog.max-packet-size")

    def buildMetricKey(metricName: String): String = s"kamon.$localhostName.test-metric-category.test-group.$metricName"

    def setup(metrics: Map[String, MetricSnapshotLike]): TestProbe = {
      val udp = TestProbe()
      val metricsSender = system.actorOf(Props(new DatadogMetricsSender(new InetSocketAddress(localhostName, 0), testMaxPacketSize) {
        override def udpExtension(implicit system: ActorSystem): ActorRef = udp.ref
      }))

      // Setup the SimpleSender
      udp.expectMsgType[Udp.SimpleSender]
      udp.reply(Udp.SimpleSenderReady)

      val testGroupIdentity = new MetricGroupIdentity {
        val name: String = "test-group"
        val category: MetricGroupCategory = new MetricGroupCategory {
          val name: String = "test-metric-category"
        }
      }

      val testMetrics = for ((metricName, snapshot) ← metrics) yield {
        val testMetricIdentity = new MetricIdentity {
          val name: String = metricName
          val tag: String = ""
        }

        (testMetricIdentity, snapshot)
      }

      metricsSender ! TickMetricSnapshot(0, 0, Map(testGroupIdentity -> new MetricGroupSnapshot {
        val metrics: Map[MetricIdentity, MetricSnapshotLike] = testMetrics.toMap
      }))

      udp
    }
  }
}
