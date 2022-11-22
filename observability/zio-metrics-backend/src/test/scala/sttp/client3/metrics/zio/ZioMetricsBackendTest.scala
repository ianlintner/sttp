package sttp.client3.metrics.zio

import sttp.client3.Response.ExampleGet
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, UriContext, basicRequest}
import sttp.model.{Header, StatusCode}
import zio.metrics.MetricLabel
import zio.test.TestAspect.withLiveClock
import zio.test._
import zio.{ZIO, durationInt}

object ZioMetricsBackendTest extends ZIOSpecDefault {


  private val stubBackend = SttpBackendStub(new RIOMonadAsyncError[Any]).whenRequestMatchesPartial {
    case r if r.uri.toString.contains("echo") =>
      Response.ok("")
    case r if r.uri.toString.contains("body") =>
      Response("1234", StatusCode(200), "OK", Seq(Header("Content-Length", "4")), Nil, ExampleGet)
    case r if r.uri.toString.contains("long") =>
      Thread.sleep(75)
      Response.ok("")
    case r if r.uri.toString.contains("wait") =>
      Thread.sleep(300)
      Response.ok("")
    case r if r.uri.toString.contains("error") =>
      throw new RuntimeException("something went wrong")
    case r if r.uri.toString.contains("404") =>
      Response("error", StatusCode.NotFound)
  }

  private def backend: ZioMetricsBackend[Any] = new ZioMetricsBackend(stubBackend)

  override def spec: Spec[TestEnvironment, Any] = suite("ZioMetricsBackend")(
    test("requests success count") {
      for {
        _ <- basicRequest.post(uri"http://stub/echo").send(backend)
        _ <- basicRequest.post(uri"http://stub/echo").send(backend)
        state <- backend.requestSuccess.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/echo"),
          MetricLabel("code", "200")
        )).value
      } yield assertTrue(state.count == 2D)
    },
    test("set request/response content length summary") {
      for {
        _ <- basicRequest.post(uri"http://stub/body").body("123456789").send(backend)
        state <- backend.requestSizeSummary.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/body")
        )).value
        state2 <- backend.responseSizeSummary.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/body")
        )).value
      } yield assertTrue(state.max == 9D) && assertTrue(state2.max == 4D)
    },
    test("set requests error count") {
      for {
        _ <- basicRequest.post(uri"http://stub/error").send(backend).orElse(ZIO.unit)
        state <- backend.requestError.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/error")
        )).value
      } yield assertTrue(state.count == 1D)
    },
    test("set requests failed 404 count") {
      for {
        _ <- basicRequest.post(uri"http://stub/404").send(backend)
        state <- backend.requestFailure.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/404"),
          MetricLabel("code", "404")
        )).value
      } yield assertTrue(state.count == 1D)
    },
    test("set requests interrupt count") {
      for {
        _ <- basicRequest.post(uri"http://stub/wait").send(backend).timeout(10.milliseconds)
        _ <- ZIO.sleep(300.milliseconds)
        state <- backend.requestInterrupt.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/wait")
        )).value
      } yield assertTrue(state.count == 1D)
    } @@ withLiveClock,
    test("set requests latency") {
      for {
        _ <- basicRequest.post(uri"http://stub/wait").send(backend)
        _ <- ZIO.sleep(300.milliseconds)
        state <- backend.requestLatency.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/wait")
        )).value
      } yield assertTrue(state.count == 1L) &&
        assertTrue(state.buckets.exists(x => x._1 == .01D && x._2 == 1L))
    } @@ withLiveClock,
    test("Requests in progress count") {
      for {
        _ <- basicRequest.post(uri"http://stub/long").send(backend).fork
        _ <- basicRequest.post(uri"http://stub/long").send(backend).fork
        _ = Thread.sleep(70)
        state <- backend.requestInProgress.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/long")
        )).value
        _ = Thread.sleep(150)
        state2 <- backend.requestInProgress.tagged(Set(
          MetricLabel("method", "POST"),
          MetricLabel("uri", "http://stub/long")
        )).value
      } yield assertTrue(state.count == 2D) &&
        assertTrue(state2.count == 0D)
    } @@ withLiveClock
  )
}
