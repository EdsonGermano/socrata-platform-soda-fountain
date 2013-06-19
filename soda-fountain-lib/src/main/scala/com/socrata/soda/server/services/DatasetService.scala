package com.socrata.soda.server.services

import com.socrata.http.server.responses._
import com.socrata.http.server.implicits._
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import dispatch._
import com.socrata.datacoordinator.client._
import com.rojoma.json.ast._
import com.socrata.soda.server.services.ClientRequestExtractor._
import com.rojoma.json.util.{JsonArrayIterator, JsonUtil}
import com.rojoma.json.io.{JsonReaderException, JsonBadParse, JsonReader}
import com.socrata.http.server.responses
import com.socrata.soda.server.types.TypeChecker
import com.socrata.datacoordinator.client.DataCoordinatorClient.SchemaSpec


trait DatasetService extends SodaService {

  object dataset {

    val MAX_DATUM_SIZE = SodaService.config.getInt("max-dataum-size")

    protected def prepareForUpsert(resourceName: String, request: HttpServletRequest)(f: (String, SchemaSpec, Iterator[UpsertRow]) => HttpServletResponse => Unit) : HttpServletResponse => Unit = {
      val it = streamJsonArrayValues(request, MAX_DATUM_SIZE)
      it match {
        case Right(boundedIt) => {
          try {
            withDatasetId(resourceName){ datasetId =>
              withDatasetSchema(datasetId) { schema =>
                val upserts = boundedIt.map { rowjval =>
                  rowjval match {
                    case JObject(map) =>  {
                      map.foreach{ pair =>
                        val expectedTypeName = schema.schema.get(pair._1).getOrElse( return sendErrorResponse("no column " + pair._1, "column.not.found", BadRequest, Some(rowjval)))
                        TypeChecker.check(expectedTypeName, pair._2) match {
                          case Right(v) => v
                          case Left(msg) => return sendErrorResponse(msg, "type.error", BadRequest, Some(rowjval))
                        }
                      }
                      UpsertRow(map)
                    }
                    case _ => return sendErrorResponse("could not deserialize into JSON object", "json.row.object.not.valid", BadRequest, Some(rowjval))
                  }
                }
                f(datasetId, schema, upserts)
              }
            }
          }
          catch {
            case bp: JsonReaderException => sendErrorResponse("bad JSON value: " + bp.getMessage, "json.value.invalid", BadRequest, None)
          }
        }
        case Left(err) => sendErrorResponse("Error upserting values", err, BadRequest, None)
      }
    }

    def upsert(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      prepareForUpsert(resourceName, request){ (datasetId, schema, upserts) =>
        val r = dc.update(datasetId, schemaHash(request), mockUser, upserts)
        passThroughResponse(r)
      }
    }

    def replace(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      prepareForUpsert(resourceName, request){ (datasetId, schema, upserts) =>
        val r = dc.copy(datasetId, schemaHash(request), false, mockUser, Some(upserts))
        //TODO: publish?
        passThroughResponse(r)
      }
    }

    def truncate(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName) { datasetId =>
        val c = dc.copy(datasetId, schemaHash(request), false, mockUser, None)
        //TODO: publish?
        passThroughResponse(c)
      }
    }

    def query(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName) { datasetId =>
        val q = Option(request.getParameter("$query")).getOrElse("select *")
        val r = qc.query(datasetId, q)
        r() match {
          case Right(response) => {
            responses.Status(response.getStatusCode) ~>  ContentType(response.getContentType) ~> Content(response.getResponseBody)
          }
          case Left(err) => sendErrorResponse(err.getMessage, "internal.error", InternalServerError, None)
        }
      }
    }

   def create()(request:HttpServletRequest): HttpServletResponse => Unit =  {
      DatasetSpec(request.getReader) match {
        case Right(dspec) => {
          val columnInstructions = dspec.columns.map(c => new AddColumnInstruction(c.fieldName, c.dataType))
          val instructions = dspec.rowId match{
            case Some(rid) => columnInstructions :+ SetRowIdColumnInstruction(rid)
            case None => columnInstructions
          }
          val rnf = store.translateResourceName(dspec.resourceName)
          rnf() match {
            case Left(err) => { //TODO: can I be more specific here?
              val r = dc.create(mockUser, Some(instructions.iterator), dspec.locale )
              r() match {
                case Right((datasetId, records)) => {
                  store.add(dspec.resourceName, datasetId)  // TODO: handle failure here, see list of errors from DC.
                  OK
                }
                case Left(thr) => sendErrorResponse("could not create dataset", "internal.error", InternalServerError, Some(JString(thr.getMessage)))
              }
            }
            case Right(datasetId) => sendErrorResponse("Dataset already exists", "dataset.already.exists", BadRequest, None)
          }
        }
        case Left(ers: Seq[String]) => sendErrorResponse( ers.mkString(" "), "dataset.specification.invalid", BadRequest, None )
      }
    }
    def setSchema(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      ColumnSpec.array(request.getReader) match {
        case Right(specs) => ???
        case Left(ers) => sendErrorResponse( ers.mkString(" "), "column.specification.invalid", BadRequest, None )
      }
    }
    def getSchema(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      withDatasetId(resourceName){ id =>
        val f = dc.getSchema(id)
        val r = f()
        r match {
          case Right(schema) => OK ~> Content(schema.toString)
          case Left(err) => sendErrorResponse(err, "dataset.schema.notfound", NotFound, Some(JString(resourceName)))
        }
      }
    }
    def delete(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName){ datasetId =>
        val d = dc.deleteAllCopies(datasetId, schemaHash(request), mockUser)
        d() match {
          case Right(response) => {
            store.remove(resourceName) //TODO: handle error case!
            passThroughResponse(response)
          }
          case Left(thr) => sendErrorResponse(thr.getMessage, "failed.delete", InternalServerError, None)
        }
      }
    }

    def copy(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName){ datasetId =>
        val doCopyData = Option(request.getParameter("copy_data")).getOrElse("false").toBoolean
        val c = dc.copy(datasetId, schemaHash(request), doCopyData, mockUser, None)
        passThroughResponse(c)
      }
    }

    def dropCopy(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName){ datasetId =>
        val d = dc.dropCopy(datasetId, schemaHash(request), mockUser, None)
        passThroughResponse(d)
      }
    }

    def publish(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName){ datasetId =>
        val snapshowLimit = Option(request.getParameter("snapshot_limit")).flatMap( s => Some(s.toInt) )
        val p = dc.publish(datasetId, schemaHash(request), snapshowLimit, mockUser, None)
        p() match {
          case Right(response) => {
            dc.propagateToSecondary(datasetId)
            passThroughResponse(response)
          }
          case Left(thr) => sendErrorResponse(thr.getMessage, "failed.publish", InternalServerError, None)
        }
      }
    }

    def checkVersionInSecondary(resourceName: String, secondaryName: String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      withDatasetId(resourceName) { datasetId =>
        val response = dc.checkVersionInSecondary(datasetId, secondaryName)
        response match {
          case Right(response) => OK ~> Content(response.version.toString)
          case Left(err) => InternalServerError ~> Content(err)
        }
      }
    }
  }
}
