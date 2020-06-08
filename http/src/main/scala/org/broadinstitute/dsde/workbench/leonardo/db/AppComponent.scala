package org.broadinstitute.dsde.workbench.leonardo.db

import java.sql.SQLIntegrityConstraintViolationException
import java.time.Instant

import org.broadinstitute.dsde.workbench.leonardo.{
  App,
  AppId,
  AppName,
  AppResources,
  AppSamResourceId,
  AppStatus,
  AppType,
  AuditInfo,
  DiskId,
  KubernetesService,
  LabelMap,
  Namespace,
  NamespaceId,
  NodepoolLeoId,
  PersistentDisk
}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import slick.lifted.Tag
import LeoProfile.api._
import LeoProfile.mappedColumnImplicits._
import org.broadinstitute.dsde.workbench.leonardo.db.LeoProfile.{dummyDate, unmarshalDestroyedDate}

import scala.concurrent.ExecutionContext

final case class AppRecord(id: AppId,
                           nodepoolId: NodepoolLeoId,
                           appType: AppType,
                           appName: AppName,
                           status: AppStatus,
                           samResourceId: AppSamResourceId,
                           creator: WorkbenchEmail,
                           createdDate: Instant,
                           destroyedDate: Instant,
                           dateAccessed: Instant,
                           namespaceId: NamespaceId,
                           diskId: Option[DiskId])

class AppTable(tag: Tag) extends Table[AppRecord](tag, "APP") {
  //unique (appName, destroyedDate)
  def id = column[AppId]("id", O.PrimaryKey, O.AutoInc)
  def nodepoolId = column[NodepoolLeoId]("nodepoolId")
  def appType = column[AppType]("appType", O.Length(254))
  def appName = column[AppName]("appName", O.Length(254))
  def status = column[AppStatus]("status", O.Length(254))
  def samResourceId = column[AppSamResourceId]("samResourceId", O.Length(254))
  def creator = column[WorkbenchEmail]("creator", O.Length(254))
  def createdDate = column[Instant]("createdDate", O.SqlType("TIMESTAMP(6)"))
  def destroyedDate = column[Instant]("destroyedDate", O.SqlType("TIMESTAMP(6)"))
  def dateAccessed = column[Instant]("dateAccessed", O.SqlType("TIMESTAMP(6)"))
  def namespaceId = column[NamespaceId]("namespaceId", O.Length(254))
  def diskId = column[Option[DiskId]]("diskId", O.Length(254))

  def * =
    (
      id,
      nodepoolId,
      appType,
      appName,
      status,
      samResourceId,
      creator,
      createdDate,
      destroyedDate,
      dateAccessed,
      namespaceId,
      diskId
    ) <> (AppRecord.tupled, AppRecord.unapply)
}

object appQuery extends TableQuery(new AppTable(_)) {
  def unmarshalApp(app: AppRecord,
                   sevices: List[KubernetesService],
                   labels: LabelMap,
                   namespace: Namespace,
                   disk: Option[PersistentDisk]): App =
    App(
      app.id,
      app.nodepoolId,
      app.appType,
      app.appName,
      app.status,
      app.samResourceId,
      AuditInfo(
        app.creator,
        app.createdDate,
        unmarshalDestroyedDate(app.destroyedDate),
        app.dateAccessed
      ),
      labels,
      AppResources(
        namespace,
        disk,
        sevices
      ),
      List()
    )

  def save(saveApp: SaveApp)(implicit ec: ExecutionContext): DBIO[App] = {
    val namespaceName = saveApp.app.appResources.namespace.name
    for {
      nodepool <- nodepoolQuery
        .getMinimalById(saveApp.app.nodepoolId)
        .map(
          _.getOrElse(
            throw new SQLIntegrityConstraintViolationException(
              "Apps must be saved with an nodepool ID that exists in the DB. FK_APP_NODEPOOL_ID"
            )
          )
        )
      namespaceId <- namespaceQuery.save(nodepool.clusterId, namespaceName)
      namespace = saveApp.app.appResources.namespace.copy(id = namespaceId)

      diskOpt = saveApp.app.appResources.disk

      record = AppRecord(
        AppId(-1),
        saveApp.app.nodepoolId,
        saveApp.app.appType,
        saveApp.app.appName,
        saveApp.app.status,
        saveApp.app.samResourceId,
        saveApp.app.auditInfo.creator,
        saveApp.app.auditInfo.createdDate,
        saveApp.app.auditInfo.destroyedDate.getOrElse(dummyDate),
        saveApp.app.auditInfo.dateAccessed,
        namespaceId,
        diskOpt.map(_.id)
      )
      appId <- appQuery returning appQuery.map(_.id) += record
      _ <- labelQuery.saveAllForResource(appId.id, LabelResourceType.App, saveApp.app.labels)
      services <- serviceQuery.saveAllForApp(appId, saveApp.app.appResources.services)
    } yield saveApp.app.copy(id = appId, appResources = AppResources(namespace, diskOpt, services))
  }

  def updateStatus(id: AppId, status: AppStatus): DBIO[Int] =
    getByIdQuery(id)
      .map(_.status)
      .update(status)

  def markPendingDeletion(id: AppId): DBIO[Int] =
    updateStatus(id, AppStatus.Deleting)

  def markAsDeleted(id: AppId, now: Instant): DBIO[Int] =
    getByIdQuery(id)
      .map(a => (a.status, a.destroyedDate))
      .update((AppStatus.Deleted, now))

  private[db] def getByIdQuery(id: AppId) =
    appQuery.filter(_.id === id)

  private[db] def findActiveByNameQuery(
    appName: AppName
  ): Query[AppTable, AppRecord, Seq] =
    appQuery
      .filter(_.appName === appName)
      .filter(_.destroyedDate === dummyDate)
}

case class SaveApp(app: App)
