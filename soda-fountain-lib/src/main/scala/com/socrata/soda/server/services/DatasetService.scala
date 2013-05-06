package com.socrata.soda.server.services

import com.socrata.http.server.responses._
import com.socrata.http.server.implicits._
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import dispatch._
import com.socrata.datacoordinator.client.{UpsertRow, UpdateDataset, MutationScript, DataCoordinatorClient}
import com.rojoma.json.ast._
import com.rojoma.json.util.JsonUtil
import com.socrata.soda.server.SodaFountain


trait DatasetService extends SodaService {

  object data {

    def query(datasetResourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("resource request not implemented")
    }

    def get(datasetResourceName: String, rowId:String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("resource request not implemented")
    }

    def setRowFromPost(resourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      try {
        val fields = JsonUtil.readJson[Map[String,JValue]](request.getReader)
        fields match {
          case Some(map) => {
            store.translateResourceName(resourceName) match {
              case Some((datasetId, schemaHash)) => {
                val response = dc.update(datasetId, schemaHash, "soda-fountain-community-edition", Array(UpsertRow(map)).toIterable)
                response() match {
                  case Right(resp) => DataCoordinatorClient.passThroughResponse(resp)
                  case Left(th) => sendErrorResponse(th.getMessage, "internal.error", InternalServerError, None)
                }
              }
              case None => sendErrorResponse("could not find dataset", "unknown.dataset", NotFound, Some(JString(resourceName)))
            }
          }
          case None => sendErrorResponse("could not parse request body as single JSON object", "parse.error", BadRequest, None)
        }
      } catch {
        case e: Exception => sendErrorResponse("could not parse request body as JSON: " + e.getMessage, "parse.error", UnsupportedMediaType, None)
        case _: Throwable => sendErrorResponse("error processing request", "internal.error", InternalServerError, None)
      }
    }

    def set(datasetResourceName: String, rowId:String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
      ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("resource request not implemented")
    }

    def create(datasetResourceName: String, rowId:String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("create request not implemented")
    }

    def delete(datasetResourceName: String, rowId:String)(request:HttpServletRequest): HttpServletResponse => Unit = {
      ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("delete request not implemented")
    }
  }
}

