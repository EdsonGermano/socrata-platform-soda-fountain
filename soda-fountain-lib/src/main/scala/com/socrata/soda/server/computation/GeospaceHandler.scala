package com.socrata.soda.server.computation

import com.rojoma.json.ast.{JObject, JString, JArray, JNumber}
import com.rojoma.json.codec.JsonCodec
import com.rojoma.json.io.{JsonReader, CompactJsonWriter}
import com.socrata.soda.server.highlevel.RowDataTranslator
import com.socrata.soda.server.highlevel.RowDataTranslator.{DeleteAsCJson, UpsertAsSoQL}
import com.socrata.soda.server.persistence._
import com.socrata.soql.environment.ColumnName
import com.socrata.soql.types.{SoQLPoint, SoQLText}
import com.socrata.thirdparty.curator.CuratorServiceBase
import com.typesafe.config.Config
import org.apache.curator.x.discovery.ServiceDiscovery
import org.slf4j.LoggerFactory
import scala.annotation.tailrec
import scala.concurrent.duration._
import scalaj.http.Http

/**
 * A [[ComputationHandler]] for mapping points (or lat/long pairs) to geo features (point-in-polygon)
 * using the Geospace microservice (http://github.com/socrata/geospace).
 *
 * Source rows that don't have points or that don't map to a region are encoded using an empty string.
 *
 * To instantiate, pass the sub-config only, like so:
 *
 *     val handler = new GeospaceHandler(rootConfig.at("computation.handlers.geospace"))
 *
 * == Config ==
 * {{{
 *   service-name = "geospace"
 *   batch-size = 200    # Number of rows to send to Geospace server at once
 * }}}
 */
class GeospaceHandler[T](config: Config, discovery: ServiceDiscovery[T]) extends ComputationHandler {
  import ComputationHandler._

  // Get config values
  val serviceName  = config.getString("service-name")
  val batchSize    = config.getInt("batch-size")
  val maxRetries   = config.getInt("max-retries")
  val retryWait    = config.getMilliseconds("retry-wait").longValue

  class GeospaceService[T](discovery: ServiceDiscovery[T]) extends CuratorServiceBase(discovery, serviceName)
  val service = new GeospaceService(discovery)
  service.start()

  def urlPrefix = Option(service.provider.getInstance()).map { serv => serv.buildUriSpec() + "experimental" }.
                    getOrElse(throw new RuntimeException("Unable to get Geospace instance from Curator/ZK"))

  private val logger = LoggerFactory.getLogger(getClass)

  case class Point(x: Double, y: Double)

  /**
   * A single-threaded (for now) geo-region-coding handler.  Batches and sends out the points
   * to Geospace, then incorporates the feature IDs into a new column.
   *
   * == Parsing from a single point column ==
   * This is the only supported source column for now.
   * sourceColumns must be a list of one column, and it must be a Geo Point type.
   * parameters: {"region":  <<name of geo region dataset 4x4>>}
   */
  def compute(sourceIt: Iterator[RowDataTranslator.Success], column: MinimalColumnRecord): Iterator[RowDataTranslator.Success] = {
    // Only a single point column is allowed as a source for now
    val (geoColumnName, region) = parsePointColumnSourceStrategy(column)

    val batches = sourceIt.grouped(batchSize)
    val computedBatches = batches.map { batch =>
      val rowsWithIndex = batch.zipWithIndex.toSeq

      // Grab just the upserts and get the point column for mapping to feature ID
      val pointsWithIndex = rowsWithIndex.collect {
        case (upsert: UpsertAsSoQL, i) => (extractPointFromRow(upsert.rowData.toMap, ColumnName(geoColumnName)), i)
      }.collect {
        case (Some(point), i)          => (point, i)
      }

      // Convert points to feature IDs, and splice feature IDs back into rows.
      // Deletes are returned untouched.
      val featureIds = geospaceRegionCoder(pointsWithIndex.map(_._1), region)
      val featureIdsWithIndex = pointsWithIndex.map(_._2).zip(featureIds).toMap
      rowsWithIndex.map {
        case (upsert: UpsertAsSoQL, i) =>
          val featureId = featureIdsWithIndex.getOrElse(i, "")
          UpsertAsSoQL(upsert.rowData + (column.fieldName.name -> SoQLText(featureId)))
        case (delete: DeleteAsCJson, i) => delete
        case _                     =>
          val message = "Unsupported row update type passed into GeospaceHandler"
          logger.error(message)
          throw ComputationEx(message, None)
      }.toIterator
    }
    computedBatches.flatten
  }

  def close() {
    logger.info("Closing GeospaceHandler...")
    service.close()
  }

  private def parsePointColumnSourceStrategy(column: MinimalColumnRecord): (String, String) = {
    require(column.computationStrategy.isDefined, "Not a target computed column")
    column.computationStrategy match {
      case Some(ComputationStrategyRecord(_, _, Some(Seq(sourceCol)), Some(JObject(map)))) =>
        require(map contains "region", "parameters does not contain 'region'")
        val JString(regionName) = map("region")
        (sourceCol, regionName)
      case x =>  throw new IllegalArgumentException("There must be exactly 1 sourceColumn, and " +
        "parameters must have a key 'region'")
    }
  }

  private def extractPointFromRow(rowmap: SoQLRow, colName: ColumnName): Option[Point] = {
    rowmap.get(colName.name) match {
      case Some(point: SoQLPoint) => Some(Point(point.value.getX, point.value.getY))
      case Some(x)                => throw MaltypedDataEx(colName, SoQLPoint, x.typ)
      case None                   => None
    }
  }

  private def geospaceRegionCoder(points: Seq[Point], region: String): Seq[String] = {
    if (points.size == 0) return Seq[String]()

    val url = urlPrefix + s"/regions/$region/geocode"
    logger.debug("HTTP POST [{}] with {} points...", url, points.length)

    val jsonPoints = points.map { case Point(x, y) => JArray(Seq(JNumber(x), JNumber(y))) }
    val (status, response) = postWithRetry(url, jsonPoints, maxRetries)

    logger.debug("Got back status {}, response [{}]", status, response)
    status match {
      case 200 =>
        JsonCodec[Seq[String]].decode(JsonReader.fromString(response)).
          getOrElse(throw ComputationEx("Error parsing JSON response: " + response, None))
      case sc  =>
        val errorMessage = s"Error: HTTP [$url] got response code $sc, body $response"
        logger.error(errorMessage)
        throw ComputationEx(errorMessage, None)
    }
  }

  @tailrec
  private def postWithRetry(url: String, jsonPoints: Seq[JArray], retriesLeft: Int): (Int, String) = {
    try {
      val (status, _, response) = Http.postData(url, CompactJsonWriter.toString(JArray(jsonPoints))).
        header("content-type", "application/json").
        asHeadersAndParse(Http.readString)
      (status, response)
    } catch {
      case e: scalaj.http.HttpException =>
        if (retriesLeft > 0) {
          Thread.sleep(retryWait)
          postWithRetry(url, jsonPoints, retriesLeft - 1)
        }
        else {
          logger.error("HTTP Error: ", e)
          throw ComputationEx("HTTP Error reading " + url, Some(e))
        }
    }
  }
}