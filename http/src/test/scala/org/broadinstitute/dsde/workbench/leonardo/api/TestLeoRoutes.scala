package org.broadinstitute.dsde.workbench.leonardo.api

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.headers.{HttpCookiePair, `Set-Cookie`}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.broadinstitute.dsde.workbench.google.GoogleStorageDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleIamDAO, MockGoogleProjectDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.mock.BaseFakeGoogleStorage
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData
import org.broadinstitute.dsde.workbench.leonardo.dao.MockWelderDAO
import org.broadinstitute.dsde.workbench.leonardo.db.DbSingleton
import org.broadinstitute.dsde.workbench.leonardo.dns.ClusterDnsCache
import org.broadinstitute.dsde.workbench.leonardo.monitor.NoopActor
import org.broadinstitute.dsde.workbench.leonardo.service.{LeonardoService, MockProxyService, StatusService}
import org.broadinstitute.dsde.workbench.leonardo.util.{BucketHelper, ClusterHelper}
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.scalatest.Matchers

import scala.concurrent.duration._

/**
  * Created by rtitle on 8/15/17.
  */
trait TestLeoRoutes { this: ScalatestRouteTest with Matchers with CommonTestData =>

  implicit val cs = IO.contextShift(executor)
  implicit val timer = IO.timer(executor)
  implicit val unsafeLogger = Slf4jLogger.getLogger[IO]
  val mockGoogleIamDAO = new MockGoogleIamDAO
  val mockGoogleStorageDAO = new MockGoogleStorageDAO
  val mockGoogle2StorageDAO = new BaseFakeGoogleStorage
  val mockGoogleProjectDAO = new MockGoogleProjectDAO
  mockGoogleStorageDAO.buckets += jupyterExtensionUri.bucketName -> Set((jupyterExtensionUri.objectName, new ByteArrayInputStream("foo".getBytes())))
  val mockPetGoogleStorageDAO: String => GoogleStorageDAO = _ => {
    val petMock = new MockGoogleStorageDAO
    petMock.buckets += jupyterUserScriptUri.bucketName -> Set((jupyterUserScriptUri.objectName, new ByteArrayInputStream("foo".getBytes())))
    petMock.buckets += jupyterExtensionUri.bucketName -> Set((jupyterExtensionUri.objectName, new ByteArrayInputStream("foo".getBytes())))
    petMock
  }
  // Route tests don't currently do cluster monitoring, so use NoopActor
  val clusterMonitorSupervisor = system.actorOf(NoopActor.props)
  val bucketHelper = new BucketHelper(dataprocConfig, mockGoogleDataprocDAO, mockGoogleComputeDAO, mockGoogleStorageDAO, mockGoogle2StorageDAO, serviceAccountProvider)
  val clusterHelper = new ClusterHelper(DbSingleton.ref, dataprocConfig, proxyConfig, clusterResourcesConfig, clusterFilesConfig, bucketHelper, mockGoogleDataprocDAO, mockGoogleComputeDAO, mockGoogleIamDAO, mockGoogleProjectDAO, contentSecurityPolicy)
  val leonardoService = new LeonardoService(dataprocConfig, MockWelderDAO, clusterDefaultsConfig, proxyConfig, swaggerConfig, autoFreezeConfig, mockPetGoogleStorageDAO, DbSingleton.ref, whitelistAuthProvider, serviceAccountProvider, bucketHelper, clusterHelper)
  val clusterDnsCache = new ClusterDnsCache(proxyConfig, DbSingleton.ref, dnsCacheConfig)
  val proxyService = new MockProxyService(proxyConfig, mockGoogleDataprocDAO, DbSingleton.ref, whitelistAuthProvider, clusterDnsCache)
  val statusService = new StatusService(mockGoogleDataprocDAO, mockSamDAO, DbSingleton.ref, dataprocConfig, pollInterval = 1.second)
  val timedUserInfo = defaultUserInfo.copy(tokenExpiresIn = tokenAge)
  val leoRoutes = new LeoRoutes(leonardoService, proxyService, statusService, swaggerConfig) with MockUserInfoDirectives {
    override val userInfo: UserInfo = defaultUserInfo
  }
  val timedLeoRoutes = new LeoRoutes(leonardoService, proxyService, statusService, swaggerConfig) with MockUserInfoDirectives {
    override val userInfo: UserInfo = timedUserInfo
  }

  private[api] def validateCookie(setCookie: Option[`Set-Cookie`],
                             expectedCookie: HttpCookiePair = tokenCookie,
                             age: Long = tokenAge): Unit = {
    def roundUpToNearestTen(d: Long) = Math.ceil(d / 10.0) * 10

    setCookie shouldBe 'defined
    val cookie = setCookie.get.cookie
    cookie.name shouldBe expectedCookie.name
    cookie.value shouldBe expectedCookie.value
    cookie.secure shouldBe true
    cookie.maxAge.map(roundUpToNearestTen) shouldBe Some(age) // test execution loses some milliseconds
    cookie.domain shouldBe None
    cookie.path shouldBe Some("/")
  }
}
