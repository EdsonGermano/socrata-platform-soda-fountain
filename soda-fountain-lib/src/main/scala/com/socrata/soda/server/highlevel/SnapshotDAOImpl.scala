package com.socrata.soda.server.highlevel

import java.nio.charset.StandardCharsets

import com.rojoma.simplearm.v2.ResourceScope
import com.socrata.http.common.util.AliasedCharset
import com.socrata.soda.clients.datacoordinator.DataCoordinatorClient
import com.socrata.soda.clients.datacoordinator.DataCoordinatorClient.ExportResult
import com.socrata.soda.server.export.CsvExporter
import com.socrata.soda.server.highlevel.ExportDAO.ColumnInfo
import com.socrata.soda.server.id.{ColumnId, ResourceName}
import com.socrata.soda.server.persistence.NameAndSchemaStore
import com.socrata.soda.server.wiremodels.JsonColumnRep
import com.socrata.soql.environment.ColumnName

class SnapshotDAOImpl(store: NameAndSchemaStore, dc: DataCoordinatorClient) extends SnapshotDAO {
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[SnapshotDAOImpl])

  override def datasetsWithSnapshots(): Set[ResourceName] =
    store.bulkDatasetLookup(dc.datasetsWithSnapshots())

  override def snapshotsForDataset(resourceName: ResourceName): Option[Seq[Long]] =
    store.lookupDataset(resourceName) match {
      case dss if dss.nonEmpty =>
        val ds = dss.head
        dc.listSnapshots(ds.systemId)
      case _ =>
        None
    }

  override def deleteSnapshot(resourceName: ResourceName, snapshot: Long): SnapshotDAO.DeleteSnapshotResponse =
    store.lookupDataset(resourceName) match {
      case dss if dss.nonEmpty =>
        val ds = dss.head
        if(!dc.deleteSnapshot(ds.systemId, snapshot)) SnapshotDAO.SnapshotNotFound
        else SnapshotDAO.Deleted
      case _ =>
        SnapshotDAO.DatasetNotFound
    }

  override def exportSnapshot(resourceName: ResourceName, snapshot: Long, resourceScope: ResourceScope): SnapshotDAO.ExportSnapshotResponse = {
    store.lookupDataset(resourceName) match {
      case dss if dss.nonEmpty =>
        val ds = dss.head
        dc.exportSimple(ds.systemId, snapshot.toString, resourceScope) match {
          case ExportResult(json, _) =>
            val decodedSchema = CJson.decode(json, JsonColumnRep.forDataCoordinatorType)
            val schema = decodedSchema.schema
            SnapshotDAO.Export(
              CsvExporter.export(
                AliasedCharset(StandardCharsets.UTF_8, "utf-8"),
                ExportDAO.CSchema(
                  approximateRowCount = None,
                  dataVersion = None,
                  lastModified = None,
                  locale = schema.locale,
                  pk = None,
                  rowCount = None,
                  schema = schema.schema.map { f =>
                    val fieldName = f.f.getOrElse(ColumnName(f.c.underlying))
                    ColumnInfo(f.c, fieldName, fieldName.name, f.t)
                  }),
                decodedSchema.rows))
          // TODO: Snapshot not found
          case other =>
            log.error("Unexpected result from simple export: {}", other)
            throw new Exception("Unexpected result from simple export: " + other)
        }
      case _ =>
        SnapshotDAO.DatasetNotFound
    }
  }
}