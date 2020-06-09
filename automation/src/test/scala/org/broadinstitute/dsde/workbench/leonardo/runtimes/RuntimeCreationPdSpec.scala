package org.broadinstitute.dsde.workbench.leonardo
package runtimes

import cats.effect.IO
import org.broadinstitute.dsde.workbench.google2.{DiskName, GoogleDiskService, ZoneName}
import org.http4s.{AuthScheme, Credentials}
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.scalatest.{DoNotDiscover, ParallelTestExecution}

//@DoNotDiscover
class RuntimeCreationPdSpec
    extends GPAllocFixtureSpec
    with ParallelTestExecution
    with LeonardoTestUtils
    with GPAllocBeforeAndAfterAll {
  implicit val authTokenForOldApiClient = ronAuthToken
  implicit val auth: Authorization = Authorization(Credentials.Token(AuthScheme.Bearer, ronCreds.makeAuthToken().value))

  val zone = ZoneName("us-central1-a")

  val dependencies = for {
    diskService <- googleDiskService
    httpClient <- LeonardoApiClient.client
  } yield RuntimeCreationPdSpecDependencies(httpClient, diskService)

  "create and attach a persistent disk" in { googleProject =>
    val runtimeName = randomClusterName
    val diskName = DiskName("test-disk-3")
    val runtimeRequest = defaultRuntimeRequest.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.GceWithPdConfig(
          "gce",
          None,
          PersistentDiskRequest(
            diskName.value,
            Some(50),
            None,
            None,
            Map.empty
          )
        )
      )
    )

    withNewRuntime(googleProject, runtimeName, runtimeRequest, deleteRuntimeAfter = false) { runtime =>
      Leonardo.cluster
        .getRuntime(runtime.googleProject, runtime.clusterName)
        .status shouldBe ClusterStatus.Running

      // validate disk still exists after runtime is deleted
      val res = dependencies.use { dep =>
        implicit val client = dep.httpClient
        for {
          _ <- LeonardoApiClient.deleteRuntimeWithWait(googleProject, runtimeName)
          disk <- LeonardoApiClient.getDisk(googleProject, diskName)
          _ <- LeonardoApiClient.deleteDiskWithWait(googleProject, diskName)
          diskAfterDelete <- LeonardoApiClient.getDisk(googleProject, diskName)
        } yield {
          disk.status shouldBe DiskStatus.Ready
          diskAfterDelete.status shouldBe DiskStatus.Deleted
        }
      }
      res.unsafeRunSync()
    }
  }

  "create and attach an existing a persistent disk" in { googleProject =>
    val runtimeName = randomClusterName
    val diskName = DiskName("test-disk-1")
    val diskSize = DiskSize(30)

    val res = dependencies.use { dep =>
      implicit val client = dep.httpClient

      val runtimeRequest = defaultRuntimeRequest.copy(
        runtimeConfig = Some(
          RuntimeConfigRequest.GceWithPdConfig(
            "gce",
            None,
            PersistentDiskRequest(
              diskName.value,
              Some(30),
              None,
              None,
              Map.empty
            )
          )
        )
      )

      for {
        _ <- LeonardoApiClient.createDiskWithWait(googleProject, diskName)
        _ <- IO(withNewRuntime(googleProject, runtimeName, runtimeRequest, deleteRuntimeAfter = false) { runtime =>
          Leonardo.cluster
            .getRuntime(runtime.googleProject, runtime.clusterName)
            .status shouldBe ClusterStatus.Running
        })
        runtime <- LeonardoApiClient.getRuntime(googleProject, runtimeName)
        _ <- LeonardoApiClient.deleteDiskWithWait(googleProject, diskName)
      } yield {
        runtime.diskConfig.map(_.name) shouldBe Some(diskName)
        runtime.diskConfig.map(_.size) shouldBe Some(diskSize)
      }
    }

    res.unsafeRunSync()
  }
}

final case class RuntimeCreationPdSpecDependencies(httpClient: Client[IO], googleDiskService: GoogleDiskService[IO])
