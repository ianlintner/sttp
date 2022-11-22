# ZIO Metrics backend

To use, add the following dependency to your project:

```
"com.softwaremill.sttp.client3" %% "zio-metrics-backend" % "@VERSION@"
```

and some imports:

```scala mdoc
import sttp.client3.metrics.zio._
```

This backend depends on [ZIO Core Metrics](https://zio.dev/guides/tutorials/monitor-a-zio-application-using-zio-built-in-metric-system#adding-dependencies-to-the-project). Keep in mind this backend registers histograms and gathers request times, but you have to expose those metrics to [Prometheus](https://prometheus.io/) using a connector e.g. using [zio-metrics-connector](https://zio.dev/guides/tutorials/monitor-a-zio-application-using-zio-built-in-metric-system#adding-dependencies-to-the-project).

The ZIO Metrics backend wraps any other backend, for example using ZLayer construction:

```scala mdoc:compile-only
  import sttp.client3.httpclient.zio.HttpClientZioBackend
  import sttp.client3.metrics.zio._
  import zio._
  
  val metricsWrappedClientLayer: ZLayer[Any, Throwable, Unit] = zio.ZLayer.fromZIO(
    HttpClientZioBackend.apply()
    .map(client => (ZioMetricsBackend(client)))
  )
```

Metrics Provided

* Counters
  * Success
  * Failed
  * Error
  * Interrupted
  * In Progress
* Histogram
 * Latency
* Summary
 * Request Content-Length
 * Response Content-Length
