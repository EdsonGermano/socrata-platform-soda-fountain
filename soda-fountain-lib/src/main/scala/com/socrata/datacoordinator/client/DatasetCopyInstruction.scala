package com.socrata.datacoordinator.client

sealed abstract class DatasetCopyInstruction { val command: String}

case class CreateDataset(locale: String) extends DatasetCopyInstruction { val command = "create"}
case class CopyDataset(copyData: Boolean) extends DatasetCopyInstruction { val command = "copy"}
case class PublishDataset(snapshotLimit: Int) extends DatasetCopyInstruction { val command = "publish"}
case class DropDataset() extends DatasetCopyInstruction { val command = "drop"}
case class UpdateDataset() extends DatasetCopyInstruction { val command = "normal"}

