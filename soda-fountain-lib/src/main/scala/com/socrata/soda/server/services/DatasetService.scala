package com.socrata.soda.server.services

import com.socrata.http.server.responses._
import com.socrata.http.server.implicits._
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import dispatch._
import com.socrata.datacoordinator.client.{UpsertRowInstruction, UpdateDataset, MutationScript, DataCoordinatorClient}
import com.rojoma.json.ast._
import com.rojoma.json.util.JsonUtil
import com.socrata.soda.server.SodaFountain


object DatasetService {

  val dc = DataCoordinatorClient.instance

  def query(datasetResourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
    ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("resource request not implemented")
  }

  def get(datasetResourceName: String, rowId:String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
    ImATeapot ~> ContentType("text/plain; charset=utf-8") ~> Content("resource request not implemented")
  }

  def setRowFromPost(datasetResourceName: String)(request:HttpServletRequest): HttpServletResponse => Unit =  {
    try {
      val fields = JsonUtil.readJson[Map[String,JValue]](request.getReader)
      fields match {
        case Some(map) => {
          val script = new MutationScript(datasetResourceName,
            "soda-fountain-community-edition",
            UpdateDataset(),
            Array(Right(UpsertRowInstruction(map))).toIterable)
          val response = dc.sendMutateRequest(script)
          response() match {
            case Right(resp) => DataCoordinatorClient.passThroughResponse(resp)
            case Left(th) => SodaFountain.sendErrorResponse(th.getMessage, "internal.error", InternalServerError, None)
          }
        }
        case None => SodaFountain.sendErrorResponse("could not parse request body as single JSON object", "parse.error", BadRequest, None)
      }
    } catch {
      case e: Exception => SodaFountain.sendErrorResponse("could not parse request body as JSON: " + e.getMessage, "parse.error", UnsupportedMediaType, None)
      case _ => SodaFountain.sendErrorResponse("could not parse request body", "parse.error", UnsupportedMediaType, None)
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

