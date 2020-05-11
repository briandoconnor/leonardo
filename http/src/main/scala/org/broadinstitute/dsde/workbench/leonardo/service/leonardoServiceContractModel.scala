package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.net.URL
import java.time.Instant

import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.ServiceName
import org.broadinstitute.dsde.workbench.leonardo.SamResource.RuntimeSamResource
import org.broadinstitute.dsde.workbench.leonardo.http.api.{DiskConfig, DiskConfigRequest}
import org.broadinstitute.dsde.workbench.google2.{DiskName, MachineTypeName}
import org.broadinstitute.dsde.workbench.leonardo.db.GetAppResult
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.{GcsPathFormat => _}
import org.broadinstitute.dsde.workbench.model.google.{GcsPath, GoogleProject}

/** Runtime configuration in the createRuntime request */
sealed trait RuntimeConfigRequest extends Product with Serializable {
  def cloudService: CloudService
}
object RuntimeConfigRequest {
  final case class GceConfig(
    machineType: Option[MachineTypeName],
    // diskSize deprecated in favor of CreateRuntime2Request.diskConfig
    diskSize: Option[DiskSize]
  ) extends RuntimeConfigRequest {
    val cloudService: CloudService = CloudService.GCE

    def toRuntimeConfigGceConfig(default: RuntimeConfig.GceConfig): RuntimeConfig.GceConfig = {
      val minimumDiskSize = 10
      val diskSizeFinal = math.max(minimumDiskSize, diskSize.getOrElse(default.diskSize).gb)
      RuntimeConfig.GceConfig(machineType.getOrElse(default.machineType), DiskSize(diskSizeFinal))
    }
  }

  final case class DataprocConfig(numberOfWorkers: Option[Int],
                                  masterMachineType: Option[MachineTypeName],
                                  masterDiskSize: Option[DiskSize], //min 10
                                  // worker settings are None when numberOfWorkers is 0
                                  workerMachineType: Option[MachineTypeName] = None,
                                  workerDiskSize: Option[DiskSize] = None, //min 10
                                  numberOfWorkerLocalSSDs: Option[Int] = None, //min 0 max 8
                                  numberOfPreemptibleWorkers: Option[Int] = None,
                                  properties: Map[String, String])
      extends RuntimeConfigRequest {
    val cloudService: CloudService = CloudService.Dataproc

    def toRuntimeConfigDataprocConfig(default: RuntimeConfig.DataprocConfig): RuntimeConfig.DataprocConfig = {
      val minimumDiskSize = 10
      val masterDiskSizeFinal = math.max(minimumDiskSize, masterDiskSize.getOrElse(default.masterDiskSize).gb)
      numberOfWorkers match {
        case None | Some(0) =>
          RuntimeConfig.DataprocConfig(
            0,
            masterMachineType.getOrElse(default.masterMachineType),
            DiskSize(masterDiskSizeFinal),
            None,
            None,
            None,
            None,
            properties
          )
        case Some(numWorkers) =>
          val wds = workerDiskSize.orElse(default.workerDiskSize)
          RuntimeConfig.DataprocConfig(
            numWorkers,
            masterMachineType.getOrElse(default.masterMachineType),
            DiskSize(masterDiskSizeFinal),
            workerMachineType.orElse(default.workerMachineType),
            wds.map(s => DiskSize(math.max(minimumDiskSize, s.gb))),
            numberOfWorkerLocalSSDs.orElse(default.numberOfWorkerLocalSSDs),
            numberOfPreemptibleWorkers.orElse(default.numberOfPreemptibleWorkers),
            properties
          )
      }
    }
  }
}

/** The createRuntime request itself */
final case class CreateRuntimeRequest(labels: LabelMap = Map.empty,
                                      jupyterUserScriptUri: Option[UserScriptPath] = None,
                                      jupyterStartUserScriptUri: Option[UserScriptPath] = None,
                                      runtimeConfig: Option[RuntimeConfigRequest] = None,
                                      stopAfterCreation: Option[Boolean] = None,
                                      allowStop: Boolean = false,
                                      userJupyterExtensionConfig: Option[UserJupyterExtensionConfig] = None,
                                      autopause: Option[Boolean] = None,
                                      autopauseThreshold: Option[Int] = None,
                                      defaultClientId: Option[String] = None,
                                      jupyterDockerImage: Option[ContainerImage] = None,
                                      toolDockerImage: Option[ContainerImage] = None,
                                      welderDockerImage: Option[ContainerImage] = None,
                                      scopes: Set[String] = Set.empty,
                                      enableWelder: Option[Boolean] = None,
                                      customClusterEnvironmentVariables: Map[String, String] = Map.empty)

