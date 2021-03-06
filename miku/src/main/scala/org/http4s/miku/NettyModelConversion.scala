package org.http4s.miku

import java.net.InetSocketAddress

import cats.effect.{Async, Effect, IO}
import cats.syntax.all._
import com.typesafe.netty.http.{DefaultStreamedHttpResponse, DefaultWebSocketHttpResponse, StreamedHttpRequest}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.{WebSocketFrame => WSFrame, _}
import io.netty.handler.ssl.SslHandler
import fs2.{Chunk, Pull, Stream}
import fs2.interop.reactivestreams._
import org.http4s.Request.Connection
import org.http4s.headers.`Content-Length`
import org.http4s.{HttpVersion => HV, _}
import org.http4s.server.websocket.websocketKey
import org.http4s.websocket.WebSocketContext
import org.http4s.websocket.WebsocketBits._
import org.http4s.util.execution.trampoline
import org.reactivestreams.{Processor, Subscriber, Subscription}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

/** Helpers for converting http4s request/response
  * objects to and from the netty model
  *
  * Adapted from NettyModelConversion.scala
  * in
  * https://github.com/playframework/playframework/blob/master/framework/src/play-netty-server
  *
  */
object NettyModelConversion {

  /** Turn a netty http request into an http4s request
    *
    * @param channel the netty channel
    * @param request the netty http request impl
    * @return Http4s request
    */
  def fromNettyRequest[F[_]](channel: Channel, request: HttpRequest)(
      implicit F: Effect[F]
  ): F[Request[F]] = {
    val connection = createRemoteConnection(channel)
    if (request.decoderResult().isFailure)
      F.raiseError(ParseFailure("Malformed request", "Netty codec parsing unsuccessful"))
    else {
      val requestBody           = convertRequestBody(request)
      val uri: ParseResult[Uri] = Uri.fromString(request.uri())
      val headerBuf             = new ListBuffer[Header]
      request.headers().entries().forEach { entry =>
        headerBuf += Header(entry.getKey, entry.getValue)
      }
      val method: ParseResult[Method] =
        Method.fromString(request.method().name())
      val version: HV = {
        if (request.protocolVersion() == HttpVersion.HTTP_1_1)
          HV.`HTTP/1.1`
        else
          HV.`HTTP/1.0`
      }
      uri.flatMap { u =>
        method.map { m =>
          Request[F](
            m,
            u,
            version,
            Headers(headerBuf.toList),
            requestBody,
            AttributeMap(AttributeEntry(Request.Keys.ConnectionInfo, connection))
          )
        }
      } match { //Micro-optimization: No fold call
        case Right(http4sRequest) => F.pure(http4sRequest)
        case Left(err)            => F.raiseError(err)
      }
    }
  }

  /** Capture a request's connection info from its channel and headers. */
  private def createRemoteConnection(channel: Channel): Connection =
    Connection(
      channel.localAddress().asInstanceOf[InetSocketAddress],
      channel.remoteAddress().asInstanceOf[InetSocketAddress],
      channel.pipeline().get(classOf[SslHandler]) != null
    )

  /** Create the source for the request body
    * Todo: Turn off scalastyle due to non-exhaustive match
    */
  private def convertRequestBody[F[_]](request: HttpRequest)(
      implicit F: Effect[F]
  ): Stream[F, Byte] =
    request match {
      case full: FullHttpRequest =>
        val content = full.content()
        val buffers = content.nioBuffers()
        if (buffers.isEmpty)
          Stream.empty.covary[F]
        else {
          val content = full.content()
          val arr     = new Array[Byte](content.readableBytes())
          content.readBytes(arr)
          content.release()
          Stream
            .chunk(Chunk.bytes(arr))
            .covary[F]
        }
      case streamed: StreamedHttpRequest =>
        new NettySafePublisher(streamed).toStream[F]()(F, trampoline).flatMap(Stream.chunk(_))
    }

  /** Create a Netty streamed response. */
  private def responseToPublisher[F[_]](
      response: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): StreamUnicastPublisher[F, HttpContent] = {
    def go(s: Stream[F, Byte]): Pull[F, HttpContent, Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((chnk, stream)) =>
          Pull.output1[F, HttpContent](chunkToNetty(chnk)) >> go(stream)
        case None =>
          Pull.eval(response.trailerHeaders).flatMap { h =>
            if (h.isEmpty)
              Pull.done
            else {
              val c = new DefaultLastHttpContent()
              h.foreach(header => c.trailingHeaders().add(header.name.toString(), header.value))
              Pull.output1(c) >> Pull.done
            }
          }
      }
    go(response.body).stream.toUnicastPublisher()
  }

