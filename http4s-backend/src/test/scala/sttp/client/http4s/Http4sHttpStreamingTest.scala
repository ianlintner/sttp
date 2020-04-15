package sttp.client.http4s

import cats.effect.IO
import cats.instances.string._
import fs2.{Chunk, Stream, text}
import sttp.client.{NothingT, SttpBackend}
import sttp.client.impl.cats.CatsTestBase
import sttp.client.testing.streaming.StreamingTest

import scala.concurrent.ExecutionContext

import org.http4s.client.blaze.BlazeClientBuilder

class Http4sHttpStreamingTest extends StreamingTest[IO, Stream[IO, Byte]] with CatsTestBase {

  private val blazeClientBuilder = BlazeClientBuilder[IO](ExecutionContext.Implicits.global)
  override implicit val backend: SttpBackend[IO, Stream[IO, Byte], NothingT] =
    Http4sBackend.usingClientBuilder(blazeClientBuilder).allocated.unsafeRunSync()._1

  override def bodyProducer(chunks: Iterable[Array[Byte]]): Stream[IO, Byte] =
    Stream.chunk(Chunk.concatBytes(chunks.toSeq.map(Chunk.array)))

  override def bodyConsumer(stream: Stream[IO, Byte]): IO[String] =
    stream
      .through(text.utf8Decode)
      .compile
      .foldMonoid
}