object CreateRuntimeRequest {
  def toRuntime(request: CreateRuntimeRequest,
                samResource: RuntimeSamResource,
                userEmail: WorkbenchEmail,
                runtimeName: RuntimeName,
                googleProject: GoogleProject,
                serviceAccountInfo: WorkbenchEmail,
                proxyUrlBase: String,
                autopauseThreshold: Int,
                scopes: Set[String],
                runtimeImages: Set[RuntimeImage],
                timestamp: Instant): Runtime =
    Runtime(
      id = -1,
      samResource = samResource,
      runtimeName = runtimeName,
      googleProject = googleProject,
      serviceAccount = serviceAccountInfo,
      asyncRuntimeFields = None,
      auditInfo = AuditInfo(userEmail, timestamp, None, timestamp),
      kernelFoundBusyDate = None,
      proxyUrl = Runtime.getProxyUrl(proxyUrlBase, googleProject, runtimeName, runtimeImages, request.labels),
      status = RuntimeStatus.Creating,
      labels = request.labels,
      jupyterUserScriptUri = request.jupyterUserScriptUri,
      jupyterStartUserScriptUri = request.jupyterStartUserScriptUri,
      errors = List.empty,
      dataprocInstances = Set.empty,
      userJupyterExtensionConfig = request.userJupyterExtensionConfig,
      autopauseThreshold = autopauseThreshold,
      defaultClientId = request.defaultClientId,
      stopAfterCreation = request.stopAfterCreation.getOrElse(false),
      allowStop = request.allowStop,
      runtimeImages = runtimeImages,
      scopes = scopes,
      welderEnabled = request.enableWelder.getOrElse(false),
      customEnvironmentVariables = request.customClusterEnvironmentVariables,
      runtimeConfigId = RuntimeConfigId(-1),
      patchInProgress = false,
      None
    )
}

// Currently, CreateRuntimeResponse has exactly the same fields as GetRuntimeResponse, but going forward, when we can,
// we should deprecate and remove some of fields for createRuntime request
final case class CreateRuntimeResponse(id: Long,
                                       samResource: RuntimeSamResource,
                                       clusterName: RuntimeName,
                                       googleProject: GoogleProject,
                                       serviceAccountInfo: WorkbenchEmail,
                                       asyncRuntimeFields: Option[AsyncRuntimeFields],
                                       auditInfo: AuditInfo,
                                       kernelFoundBusyDate: Option[Instant],
                                       runtimeConfig: RuntimeConfig,
                                       clusterUrl: URL,
                                       status: RuntimeStatus,
                                       labels: LabelMap,
                                       jupyterExtensionUri: Option[GcsPath],
                                       jupyterUserScriptUri: Option[UserScriptPath],
                                       jupyterStartUserScriptUri: Option[UserScriptPath],
                                       errors: List[RuntimeError],
                                       dataprocInstances: Set[DataprocInstance],
                                       userJupyterExtensionConfig: Option[UserJupyterExtensionConfig],
                                       autopauseThreshold: Int,
                                       defaultClientId: Option[String],
                                       stopAfterCreation: Boolean,
                                       clusterImages: Set[RuntimeImage],
                                       scopes: Set[String],
                                       welderEnabled: Boolean,
                                       patchInProgress: Boolean,
                                       customClusterEnvironmentVariables: Map[String, String])

object CreateRuntimeResponse {
  def fromRuntime(runtime: Runtime, runtimeConfig: RuntimeConfig) = CreateRuntimeResponse(
    runtime.id,
    runtime.samResource,
    runtime.runtimeName,
    runtime.googleProject,
    runtime.serviceAccount,
    runtime.asyncRuntimeFields,
    runtime.auditInfo,
    runtime.kernelFoundBusyDate,
    runtimeConfig,
    runtime.proxyUrl,
    runtime.status,
    runtime.labels,
    None,
    runtime.jupyterUserScriptUri,
    runtime.jupyterStartUserScriptUri,
    runtime.errors,
    runtime.dataprocInstances,
    runtime.userJupyterExtensionConfig,
    runtime.autopauseThreshold,
    runtime.defaultClientId,
    runtime.stopAfterCreation,
    runtime.runtimeImages,
    runtime.scopes,
    runtime.welderEnabled,
    runtime.patchInProgress,
    runtime.customEnvironmentVariables
  )
}

final case class ListRuntimeResponse(id: Long,
                                     samResource: RuntimeSamResource,
                                     clusterName: RuntimeName,
                                     googleProject: GoogleProject,
                                     serviceAccountInfo: WorkbenchEmail,
                                     asyncRuntimeFields: Option[AsyncRuntimeFields],
                                     auditInfo: AuditInfo,
                                     kernelFoundBusyDate: Option[Instant],
                                     machineConfig: RuntimeConfig,
                                     clusterUrl: URL,
                                     status: RuntimeStatus,
                                     labels: LabelMap,
                                     jupyterUserScriptUri: Option[UserScriptPath],
                                     dataprocInstances: Set[DataprocInstance],
                                     autopauseThreshold: Int,
                                     defaultClientId: Option[String],
                                     stopAfterCreation: Boolean,
                                     welderEnabled: Boolean,
                                     patchInProgress: Boolean)