  /** Create a Netty response from the result */
  def toNettyResponse[F[_]](
      http4sResponse: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): DefaultHttpResponse = {
    val httpVersion: HttpVersion =
      if (http4sResponse.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else
        HttpVersion.HTTP_1_0

    toNonWSResponse[F](http4sResponse, httpVersion)
  }

  /** Create a Netty response from the result */
  def toNettyResponseWithWebsocket[F[_]](
      httpRequest: Request[F],
      httpResponse: Response[F]
  )(implicit F: Effect[F], ec: ExecutionContext): F[DefaultHttpResponse] = {
    val httpVersion: HttpVersion =
      if (httpResponse.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else
        HttpVersion.HTTP_1_0

    httpResponse.attributes.get(websocketKey[F]) match {
      case None            => F.pure(toNonWSResponse[F](httpResponse, httpVersion))
      case Some(wsContext) => toWSResponse[F](httpRequest, httpResponse, httpVersion, wsContext)
    }
  }

  private def toNonWSResponse[F[_]](httpResponse: Response[F], httpVersion: HttpVersion)(
      implicit F: Effect[F],
      ec: ExecutionContext
  ): DefaultHttpResponse =
    if (httpResponse.status.isEntityAllowed) {
      val publisher = responseToPublisher[F](httpResponse)
      val response =
        new DefaultStreamedHttpResponse(
          httpVersion,
          HttpResponseStatus.valueOf(httpResponse.status.code),
          publisher
        )
      httpResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      response
    } else {
      val response = new DefaultFullHttpResponse(
        httpVersion,
        HttpResponseStatus.valueOf(httpResponse.status.code)
      )
      httpResponse.headers.foreach(h => response.headers().add(h.name.value, h.value))
      if (HttpUtil.isContentLengthSet(response))
        response.headers().remove(`Content-Length`.name.toString())
      response
    }

  private def toWSResponse[F[_]](
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      wsContext: WebSocketContext[F]
  )(
      implicit F: Effect[F],
      ec: ExecutionContext
  ): F[DefaultHttpResponse] =
    if (httpRequest.headers.exists(
          h => h.name.toString.equalsIgnoreCase("Upgrade") && h.value.equalsIgnoreCase("websocket")
        )) {
      val wsProtocol  = if (httpRequest.isSecure.exists(identity)) "wss" else "ws"
      val wsUrl       = s"$wsProtocol://${httpRequest.serverAddr}${httpRequest.pathInfo}"
      val bufferLimit = 65535 //Todo: Configurable. Probably param
      val factory     = new WebSocketServerHandshakerFactory(wsUrl, "*", true, bufferLimit)
      StreamSubscriber[F, WebSocketFrame].flatMap { subscriber =>
        F.delay {
            val processor = new Processor[WSFrame, WSFrame] {
              def onError(t: Throwable): Unit = subscriber.onError(t)

              def onComplete(): Unit = subscriber.onComplete()

              def onNext(t: WSFrame): Unit = subscriber.onNext(nettyWsToHttp4s(t))

              def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

              def subscribe(s: Subscriber[_ >: WSFrame]): Unit =
                wsContext.webSocket.send.map(wsbitsToNetty).toUnicastPublisher().subscribe(s)
            }

            F.runAsync {
                Async.shift[F](ec) >> subscriber.stream
                  .through(wsContext.webSocket.receive)
                  .compile
                  .drain
              }(_ => IO.unit)
              .unsafeRunSync()
            val resp: DefaultHttpResponse =
              new DefaultWebSocketHttpResponse(httpVersion, HttpResponseStatus.OK, processor, factory)
            wsContext.headers.foreach(h => resp.headers().add(h.name.toString(), h.value))
            resp
          }
          .handleErrorWith(_ => wsContext.failureResponse.map(toNonWSResponse[F](_, httpVersion)))
      }
    } else {
      F.pure(toNonWSResponse[F](httpResponse, httpVersion))
    }

  private def wsbitsToNetty(w: WebSocketFrame): WSFrame =
    w match {
      case Text(str, last)    => new TextWebSocketFrame(last, 0, str)
      case Binary(data, last) => new BinaryWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data))
      case Ping(data)         => new PingWebSocketFrame(Unpooled.wrappedBuffer(data))
      case Pong(data)         => new PongWebSocketFrame(Unpooled.wrappedBuffer(data))
      case Continuation(data, last) =>
        new ContinuationWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data))
      case Close(data) => new CloseWebSocketFrame(true, 0, Unpooled.wrappedBuffer(data))
    }

  private def nettyWsToHttp4s(w: WSFrame): WebSocketFrame =
    w match {
      case c: TextWebSocketFrame         => Text(bytebufToArray(c.content()), c.isFinalFragment)
      case c: BinaryWebSocketFrame       => Binary(bytebufToArray(c.content()), c.isFinalFragment)
      case c: PingWebSocketFrame         => Ping(bytebufToArray(c.content()))
      case c: PongWebSocketFrame         => Pong(bytebufToArray(c.content()))
      case c: ContinuationWebSocketFrame => Continuation(bytebufToArray(c.content()), c.isFinalFragment)
      case c: CloseWebSocketFrame        => Close(bytebufToArray(c.content()))
    }

  /** Convert a Chunk to a Netty ByteBuf. */
  private def chunkToNetty(bytes: Chunk[Byte]): HttpContent =
    if (bytes.isEmpty)
      CachedEmpty
    else
      bytes match {
        case c: Chunk.Bytes =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.values, c.offset, c.length))
        case c: Chunk.ByteBuffer =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(c.buf))
        case _ =>
          new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArray))
      }

  private def bytebufToArray(buf: ByteBuf): Array[Byte] = {
    val array = new Array[Byte](buf.readableBytes())
    buf.readBytes(array)
    buf.release()
    array
  }

  private val CachedEmpty: DefaultHttpContent =
    new DefaultHttpContent(Unpooled.EMPTY_BUFFER)

}
