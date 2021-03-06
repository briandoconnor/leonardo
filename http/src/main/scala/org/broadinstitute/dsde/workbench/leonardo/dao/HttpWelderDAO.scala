package org.broadinstitute.dsde.workbench.leonardo
package dao

import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.leonardo.dns.ClusterDnsCache
import org.broadinstitute.dsde.workbench.leonardo.dns.ClusterDnsCache._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}

class HttpWelderDAO[F[_]: Concurrent: Timer: ContextShift: Logger](
  val clusterDnsCache: ClusterDnsCache[F],
  client: Client[F]
)(
  implicit metrics: OpenTelemetryMetrics[F]
) extends WelderDAO[F] {

  def flushCache(googleProject: GoogleProject, runtimeName: RuntimeName): F[Unit] =
    for {
      host <- Proxy.getTargetHost(clusterDnsCache, googleProject, runtimeName)
      res <- host match {
        case HostReady(targetHost) =>
          client.successful(
            Request[F](
              method = Method.POST,
              uri = Uri.unsafeFromString(
                s"https://${targetHost.toString}/proxy/${googleProject.value}/${runtimeName.asString}/welder/cache/flush"
              )
            )
          )
        case x =>
          Logger[F]
            .error(
              s"fail to get target host name for welder for ${googleProject.value}/${runtimeName.asString} when trying to flush cache. Host status ${x}"
            )
            .as(false)
      }
      _ <- if (res)
        metrics.incrementCounter("welder/flushcache", tags = Map("result" -> "success"))
      else
        metrics.incrementCounter("welder/flushcache", tags = Map("result" -> "failure"))
    } yield ()

  def isProxyAvailable(googleProject: GoogleProject, runtimeName: RuntimeName): F[Boolean] =
    for {
      host <- Proxy.getTargetHost(clusterDnsCache, googleProject, runtimeName)
      res <- host match {
        case HostReady(targetHost) =>
          client
            .successful(
              Request[F](
                method = Method.GET,
                uri = Uri.unsafeFromString(
                  s"https://${targetHost.toString}/proxy/${googleProject.value}/${runtimeName.asString}/welder/status"
                )
              )
            )
            .handleError(_ => false)
        case x =>
          Logger[F]
            .error(
              s"fail to get target host name for welder for ${googleProject.value}/${runtimeName.asString}. Host status ${x}"
            )
            .as(false)
      }
      _ <- if (res) {
        metrics.incrementCounter("welder/status", tags = Map("result" -> "success"))
      } else
        metrics.incrementCounter("welder/status", tags = Map("result" -> "failure"))
    } yield res
}

trait WelderDAO[F[_]] {
  def flushCache(googleProject: GoogleProject, runtimeName: RuntimeName): F[Unit]
  def isProxyAvailable(googleProject: GoogleProject, runtimeName: RuntimeName): F[Boolean]
}
