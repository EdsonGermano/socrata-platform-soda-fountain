package com.socrata.soda.server.computation

import com.rojoma.json.v3.ast._
import com.socrata.soda.server.computation.ComputationHandler.MaltypedDataEx
import com.socrata.soda.server.persistence.{ComputationStrategyRecord, ColumnRecordLike}
import com.socrata.soql.environment.ColumnName
import com.socrata.soql.types.{SoQLNull, SoQLNumber, SoQLText}
import com.typesafe.config.Config
import org.apache.curator.x.discovery.ServiceDiscovery

/**
 * A [[ComputationHandler]] that uses region-coder to match a row to a georegion,
 * based on the value of a specified string column.
 * The georegion feature ID returned for each row represents the georegion
 * whose corresponding string column contains the same value as the row.
 * @param config    Configuration information for connecting to region-coder
 * @param discovery ServiceDiscovery instance used for discovering other services using ZK/Curator
 * @tparam T        ServiceDiscovery payload type
 */
class GeoregionMatchOnStringHandler[T](config: Config, discovery: ServiceDiscovery[T])
  extends GeoregionMatchHandler[T, String](config, discovery){

  /**
   * Constructs the region-coder endpoint. Format is:
   * /regions/:resourceName/pointcode?column=:columnName
   * where :resourceName is the name of the georegion to match against,
   * defined in the computed column parameters as 'region'
   * and :columnName is the name of the column in the georegion dataset
   * whose value to match against
   * @param computedColumn Computed column definition
   * @return               region-coder endpoint for georegion coding against strings
   */
  protected def genEndpoint(computedColumn: ColumnRecordLike): String = {
    require(computedColumn.computationStrategy.isDefined, "No computation strategy found")
    computedColumn.computationStrategy match {
      case Some(ComputationStrategyRecord(_, _, Some(JObject(params)))) =>
        require(params.contains("region"), "parameters does not contain 'region'")
        require(params.contains("column"), "parameters does not contain 'column'")
        val JString(region) = params("region")
        val JString(column) = params("column")
        // Falling back to a default primary_key so we don't break things
        val JString(primaryKey) = params.getOrElse("primary_key", JString(defaultRegionPrimaryKey))
        s"/regions/$region/stringcode?columnToMatch=$column&columnToReturn=$primaryKey"
      case x =>
        throw new IllegalArgumentException("Computation strategy parameters were invalid." +
          """Expected format: { "region" : "[REGION_RESOURCE_NAME]", "column" : "[COLUMN_NAME]" }""")
    }
  }

  /**
   * Extracts the value of the source column given the key-value map of fields in the row
   * @param rowmap  Map of fields in the row
   * @param colName Name of the source column
   * @return        Value of the source column as a string
   */
  protected def extractSourceColumnValueFromRow(rowmap: SoQLRow, colName: ColumnName): Option[String] =
    rowmap.get(colName.name) match {
      case Some(SoQLText(str))   => Some(str)
      case Some(SoQLNumber(num)) => Some(num.toString) // Zip codes etc. might be a number.
                                                       // Or is this going to bite us later?
      case Some(SoQLNull) | None => None
      case Some(x)               => throw MaltypedDataEx(colName, SoQLText, x.typ)
    }

  /**
   * Serializes a string to a JSON format that region-coder understands
   * @param str String value
   * @return    JSONified string
   */
  protected def toJValue(str: String): JValue = JString(str)
}
