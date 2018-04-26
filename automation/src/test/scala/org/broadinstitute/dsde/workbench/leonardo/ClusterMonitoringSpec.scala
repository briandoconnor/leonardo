package org.broadinstitute.dsde.workbench.leonardo

import java.io.File

import org.broadinstitute.dsde.workbench.service.{Orchestration, Sam}
import org.broadinstitute.dsde.workbench.dao.Google.{googleIamDAO, googleStorageDAO}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.model.google.GcsEntityTypes.Group
import org.broadinstitute.dsde.workbench.model.google.GcsRoles.Reader
import org.broadinstitute.dsde.workbench.model.google.{GcsEntity, GcsObjectName, GcsPath, GoogleProject, parseGcsPath}
import org.scalatest.{FreeSpec, ParallelTestExecution}

class ClusterMonitoringSpec extends FreeSpec with LeonardoTestUtils with ParallelTestExecution with BillingFixtures {

  // these tests interact with the Leo APIs _and_ interact with notebooks via selenium

  // default PetClusterServiceAccountProvider edition
  "should create a cluster in a different billing project using PetClusterServiceAccountProvider and put the pet's credentials on the cluster" in withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      val project = GoogleProject(projectName)

      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

      implicit val token = ronAuthToken
      // Pre-conditions: pet service account exists in this Google project and in Sam
      val petEmail = getAndVerifyPet(project)

      // Create a cluster

      withNewCluster(project) { cluster =>
        // cluster should have been created with the pet service account
        cluster.serviceAccountInfo.clusterServiceAccount shouldBe Some(petEmail)
        cluster.serviceAccountInfo.notebookServiceAccount shouldBe None

        withNewNotebook(cluster) { notebookPage =>
          // should not have notebook credentials because Leo is not configured to use a notebook service account
          verifyNoNotebookCredentials(notebookPage)
        }
      }

      // Post-conditions: pet should still exist in this Google project

