package sttp.client3.metrics.zio

import sttp.capabilities.Effect
import sttp.client3._
import sttp.client3.metrics.zio.ZioMetricsBackend._
import sttp.model.Uri.QuerySegment
import zio._
import zio.metrics.Metric._
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics._

import java.time.temporal.ChronoUnit

object ZioMetricsBackend {
  type MetricsTimer = Metric[MetricKeyType.Histogram, Duration, MetricState.Histogram]

  /** Used for creating request/uri custom labelers. e.g. regex replacer for cases with ids in url e.g. /employee/42 */
  trait RequestMetricLabelTransformer {
    def transform[T, R](request: Request[T, R]): Seq[MetricLabel]
  }

  /** Format the uri without a querystring as query strings are variable and cause metric tag-explosion. */
  object UrlWithOutQuerystringTransformer extends RequestMetricLabelTransformer {
    override def transform[T, R](request: Request[T, R]): Seq[MetricLabel] =
      Seq(MetricLabel("uri", request.uri.copy(querySegments = Seq.empty[QuerySegment]).toString()))
  }

  val DefaultNamespace: String = "sttp"
  val NanosSecondsDivisor: Double = 1000000000D
  val DefaultBuckets: Chunk[Double] = Chunk(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10)
  val DefaultAgeSeconds: Duration = 10.seconds
  val DefaultAgeBuckets: Int = 5
  val defaultBoundaries: Boundaries = Boundaries.fromChunk(DefaultBuckets)

  /** This generates a histogram in seconds. */
  def getTimerSeconds(name: String, boundaries: Boundaries = defaultBoundaries): MetricsTimer =
    histogram(name, boundaries).tagged(MetricLabel("time_unit", ChronoUnit.SECONDS.toString.toLowerCase)).contramap[Duration] { duration =>
      duration.toNanos.toDouble / NanosSecondsDivisor
    }

}

class ZioMetricsBackend[+P](delegate: SttpBackend[Task, P],
                            namespace: String = DefaultNamespace,
                            defaultTags: Set[MetricLabel] = Set.empty,
                            requestMetricLabelTransformer: Seq[RequestMetricLabelTransformer] = Seq(UrlWithOutQuerystringTransformer)
                           ) extends DelegateSttpBackend[Task, P](delegate) {

  val requestLatency: MetricsTimer = getTimerSeconds(s"${namespace}_request_latency")
  val requestSuccess: Metric.Counter[Long] = Metric.counter(s"${namespace}_requests_success_count").tagged(defaultTags)
  val requestError: Metric.Counter[Long] = Metric.counter(s"${namespace}_requests_error_count").tagged(defaultTags)
  val requestFailure: Metric.Counter[Long] = Metric.counter(s"${namespace}_requests_failure_count").tagged(defaultTags)
  val requestInterrupt: Metric.Counter[Long] = Metric.counter(s"${namespace}_requests_interrupt_count").tagged(defaultTags)
  val requestInProgress: Metric.Counter[Long] = Metric.counter(s"${namespace}_requests_in_progress").tagged(defaultTags)
  val requestSizeSummary: Metric.Summary[Double] = Metric.summary(s"${namespace}_request_size_bytes", DefaultAgeSeconds, DefaultAgeBuckets, 0, Chunk.empty).tagged(defaultTags)
  val responseSizeSummary: Metric.Summary[Double] = Metric.summary(s"${namespace}_response_size_bytes", DefaultAgeSeconds, DefaultAgeBuckets, 0, Chunk.empty).tagged(defaultTags)

  def send[T, R >: P with Effect[Task]](request: Request[T, R]): Task[Response[T]] = {
    val requestLabels: Set[MetricLabel] = (
      Seq(MetricLabel("method", request.method.method)) ++
        requestMetricLabelTransformer.flatMap(_.transform(request)) ++
        request.tags.foldLeft(Seq.empty[MetricLabel])((acc, t) => t._2 match {
          case metricLabel: MetricLabel => acc ++ Seq(metricLabel)
          case _ => acc
        })
      ).toSet
    (for {
      _ <- requestSizeSummary.tagged(requestLabels).update((request.contentLength: Option[Long]).map(_.toDouble).getOrElse(0))
      _ <- requestInProgress.tagged(requestLabels).update(1)
      resp <- delegate.send(request) @@ requestLatency.tagged(requestLabels).trackDuration
    } yield resp)
      .onInterrupt(
        _ => requestInterrupt.tagged(requestLabels).update(1)
      )
      .onExit(
        _ => requestInProgress.tagged(requestLabels).update(-1)
      )
      .tapBoth(
        _ => requestError.tagged(requestLabels).update(1),
        response =>
          responseSizeSummary.tagged(requestLabels).update((response.contentLength: Option[Long]).map(_.toDouble).getOrElse(0)) *> {
            if (response.isSuccess) requestSuccess.tagged(requestLabels + MetricLabel("code", response.code.toString())).update(1)
            else requestFailure.tagged(requestLabels + MetricLabel("code", response.code.toString())).update(1)
          }
      )
  }
}
