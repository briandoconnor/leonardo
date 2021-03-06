package org.broadinstitute.dsde.workbench.leonardo
package http
package api

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`, _}
import akka.http.scaladsl.model.ws.{TextMessage, WebSocketRequest}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.broadinstitute.dsde.workbench.leonardo.db.TestComponent
import org.broadinstitute.dsde.workbench.leonardo.http.service.{MockProxyService, TestProxy}
import org.broadinstitute.dsde.workbench.leonardo.http.service.TestProxy.Data
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec}

import scala.collection.immutable
import scala.concurrent.duration._
import CommonTestData._
import cats.effect.IO
import fs2.concurrent.InspectableQueue
import org.broadinstitute.dsde.workbench.leonardo.config.ProxyConfig
import org.broadinstitute.dsde.workbench.leonardo.monitor.UpdateDateAccessMessage

/**
 * Created by rtitle on 8/10/17.
 */
class ProxyRoutesSpec
    extends FlatSpec
    with BeforeAndAfterAll
    with BeforeAndAfter
    with ScalatestRouteTest
    with ScalaFutures
    with LeonardoTestSuite
    with TestProxy
    with TestComponent
    with GcsPathUtils
    with TestLeoRoutes {
  implicit val patience = PatienceConfig(timeout = scaled(Span(30, Seconds)))
  implicit val routeTimeout = RouteTestTimeout(10 seconds)
  override def proxyConfig: ProxyConfig = CommonTestData.proxyConfig

  val clusterName = "test"
  val googleProject = "dsp-leo-test"
  val unauthorizedTokenCookie = HttpCookiePair("LeoToken", "unauthorized")
  val expiredTokenCookie = HttpCookiePair("LeoToken", "expired")

  val routeTest = this

  override def beforeAll(): Unit = {
    super.beforeAll()
    startProxyServer()
  }

  override def afterAll(): Unit = {
    shutdownProxyServer()
    super.afterAll()
  }

  before {
    proxyService.googleTokenCache.invalidateAll()
    proxyService.runtimeSamResourceCache.put((GoogleProject(googleProject), RuntimeName(clusterName)),
                                             Some(runtimeSamResource))
  }

  val prefix = "proxy"

  "ProxyRoutes" should s"listen on /$prefix/{project}/{name}/... ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      validateCors()
    }
    Get(s"/$prefix/$googleProject/$clusterName/foo").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      validateCors()
    }
    val newName = "aDifferentClusterName"
    proxyService.runtimeSamResourceCache.put((GoogleProject(googleProject), RuntimeName(newName)),
                                             Some(runtimeSamResource))
    Get(s"/$prefix/$googleProject/$newName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      validateCors()
    }

    Get(s"/$prefix/").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      handled shouldBe false
    }
    Get(s"/api/$prefix").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      handled shouldBe false
    }
  }

  it should s"404 for non-existent clusters ($prefix)" in {
    val newName = "aDifferentClusterName"
    // should 404 since the internal id cannot be looked up
    Get(s"/$prefix/$googleProject/$newName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    // should still 404 even if a cache entry is present
    proxyService.runtimeSamResourceCache.put((GoogleProject(googleProject), RuntimeName(newName)),
                                             Some(runtimeSamResource))
    Get(s"/$prefix/$googleProject/$newName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should s"set CORS headers in proxy requests ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName")
      .addHeader(Cookie(tokenCookie))
      .addHeader(Origin("http://example.com")) ~> proxyRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      validateCors(origin = Some("http://example.com"))
    }
  }

  it should s"reject non-cookied requests ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName") ~> httpRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Unauthorized
    }
  }

  it should s"404 when using a non-white-listed user ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName")
      .addHeader(Cookie(unauthorizedTokenCookie)) ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should s"401 when using an expired token ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(expiredTokenCookie)) ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.Unauthorized
    }
  }

  it should s"pass through paths ($prefix)" in {
    val queue = InspectableQueue.bounded[IO, UpdateDateAccessMessage](100).unsafeRunSync
    val proxyService =
      new MockProxyService(proxyConfig, mockGoogleDataprocDAO, whitelistAuthProvider, clusterDnsCache, Some(queue))
    proxyService.runtimeSamResourceCache.put((GoogleProject(googleProject), RuntimeName(clusterName)),
                                             Some(runtimeSamResource))
    val proxyRoutes = new ProxyRoutes(proxyService, corsSupport)
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Data].path shouldEqual s"/$prefix/$googleProject/$clusterName"
      val message = queue.tryDequeue1.unsafeRunSync().get
      message.googleProject.value shouldBe googleProject
      message.runtimeName.asString shouldBe clusterName
    }
  }

  it should s"pass through query string params ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[Data].qs shouldBe None
    }
    Get(s"/$prefix/$googleProject/$clusterName?foo=bar&baz=biz")
      .addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[Data].qs shouldEqual Some("foo=bar&baz=biz")
    }
  }

  it should s"pass through http methods ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[Data].method shouldBe "GET"
    }
    Post(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[Data].method shouldBe "POST"
    }
    Put(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[Data].method shouldBe "PUT"
    }
  }

  it should s"pass through headers ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName")
      .addHeader(Cookie(tokenCookie))
      .addHeader(RawHeader("foo", "bar"))
      .addHeader(RawHeader("baz", "biz")) ~> proxyRoutes.route ~> check {
      responseAs[Data].headers.toList should contain allElementsOf Map("foo" -> "bar", "baz" -> "biz").toList
    }
  }

  it should s"remove utf-8'' from content-disposition header filenames ($prefix)" in {
    // The TestProxy adds the Content-Disposition header to the response, we can't do it from here
    Get(s"/$prefix/$googleProject/$clusterName/content-disposition-test")
      .addHeader(Cookie(tokenCookie)) ~> proxyRoutes.route ~> check {
      responseAs[HttpResponse].headers should contain(
        `Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> "notebook.ipynb"))
      )
    }
  }

  it should s"proxy websockets ($prefix)" in withWebsocketProxy {
    // See comments in ProxyService.handleHttpRequest for more high-level information on Flows, Sources, and Sinks.

    // Sink for incoming data from the WebSocket
    val incoming = Sink.head[String]

    // Source outgoing data over the WebSocket
    val outgoing = Source.single(TextMessage("Leonardo"))

    // Flow to hit the proxy server
    val webSocketFlow = Http()
      .webSocketClientFlow(
        WebSocketRequest(Uri(s"ws://localhost:9000/$prefix/$googleProject/$clusterName/websocket"),
                         immutable.Seq(Cookie(tokenCookie)))
      )
      .map {
        case m: TextMessage.Strict => m.text
        case _                     => throw new IllegalArgumentException("ProxyRoutesSpec only supports strict messages")
      }

    // Glue together the source, sink, and flow. This materializes the Flow and actually initiates the HTTP request.
    // Returns a tuple of:
    //  - `upgradeResponse` is a Future[WebSocketUpgradeResponse] that completes or fails when the connection succeeds or fails.
    //  - `result` is a Future[String] with the stream completion from the incoming sink.
    val (upgradeResponse, result) =
      outgoing
        .viaMat(webSocketFlow)(Keep.right)
        .toMat(incoming)(Keep.both)
        .run()

    // The connection future should have returned the HTTP 101 status code
    upgradeResponse.futureValue.response.status shouldBe StatusCodes.SwitchingProtocols
    // The stream completion future should have greeted Leonardo
    result.futureValue shouldBe "Hello Leonardo!"
  }

  "setCookie" should s"set a cookie given a valid Authorization header ($prefix)" in {
    // cache should not initially contain the token
    proxyService.googleTokenCache.asMap().containsKey(tokenCookie.value) shouldBe false
    // login request with Authorization header should succeed and return a Set-Cookie header
    Get(s"/$prefix/$googleProject/$clusterName/setCookie")
      .addHeader(Authorization(OAuth2BearerToken(tokenCookie.value)))
      .addHeader(Origin("http://example.com")) ~> proxyRoutes.route ~> check {
      validateRawCookie(setCookie = header("Set-Cookie"), age = 3600)
      status shouldEqual StatusCodes.NoContent
      validateCors(origin = Some("http://example.com"))
    }

    // cache should now contain the token
    proxyService.googleTokenCache.asMap().containsKey(tokenCookie.value) shouldBe true
  }

  it should s"handle preflight OPTIONS requests ($prefix)" in {
    Options(s"/$prefix/$googleProject/$clusterName/setCookie")
      .addHeader(Authorization(OAuth2BearerToken(tokenCookie.value)))
      .addHeader(Origin("http://example.com")) ~> proxyRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NoContent
      header[`Set-Cookie`] shouldBe None
      validateCors(origin = Some("http://example.com"), optionsRequest = true)
    }
  }

  it should s"401 when not given an Authorization header ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName/setCookie")
      .addHeader(Origin("http://example.com")) ~> httpRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Unauthorized
    }
  }

  it should s"404 when using a non-white-listed user ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName/setCookie")
      .addHeader(Authorization(OAuth2BearerToken(unauthorizedTokenCookie.value)))
      .addHeader(Origin("http://example.com")) ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should s"401 when using an expired token ($prefix)" in {
    Get(s"/$prefix/$googleProject/$clusterName")
      .addHeader(Authorization(OAuth2BearerToken(expiredTokenCookie.value)))
      .addHeader(Origin("http://example.com")) ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.Unauthorized
    }
  }

  "invalidateToken" should s"remove a token from cache ($prefix)" in {
    // cache should not initially contain the token
    proxyService.googleTokenCache.asMap().containsKey(tokenCookie.value) shouldBe false

    // regular request with a cookie should succeed but NOT return a Set-Cookie header
    Get(s"/$prefix/$googleProject/$clusterName").addHeader(Cookie(tokenCookie)) ~> httpRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      header[`Set-Cookie`] shouldBe None
    }

    // cache should now contain the token
    proxyService.googleTokenCache.asMap().containsKey(tokenCookie.value) shouldBe true

    // log out, passing a cookie
    Get(s"/$prefix/invalidateToken").addHeader(Cookie(tokenCookie)) ~> httpRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      header[`Set-Cookie`] shouldBe None
    }

    // cache should not contain the token
    proxyService.googleTokenCache.asMap().containsKey(tokenCookie.value) shouldBe false
  }

  /**
   * Akka-http TestKit doesn't seem to support websocket servers.
   * So for websocket tests, manually create a server binding on port 9000 for the proxy.
   */
  def withWebsocketProxy[T](testCode: => T): T = {
    val bindingFuture = Http().bindAndHandle(proxyRoutes.route, "0.0.0.0", 9000)
    try {
      testCode
    } finally {
      bindingFuture.flatMap(_.unbind())
    }
  }

  private def validateCors(origin: Option[String] = None, optionsRequest: Boolean = false): Unit = {
    // Issue 272: CORS headers should not be double set
    headers.count(_.is(`Access-Control-Allow-Origin`.lowercaseName)) shouldBe 1
    header[`Access-Control-Allow-Origin`] shouldBe origin
      .map(`Access-Control-Allow-Origin`(_))
      .orElse(Some(`Access-Control-Allow-Origin`.*))
    header[`Access-Control-Allow-Credentials`] shouldBe Some(`Access-Control-Allow-Credentials`(true))
    header[`Access-Control-Allow-Headers`] shouldBe Some(
      `Access-Control-Allow-Headers`("Authorization", "Content-Type", "Accept", "Origin", "X-App-Id")
    )
    header[`Access-Control-Max-Age`] shouldBe Some(`Access-Control-Max-Age`(1728000))
    header[`Access-Control-Allow-Methods`] shouldBe (
      if (optionsRequest) Some(`Access-Control-Allow-Methods`(OPTIONS, POST, PUT, GET, DELETE, HEAD, PATCH))
      else None
    )
    header("Content-Security-Policy") shouldBe Some(RawHeader("Content-Security-Policy", contentSecurityPolicy))
  }
}