      implicit val patienceConfig: PatienceConfig = saPatience
      val googlePetEmail2 = googleIamDAO.findServiceAccount(project, petEmail).futureValue.map(_.email)
      googlePetEmail2 shouldBe Some(petEmail)
    }
  }

  // PetNotebookServiceAccountProvider edition.  IGNORE.
  "should create a cluster in a different billing project using PetNotebookServiceAccountProvider and put the pet's credentials on the cluster" ignore withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      val project = GoogleProject(projectName)

      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

      implicit val token = ronAuthToken
      // Pre-conditions: pet service account exists in this Google project and in Sam
      val petEmail = getAndVerifyPet(project)

      // Create a cluster

      withNewCluster(project) { cluster =>
        // cluster should have been created with the default cluster account
        cluster.serviceAccountInfo.clusterServiceAccount shouldBe None
        cluster.serviceAccountInfo.notebookServiceAccount shouldBe Some(petEmail)

        withNewNotebook(cluster) { notebookPage =>
          // should have notebook credentials
          verifyNotebookCredentials(notebookPage, petEmail)
        }
      }

      // Post-conditions: pet should still exist in this Google project

      implicit val patienceConfig: PatienceConfig = saPatience
      val googlePetEmail2 = googleIamDAO.findServiceAccount(project, petEmail).futureValue.map(_.email)
      googlePetEmail2 shouldBe Some(petEmail)
    }
  }

  // TODO: we've noticed intermittent failures for this test. See:
  // https://github.com/DataBiosphere/leonardo/issues/204
  // https://github.com/DataBiosphere/leonardo/issues/228
  "should execute Hail with correct permissions on a cluster with preemptible workers" in withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      val project = GoogleProject(projectName)

      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

      withNewGoogleBucket(project) { bucket =>
        implicit val patienceConfig: PatienceConfig = storagePatience

        val srcPath = parseGcsPath("gs://genomics-public-data/1000-genomes/vcf/ALL.chr20.integrated_phase1_v3.20101123.snps_indels_svs.genotypes.vcf").right.get
        val destPath = GcsPath(bucket, GcsObjectName("chr20.vcf"))
        googleStorageDAO.copyObject(srcPath.bucketName, srcPath.objectName, destPath.bucketName, destPath.objectName).futureValue

        implicit val token = ronAuthToken
        val ronProxyGroup = Sam.user.proxyGroup(ronEmail)
        val ronPetEntity = GcsEntity(ronProxyGroup, Group)
        googleStorageDAO.setObjectAccessControl(destPath.bucketName, destPath.objectName, ronPetEntity, Reader).futureValue

        val request = ClusterRequest(machineConfig = Option(MachineConfig(
          // need at least 2 regular workers to enable preemptibles
          numberOfWorkers = Option(2),
          numberOfPreemptibleWorkers = Option(10)
        )))

        withNewCluster(project, request = request) { cluster =>
          withNewNotebook(cluster) { notebookPage =>
            verifyHailImport(notebookPage, destPath, cluster.clusterName)
          }
        }
      }
    }
  }

  "should pause and resume a cluster" in withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      val project = GoogleProject(projectName)

      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

      implicit val token = ronAuthToken

      // Create a cluster
      withNewCluster(project) { cluster =>
        val printStr = "Pause/resume test"

        // Create a notebook and execute a cell
        withNewNotebook(cluster, kernel = Python3) { notebookPage =>
          notebookPage.executeCell(s"""print("$printStr")""") shouldBe Some(printStr)
          notebookPage.saveAndCheckpoint()
        }

        // Stop the cluster
        stopAndMonitor(cluster.googleProject, cluster.clusterName)

        // Start the cluster
        startAndMonitor(cluster.googleProject, cluster.clusterName)

        // TODO make tests rename notebooks?
        val notebookPath = new File("Untitled.ipynb")
        withOpenNotebook(cluster, notebookPath) { notebookPage =>
          // old output should still exist
          val firstCell = notebookPage.firstCell
          notebookPage.cellOutput(firstCell) shouldBe Some(printStr)
          // execute a new cell to make sure the notebook kernel still works
          notebookPage.runAllCells(60)
          notebookPage.executeCell("sum(range(1,10))") shouldBe Some("45")
        }
      }

    }
  }

  "should pause and resume a cluster with preemptible instances" in withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      val project = GoogleProject(projectName)

      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)

      withNewGoogleBucket(project) { bucket =>
        implicit val patienceConfig: PatienceConfig = storagePatience

        val srcPath = parseGcsPath("gs://genomics-public-data/1000-genomes/vcf/ALL.chr20.integrated_phase1_v3.20101123.snps_indels_svs.genotypes.vcf").right.get
        val destPath = GcsPath(bucket, GcsObjectName("chr20.vcf"))
        googleStorageDAO.copyObject(srcPath.bucketName, srcPath.objectName, destPath.bucketName, destPath.objectName).futureValue

        implicit val token = ronAuthToken
        val ronProxyGroup = Sam.user.proxyGroup(ronEmail)
        val ronPetEntity = GcsEntity(ronProxyGroup, Group)
        googleStorageDAO.setObjectAccessControl(destPath.bucketName, destPath.objectName, ronPetEntity, Reader).futureValue

        val request = ClusterRequest(machineConfig = Option(MachineConfig(
          // need at least 2 regular workers to enable preemptibles
          numberOfWorkers = Option(2),
          numberOfPreemptibleWorkers = Option(10)
        )))

        withNewCluster(project, request = request) { cluster =>
          // Verify a Hail job uses preemptibes
          withNewNotebook(cluster) { notebookPage =>
            verifyHailImport(notebookPage, destPath, cluster.clusterName)
            notebookPage.saveAndCheckpoint()
          }

          // Stop the cluster
          stopAndMonitor(cluster.googleProject, cluster.clusterName)

          // Start the cluster
          startAndMonitor(cluster.googleProject, cluster.clusterName)

          // Verify the Hail import again in a new notebook
          withNewNotebook(cluster) { notebookPage =>
            verifyHailImport(notebookPage, destPath, cluster.clusterName)
            notebookPage.saveAndCheckpoint()
          }
        }
      }
    }

  }

  //Test to check if extensions are installed correctly
  //Using nbtranslate extension from here:
  //https://github.com/ipython-contrib/jupyter_contrib_nbextensions/tree/master/src/jupyter_contrib_nbextensions/nbextensions/nbTranslate
  "should install user specified notebook extensions" in withWebDriver { implicit driver =>
    withCleanBillingProject(hermioneCreds) { projectName =>
      Orchestration.billing.addUserToBillingProject(projectName, ronEmail, Orchestration.billing.BillingProjectRole.User)(hermioneAuthToken)
      val project = GoogleProject(projectName)
      implicit val token = ronAuthToken

      val clusterName = ClusterName("user-jupyter-ext" + makeRandomId())
      withNewCluster(project, clusterName, ClusterRequest(Map(), Option(testJupyterExtensionUri), None)) { cluster =>
        withNewNotebook(cluster) { notebookPage =>
          notebookPage.executeCell("1 + 1") shouldBe Some("2")
          //Check if the mark up was translated correctly
          notebookPage.translateMarkup("Hello") should include("Bonjour")
        }
      }
    }
  }

}
