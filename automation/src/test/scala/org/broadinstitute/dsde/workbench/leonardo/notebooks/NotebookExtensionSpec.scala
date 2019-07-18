package org.broadinstitute.dsde.workbench.leonardo.notebooks

import java.time.Instant

import akka.http.scaladsl.model.HttpResponse
import org.broadinstitute.dsde.workbench.ResourceFile
import org.broadinstitute.dsde.workbench.google2.RemoveObjectResult.Removed
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, RemoveObjectResult}
import org.broadinstitute.dsde.workbench.leonardo._
import org.broadinstitute.dsde.workbench.leonardo.notebooks.Notebook.NotebookMode
import org.scalatest.time.{Minutes, Seconds, Span}

import scala.concurrent.duration._
import scala.language.postfixOps


class NotebookExtensionSpec extends ClusterFixtureSpec with NotebookTestUtils {
  override def enableWelder: Boolean = true
  debug = true
  mockedCluster = mockCluster("gpalloc-dev-master-0h7pzni","automation-test-a5tpb4abz")

  "Leonardo welder and jupyter extensions" - {

    "Welder should be up" in { clusterFixture =>
      val resp: HttpResponse = Welder.getWelderStatus(clusterFixture.cluster)
      resp.status.isSuccess() shouldBe true
    }

    "open notebook in edit mode should work" in { clusterFixture =>
      val sampleNotebook = ResourceFile("bucket-tests/gcsFile.ipynb")
      val isEditMode = true

      withResourceFileInBucket(clusterFixture.billingProject, sampleNotebook, "text/plain") { gcsPath =>

        withWelderInitialized(clusterFixture.cluster, gcsPath, isEditMode) { localizedFile =>

          withWebDriver { implicit driver =>

            withOpenNotebook(clusterFixture.cluster, localizedFile, 2.minutes) { notebookPage =>

              notebookPage.modeExists() shouldBe true
              notebookPage.getMode() shouldBe NotebookMode.EditMode
              notebookPage.addCodeAndExecute("1+1")
              notebookPage.saveNotebook()

              val localContentSize: Int = Notebook.getNotebookItem(clusterFixture.billingProject, clusterFixture.cluster.clusterName, Welder.getLocalPath(gcsPath, isEditMode)).size

              eventually(timeout(Span(5, Seconds))) {
                val remoteContentSize: Int = getObjectSize(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value))
                  .unsafeRunSync()

                remoteContentSize shouldBe localContentSize
              }

              logger.info("Waiting 4 minutes as lock takes time to be reflected in metadata")
              eventually(timeout(Span(4, Minutes)), interval(Span(30, Seconds))) {
                val gcsLockedBy: Option[String] = getLockedBy(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value)).unsafeRunSync()
                val welderLockedBy: Option[String] = Welder.getMetadata(clusterFixture.cluster, gcsPath, isEditMode).lastLockedBy

                gcsLockedBy should not be None
                welderLockedBy should not be None
                gcsLockedBy shouldBe welderLockedBy
              }
            }
          }
        }
      }
    }

    "open notebook in playground mode should work" in { clusterFixture =>
      val sampleNotebook = ResourceFile("bucket-tests/gcsFile.ipynb")
      val isEditMode = false

      withResourceFileInBucket(clusterFixture.billingProject, sampleNotebook, "text/plain") { gcsPath =>
        logger.info("Initialized google storage bucket")

        withWelderInitialized(clusterFixture.cluster, gcsPath, isEditMode) { localizedFile =>

          withWebDriver { implicit driver =>

            withOpenNotebook(clusterFixture.cluster, localizedFile, 2.minutes) { notebookPage =>

              val originalRemoteContentSize: Int = getObjectSize(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value))
                .unsafeRunSync()

              val originalLocalContentSize: Int = Notebook.getNotebookItem(clusterFixture.billingProject, clusterFixture.cluster.clusterName, Welder.getLocalPath(gcsPath, isEditMode)).size

              originalRemoteContentSize shouldBe originalLocalContentSize

              notebookPage.modeExists() shouldBe true
              notebookPage.getMode() shouldBe NotebookMode.SafeMode
              notebookPage.addCodeAndExecute("1+1")

              notebookPage.saveNotebook()
              //sleep 4 minutes. We do this to ensure the assertions are true after a certain about of time
              logger.info("Waiting 4 minutes as lock takes time to be reflected in metadata")
              Thread.sleep(240000)

              eventually(timeout(Span(5, Seconds))) {
                val newLocalContentSize = Notebook.getNotebookItem(clusterFixture.billingProject, clusterFixture.cluster.clusterName, Welder.getLocalPath(gcsPath, isEditMode)).size
                val newRemoteContentSize = getObjectSize(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value))
                  .unsafeRunSync()

                newLocalContentSize should be > newRemoteContentSize
                originalRemoteContentSize shouldBe newRemoteContentSize
              }

              //some selectors are omitted to simplify the test with the assumption that if the majority are hidden, they all are
              val uiElementIds: List[String]  = List("save-notbook", "new_notebook", "open_notebook", "copy_notebook", "save_notebook_as", "save_checkpoint", "restore_checkpoint", "notification_notebook")
              val areElementsHidden: Boolean = notebookPage.areElementsHidden(uiElementIds)

              areElementsHidden shouldBe true

              val gcsLockedBy: Option[String] = getLockedBy(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value)).unsafeRunSync()
              val welderLockedBy: Option[String] = Welder.getMetadata(clusterFixture.cluster, gcsPath, isEditMode).lastLockedBy

              gcsLockedBy shouldBe None
              welderLockedBy shouldBe None
            }
          }
        }
      }
    }

    "Sync issues and make a copy handled correctly" in { clusterFixture =>
      val fileName = "gcsFile2" //we store this portion separately as the name of the copy is computed off it
      val sampleNotebook = ResourceFile(s"bucket-tests/${fileName}.ipynb")
      val isEditMode = true

      withResourceFileInBucket(clusterFixture.billingProject, sampleNotebook, "text/plain") { gcsPath =>
        logger.info("Initialized google storage bucket")

        withWelderInitialized(clusterFixture.cluster, gcsPath, isEditMode) { localizedFile =>

          withWebDriver { implicit driver =>

            withOpenNotebook(clusterFixture.cluster, localizedFile, 10.minutes) { notebookPage =>
              val contents = "{\n      'cells': [],\n      'metadata': {\n        'kernelspec': {\n        'display_name': 'Python 3',\n        'language': 'python',\n        'name': 'python3'\n      },\n        'language_info': {\n        'codemirror_mode': {\n        'name': 'ipython',\n        'version': 3\n      },\n        'file_extension': '.py',\n        'mimetype': 'text/x-python',\n        'name': 'python',\n        'nbconvert_exporter': 'python',\n        'pygments_lexer': 'ipython3',\n        'version': '3.7.3'\n      }\n      },\n      'nbformat': 4,\n      'nbformat_minor': 2\n    }"
              setObjectContents(clusterFixture.billingProject, gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value), contents)
                .unsafeRunSync()


              val syncIssueElements = List(notebookPage.syncCopyButton, notebookPage.syncReloadButton, notebookPage.modalId)
              eventually(timeout(Span(2, Minutes)), interval(Span(30, Seconds))) { //wait for checkMeta tick
                logger.info("before tick")
                notebookPage.areElementsPresent(syncIssueElements) shouldBe true
                logger.info("past tick")


                notebookPage.executeJavaScript("window.onbeforeunload = null;") //disables pesky chrome modal to confirm navigation
                notebookPage.makeACopyFromSyncIssue()
              }

//              Thread.sleep(1.minutes.toMillis)

              logger.info("past eventually")
              //
//              driver.switchTo().alert().accept()

//              logger.info("switching to alert")
//              driver.quit()

//              logger.info("dealt with alert")

              eventually(timeout(Span(30, Seconds))) { //wait for the page to reload
//                driver switch
                  driver.getCurrentUrl should include(fileName + "-Copy")
              }

              Thread.sleep(1000000000)
            }
          }
        }
      }
    }

    "Locked by another user and playground mode transition handled properly" in { clusterFixture =>
      val sampleNotebook = ResourceFile("bucket-tests/gcsFile3.ipynb")
      val isEditMode = true

      withResourceFileInBucket(clusterFixture.billingProject, sampleNotebook, "text/plain") { gcsPath =>
        logger.info("Initialized google storage bucket")

        withWelderInitialized(clusterFixture.cluster, gcsPath, isEditMode) { localizedFile =>

          withWebDriver { implicit driver =>

            withOpenNotebook(clusterFixture.cluster, localizedFile, 10.minutes) { notebookPage =>

              val newMeta = Map("lockExpiresAt" -> Instant.now().plusMillis(20.minutes.toMillis).toString, "lastLockedBy" -> "NotMe")
              setObjectMetadata(gcsPath.bucketName, GcsBlobName(gcsPath.objectName.value), newMeta)
                  .unsafeRunSync()

              eventually(timeout(Span(2, Minutes))) { //wait for checkMeta tick

                val lockIssueElements = List(notebookPage.lockPlaygroundButton, notebookPage.lockCopyButton, notebookPage.modalId)

                notebookPage.areElementsPresent(lockIssueElements) shouldBe true

                notebookPage.goToPlaygroundModeFromLockIssue()

                eventually(timeout(Span(30, Seconds))) { //wait for the page to reload
                  driver.getCurrentUrl should contain(Welder.localSafeModeBaseDirectory)
                }
              }
            }
          }
        }
      }
    }

  }
}
