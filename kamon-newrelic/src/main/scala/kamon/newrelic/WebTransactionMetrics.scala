/*
 * =========================================================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.newrelic

import kamon.metrics._
import kamon.metrics.TraceMetrics.ElapsedTime
import akka.actor.Actor
import kamon.Kamon

trait WebTransactionMetrics {
  self: Actor ⇒

  def collectWebTransactionMetrics(metrics: Map[MetricGroupIdentity, MetricGroupSnapshot]): Seq[NewRelic.Metric] = {
    val apdexBuilder = new ApdexBuilder("Apdex", None, Kamon(NewRelic)(context.system).apdexT)

    // Trace metrics are recorded in nanoseconds.
    var accumulatedHttpDispatcher: MetricSnapshotLike = MetricSnapshot(InstrumentTypes.Histogram, 0, Scale.Nano, Vector.empty)

    val webTransactionMetrics = metrics.collect {
      case (TraceMetrics(name), groupSnapshot) ⇒

        groupSnapshot.metrics collect {
          case (ElapsedTime, snapshot) ⇒
            accumulatedHttpDispatcher = accumulatedHttpDispatcher.merge(snapshot)
            snapshot.measurements.foreach(level ⇒
              apdexBuilder.record(Scale.convert(snapshot.scale, Scale.Unit, level.value), level.count))

            toNewRelicMetric(Scale.Unit)(s"WebTransaction/Custom/$name", None, snapshot)
        }
    }

    val httpDispatcher = toNewRelicMetric(Scale.Unit)("HttpDispatcher", None, accumulatedHttpDispatcher)
    val webTransaction = toNewRelicMetric(Scale.Unit)("WebTransaction", None, accumulatedHttpDispatcher)

    Seq(httpDispatcher, webTransaction, apdexBuilder.build) ++ webTransactionMetrics.flatten.toSeq
  }
}

class ApdexBuilder(name: String, scope: Option[String], apdexT: Double) {
  val frustratingThreshold = 4 * apdexT

  var satisfying = 0L
  var tolerating = 0L
  var frustrating = 0L

  def record(duration: Double, count: Long): Unit =
    if (duration <= apdexT)
      satisfying += count
    else if (duration <= frustratingThreshold)
      tolerating += count
    else
      frustrating += count

  // NewRelic reuses the same metric structure for recording the Apdex.. weird, but that's how it works.
  def build: NewRelic.Metric = NewRelic.Metric(name, scope, satisfying, tolerating, frustrating, apdexT, apdexT, 0)
}
