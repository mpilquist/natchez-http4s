// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s.syntax

import cats.~>
import cats.data.{ Kleisli, OptionT }
import cats.data.Kleisli.applyK
import cats.effect.MonadCancel
import cats.implicits._
import natchez.{ EntryPoint, Kernel, Span }
import org.http4s.HttpRoutes
import cats.effect.Resource
import natchez.TraceValue
import cats.Monad
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

/**
 * @define excludedHeaders
 *         All headers except security (Authorization, Cookie, Set-Cookie)
 *         and payload (Content-Length, ContentType, Content-Range, Trailer, Transfer-Encoding)
 *         are passed to Kernel by default.
 *
 * @define isKernelHeader should an HTTP header be passed to Kernel or not
 */
trait EntryPointOps[F[_]] { outer =>

  def self: EntryPoint[F]

  /**
   * Given an entry point and HTTP Routes in Kleisli[F, Span[F], *] return routes in F. A new span
   * is created with the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def liftT(
    routes: HttpRoutes[Kleisli[F, Span[F], *]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name)
  )(implicit ev: MonadCancel[F, Throwable]): HttpRoutes[F] =
    Kleisli { req =>
      val kernelHeaders = req.headers.headers
        .collect {
          case header if isKernelHeader(header.name) => header.name.toString -> header.value
        }
        .toMap

      val kernel = Kernel(kernelHeaders)
      val spanR  = self.continueOrElseRoot(req.uri.path.toString, kernel)
      OptionT {
        spanR.use { span =>
          routes.run(req.mapK(lift)).mapK(applyK(span)).map(_.mapK(applyK(span))).value
        }
      }
    }

  /**
   * Lift an `HttpRoutes`-yielding resource that consumes `Span`s into the bare effect. We do this
   * by ignoring any tracing that happens during allocation and freeing of the `HttpRoutes`
   * resource. The reasoning is that such a resource typically lives for the lifetime of the
   * application and it's of little use to keep a span open that long.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def liftR(
    routes: Resource[Kleisli[F, Span[F], *], HttpRoutes[Kleisli[F, Span[F], *]]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name)
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, HttpRoutes[F]] =
    routes.map(liftT(_, isKernelHeader)).mapK(lower)

  /**
   * Given an entry point and a function from `WebSocketBuilder2` to HTTP Routes in
   * Kleisli[F, Span[F], *] return a function from `WebSocketBuilder2` to routes in F. A new span
   * is created with the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def wsLiftT(
    routes: WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name)
  )(implicit ev: MonadCancel[F, Throwable]): WebSocketBuilder2[F] => HttpRoutes[F] = wsb =>
    liftT(routes(wsb.imapK(lift)(lower)), isKernelHeader)

  /**
   * Lift a `WebSocketBuilder2 => HttpRoutes`-yielding resource that consumes `Span`s into the bare
   * effect. We do this by ignoring any tracing that happens during allocation and freeing of the
   * `HttpRoutes` resource. The reasoning is that such a resource typically lives for the lifetime
   * of the application and it's of little use to keep a span open that long.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def wsLiftR(
    routes: Resource[Kleisli[F, Span[F], *], WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name)
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
    routes.map(wsLiftT(_, isKernelHeader)).mapK(lower)

  private val lift: F ~> Kleisli[F, Span[F], *] =
    Kleisli.liftK

  private def lower(implicit ev: Monad[F]): Kleisli[F, Span[F], *] ~> F =
    new (Kleisli[F, Span[F], *] ~> F) {
      def apply[A](fa: Kleisli[F, Span[F], A]) =
        fa.run(dummySpan)
    }

  private def dummySpan(implicit ev: Monad[F]): Span[F] =
    new Span[F] {
      val kernel = Kernel(Map.empty).pure[F]
      def put(fields: (String, TraceValue)*) = Monad[F].unit
      def span(name: String) = Monad[Resource[F, *]].pure(this)
      def traceId = Monad[F].pure(None)
      def traceUri = Monad[F].pure(None)
      def spanId = Monad[F].pure(None)
    }

}

object EntryPointOps {
  val ExcludedHeaders: Set[CIString] = {
    import org.http4s.headers._
    import org.typelevel.ci._

    val payload = Set(
      `Content-Length`.name,
      ci"Content-Type",
      `Content-Range`.name,
      ci"Trailer",
      `Transfer-Encoding`.name,
    )

    val security = Set(
      Authorization.name,
      Cookie.name,
      `Set-Cookie`.name,
    )

    payload ++ security
  }
}

trait ToEntryPointOps {

  implicit def toEntryPointOps[F[_]](ep: EntryPoint[F]): EntryPointOps[F] =
    new EntryPointOps[F] {
      val self = ep
    }

}

object entrypoint extends ToEntryPointOps