object ListRuntimeResponse {
  def fromRuntime(runtime: Runtime, runtimeConfig: RuntimeConfig): ListRuntimeResponse =
    ListRuntimeResponse(
      runtime.id,
      runtime.samResource,
      runtime.runtimeName,
      runtime.googleProject,
      runtime.serviceAccount,
      runtime.asyncRuntimeFields,
      runtime.auditInfo,
      runtime.kernelFoundBusyDate,
      runtimeConfig,
      runtime.proxyUrl,
      runtime.status,
      runtime.labels,
      runtime.jupyterUserScriptUri,
      runtime.dataprocInstances,
      runtime.autopauseThreshold,
      runtime.defaultClientId,
      runtime.stopAfterCreation,
      runtime.welderEnabled,
      runtime.patchInProgress
    )
}

final case class GetRuntimeResponse(id: Long,
                                    samResource: RuntimeSamResource,
                                    clusterName: RuntimeName,
                                    googleProject: GoogleProject,
                                    serviceAccountInfo: WorkbenchEmail,
                                    asyncRuntimeFields: Option[AsyncRuntimeFields],
                                    auditInfo: AuditInfo,
                                    kernelFoundBusyDate: Option[Instant],
                                    runtimeConfig: RuntimeConfig,
                                    clusterUrl: URL,
                                    status: RuntimeStatus,
                                    labels: LabelMap,
                                    jupyterUserScriptUri: Option[UserScriptPath],
                                    jupyterStartUserScriptUri: Option[UserScriptPath],
                                    errors: List[RuntimeError],
                                    dataprocInstances: Set[DataprocInstance],
                                    userJupyterExtensionConfig: Option[UserJupyterExtensionConfig],
                                    autopauseThreshold: Int,
                                    defaultClientId: Option[String],
                                    stopAfterCreation: Boolean,
                                    clusterImages: Set[RuntimeImage],
                                    scopes: Set[String],
                                    welderEnabled: Boolean,
                                    patchInProgress: Boolean,
                                    customClusterEnvironmentVariables: Map[String, String],
                                    diskConfig: Option[DiskConfig])

object GetRuntimeResponse {
  def fromRuntime(runtime: Runtime, runtimeConfig: RuntimeConfig, diskConfig: Option[DiskConfig]) = GetRuntimeResponse(
    runtime.id,
    runtime.samResource,
    runtime.runtimeName,
    runtime.googleProject,
    runtime.serviceAccount,
    runtime.asyncRuntimeFields,
    runtime.auditInfo,
    runtime.kernelFoundBusyDate,
    runtimeConfig,
    runtime.proxyUrl,
    runtime.status,
    runtime.labels,
    runtime.jupyterUserScriptUri,
    runtime.jupyterStartUserScriptUri,
    runtime.errors,
    runtime.dataprocInstances,
    runtime.userJupyterExtensionConfig,
    runtime.autopauseThreshold,
    runtime.defaultClientId,
    runtime.stopAfterCreation,
    runtime.runtimeImages,
    runtime.scopes,
    runtime.welderEnabled,
    runtime.patchInProgress,
    runtime.customEnvironmentVariables,
    diskConfig
  )
}

  final case class CreateAppRequest(kubernetesRuntimeConfig: Option[KubernetesRuntimeConfig],
                                    appType: AppType,
                                    diskConfig: Option[DiskConfigRequest],
                                    labels: LabelMap = Map.empty)


  final case class GetAppResponse(kubernetesRuntimeConfig: KubernetesRuntimeConfig,
                                  errors: List[KubernetesError],
                                  status: AppStatus, //TODO: do we need some sort of aggregate status?
                                  proxyUrls: Map[ServiceName, URL],
                                  diskName: Option[DiskName]
                                  )

  object GetAppResponse {
    def fromDbResult(appResult: GetAppResult): GetAppResponse =  {
      val errors =  appResult.cluster.errors ++ appResult.nodepool.errors ++ appResult.app.errors
      GetAppResponse(
        KubernetesRuntimeConfig(
          appResult.nodepool.numNodes,
          appResult.nodepool.machineType,
          appResult.nodepool.autoscalingEnabled
        ),
        errors,
        if (errors.isEmpty) appResult.app.status else AppStatus.Error,
        Map.empty, //TODO: Implement when proxy functionality exists
        appResult.app.appResources.disk.map(_.name)
      )
    }
  }

  final case class ListAppResponse(googleProject: GoogleProject,
                                   kubernetesRuntimeConfig: KubernetesRuntimeConfig,
                                   errors: List[KubernetesError],
                                   status: AppStatus, //TODO: do we need some sort of aggregate status?
                                   proxyUrls: Map[ServiceName, URL],
                                   appName: AppName,
                                   diskName: Option[DiskName])

object ListAppResponse {
  def fromCluster(c: KubernetesCluster): List[ListAppResponse] =
    c.nodepools.flatMap(n =>
      n.apps.map { a =>
        val errors = c.errors ++ n.errors ++ a.errors
        ListAppResponse(
          c.googleProject,
          KubernetesRuntimeConfig(
            n.numNodes,
            n.machineType,
            n.autoscalingEnabled
          ),
          errors,
          if (errors.isEmpty) a.status else AppStatus.Error, //TODO: aggregate?
          Map.empty, //TODO: change this when proxy is implemented
          a.appName,
          a.appResources.disk.map(_.name)
        )
      }
     )

}

