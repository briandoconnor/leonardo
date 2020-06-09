package org.broadinstitute.dsde.workbench.leonardo

import cats.implicits._
import cats.effect.{IO, Resource, Timer}
import org.broadinstitute.dsde.workbench.DoneCheckable
import org.broadinstitute.dsde.workbench.google2.{streamFUntilDone, DiskName}
import org.broadinstitute.dsde.workbench.leonardo.http.CreateDiskRequest
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.util.ExecutionContexts
import org.http4s.client.middleware.Logger
import org.http4s.client.{blaze, Client}
import org.http4s.headers.Authorization
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.broadinstitute.dsde.workbench.leonardo.http.DiskRoutesTestJsonCodec._
import scala.concurrent.duration._
import ApiJsonDecoder._
import org.http4s._

import scala.concurrent.ExecutionContext.global

object LeonardoApiClient {
  implicit def http4sBody[A](body: A)(implicit encoder: EntityEncoder[IO, A]): EntityBody[IO] =
    encoder.toEntity(body).body
  implicit val cs = IO.contextShift(global)
  // Once a runtime is deleted, leonardo returns 404 for getRuntime API call
  implicit def eitherDoneCheckable[A]: DoneCheckable[Either[Throwable, A]] = (op: Either[Throwable, A]) => op.isLeft

  implicit def getDiskDoneCheckable[A]: DoneCheckable[GetPersistentDiskResponse] =
    (op: GetPersistentDiskResponse) => op.status == DiskStatus.Ready

  val client: Resource[IO, Client[IO]] = for {
    blockingEc <- ExecutionContexts.cachedThreadPool[IO]
    client <- blaze.BlazeClientBuilder[IO](blockingEc).resource
  } yield Logger[IO](logHeaders = false, logBody = true)(client)

  def createDisk(
    googleProject: GoogleProject,
    diskName: DiskName
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    client
      .successful(
        Request[IO](
          method = Method.POST,
          headers = Headers.of(authHeader),
          uri = Uri
            .unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
            .withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}"),
          body = CreateDiskRequest(
            Map.empty,
            None,
            None,
            None
          )
        )
      )
      .flatMap { success =>
        if (success)
          IO.unit
        else IO.raiseError(new Exception(s"Fail to create disk ${googleProject.value}/${diskName.value}"))
      }

  def createDiskWithWait(googleProject: GoogleProject, diskName: DiskName)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- createDisk(googleProject, diskName)
      ioa = getDisk(googleProject, diskName)
      _ <- streamFUntilDone(ioa, 5, 5 seconds).compile.lastOrError
    } yield ()

  def getDisk(
    googleProject: GoogleProject,
    diskName: DiskName
  )(implicit client: Client[IO], authHeader: Authorization): IO[GetPersistentDiskResponse] =
    client.expectOr[GetPersistentDiskResponse](
      Request[IO](
        method = Method.GET,
        headers = Headers.of(authHeader),
        uri = Uri
          .unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
          .withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}")
      )
    )(onError)

  def getRuntime(
    googleProject: GoogleProject,
    runtimeName: RuntimeName
  )(implicit client: Client[IO], authHeader: Authorization): IO[GetRuntimeResponseCopy] =
    client.expect[GetRuntimeResponseCopy](
      Request[IO](
        method = Method.GET,
        headers = Headers.of(authHeader),
        uri = Uri
          .unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
          .withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}")
      )
    )

  def deleteRuntime(googleProject: GoogleProject, runtimeName: RuntimeName)(implicit client: Client[IO],
                                                                            authHeader: Authorization): IO[Unit] =
    client
      .successful(
        Request[IO](
          method = Method.DELETE,
          headers = Headers.of(authHeader),
          uri = Uri
            .unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
            .withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}")
        )
      )
      .flatMap { success =>
        if (success)
          IO.unit
        else IO.raiseError(new Exception(s"Fail to delete runtime ${googleProject.value}/${runtimeName.asString}"))
      }

  def deleteRuntimeWithWait(googleProject: GoogleProject, runtimeName: RuntimeName)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- deleteRuntime(googleProject, runtimeName)
      ioa = getRuntime(googleProject, runtimeName).attempt
      _ <- streamFUntilDone(ioa, 10, 10 seconds).compile.lastOrError
    } yield ()

  def deleteDisk(googleProject: GoogleProject, diskName: DiskName)(implicit client: Client[IO],
                                                                   authHeader: Authorization): IO[Unit] =
    client
      .successful(
        Request[IO](
          method = Method.DELETE,
          headers = Headers.of(authHeader),
          uri = Uri
            .unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
            .withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}")
        )
      )
      .void

  def deleteDiskWithWait(googleProject: GoogleProject, diskName: DiskName)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- deleteDisk(googleProject, diskName)
      ioa = getDisk(googleProject, diskName).attempt
      _ <- streamFUntilDone(ioa, 5, 5 seconds).compile.lastOrError
    } yield ()

  private def onError(response: Response[IO]): IO[Throwable] =
    for {
      body <- response.bodyAsText(Charset.`UTF-8`).compile.foldMonoid
    } yield new Exception(body)
}
