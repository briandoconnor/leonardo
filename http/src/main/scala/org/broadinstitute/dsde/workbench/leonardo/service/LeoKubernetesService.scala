package org.broadinstitute.dsde.workbench.leonardo.service

import cats.mtl.ApplicativeAsk
import org.broadinstitute.dsde.workbench.leonardo.{AppContext, AppName}
import org.broadinstitute.dsde.workbench.leonardo.http.service.{CreateAppRequest, GetAppResponse, ListAppResponse}
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

trait LeoKubernetesService[F[_]] {

  def createApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName,
    req: CreateAppRequest
  )(
    implicit as: ApplicativeAsk[F, AppContext]
  ): F[Unit]

  def getApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName
  )(
    implicit as: ApplicativeAsk[F, AppContext]
  ): F[GetAppResponse]

  def listApp(
    userInfo: UserInfo,
    googleProject: Option[GoogleProject],
    params: Map[String, String]
  ): F[Vector[ListAppResponse]]

  def deleteApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: ApplicativeAsk[F, AppContext]
  ): F[Unit]
}
