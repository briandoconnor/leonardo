package org.broadinstitute.dsde.workbench.leonardo

final case class CreateDiskRequest(labels: LabelMap,
                                   size: Option[DiskSize],
                                   diskType: Option[DiskType],
                                   blockSize: Option[BlockSize])
