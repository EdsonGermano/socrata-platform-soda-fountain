package com.socrata.soda.server.wiremodels

import org.scalatest.{Assertions, FunSuite}
import org.scalatest.matchers.MustMatchers
import com.socrata.soql.types._
import com.rojoma.json.ast._
import com.rojoma.json.ast.JString

class JsonColumnRepTest extends FunSuite with MustMatchers with Assertions {
  test("Client reps know about all types") {
    JsonColumnRep.forClientType.keySet must equal (SoQLType.typesByName.values.toSet)
  }

  test("Data coordinator reps know about all types") {
    JsonColumnRep.forDataCoordinatorType.keySet must equal (SoQLType.typesByName.values.toSet)
  }

  test("JSON type checker handles nulls"){
    JsonColumnRep.forClientType(SoQLText).fromJValue(JNull) must equal (Some(SoQLNull))
  }

  test("JSON type checker with text"){
    val input = "this is input text"
    JsonColumnRep.forClientType(SoQLText).fromJValue(JString(input)) must equal (Some(SoQLText(input)))
  }

  test("JSON type checker with unicode text"){
    val input = "this is unicode input text   صص صꕥꔚꔄꔞഝആ"
    JsonColumnRep.forClientType(SoQLText).fromJValue(JString(input)) must equal (Some(SoQLText(input)))
  }

  test("JSON type checker for text: invalid input - object") {
    JsonColumnRep.forClientType(SoQLText).fromJValue(JObject.canonicalEmpty) must equal (None)
  }

  test("JSON type checker with number (as string)"){
    val input = "12345"
    JsonColumnRep.forClientType(SoQLNumber).fromJValue(JString(input)) must equal (Some(SoQLNumber(java.math.BigDecimal.valueOf(input.toLong))))
  }

  test("JSON type checker with number (as number)"){
    val input = BigDecimal(12345).bigDecimal
    JsonColumnRep.forClientType(SoQLNumber).fromJValue(JNumber(input)) must equal (Some(SoQLNumber(input)))
  }

  test("JSON type checker with double"){
    val input = 123.456789
    JsonColumnRep.forClientType(SoQLDouble).fromJValue(JNumber(input)) must equal (Some(SoQLDouble(input)))
  }

  test("JSON type checker with double - positive infinity"){
    JsonColumnRep.forClientType(SoQLDouble).fromJValue(JString("Infinity")) must equal (Some(SoQLDouble(Double.PositiveInfinity)))
  }

  test("JSON type checker with double - negative infinity"){
    JsonColumnRep.forClientType(SoQLDouble).fromJValue(JString("-Infinity")) must equal (Some(SoQLDouble(Double.NegativeInfinity)))
  }

  test("JSON type checker with double - NaN"){
    val result = JsonColumnRep.forClientType(SoQLDouble).fromJValue(JString("NaN"))
    assert(result.get.asInstanceOf[SoQLDouble].value.isNaN)
  }

  test("JSON type checker with money"){
    val input = BigDecimal(123.45).bigDecimal
    JsonColumnRep.forClientType(SoQLMoney).fromJValue(JNumber(input)) must equal (Some(SoQLMoney(input)))
  }

  test("JSON type checker with boolean"){
    val input = false
    JsonColumnRep.forClientType(SoQLBoolean).fromJValue(JBoolean(input)) must equal (Some(SoQLBoolean(input)))
  }

  test("JSON type checker with fixed timestamp"){
    val input = "2013-06-03T02:26:05.123Z"
    val asDateTime = SoQLFixedTimestamp.StringRep.unapply(input).get
    JsonColumnRep.forClientType(SoQLFixedTimestamp).fromJValue(JString(input)) must equal (Some(SoQLFixedTimestamp(asDateTime)))
  }

  test("JSON type checker with floating timestamp"){
    val input = "2013-06-03T02:26:05.123"
    val asDateTime = SoQLFloatingTimestamp.StringRep.unapply(input).get
    JsonColumnRep.forClientType(SoQLFloatingTimestamp).fromJValue(JString(input)) must equal (Some(SoQLFloatingTimestamp(asDateTime)))
  }

  test("JSON type checker with date"){
    val input = "2013-06-03"
    val asDate = SoQLDate.StringRep.unapply(input).get
    JsonColumnRep.forClientType(SoQLDate).fromJValue(JString(input)) must equal (Some(SoQLDate(asDate)))
  }

  test("JSON type checker with time"){
    val input = "02:26:05.123"
    val asTime = SoQLTime.StringRep.unapply(input).get
    JsonColumnRep.forClientType(SoQLTime).fromJValue(JString(input)) must equal (Some(SoQLTime(asTime)))
  }

  test("JSON type checker with invalid time"){
    val input = "@0z2:2!6:0$5.123"
    JsonColumnRep.forClientType(SoQLTime).fromJValue(JString(input)) must be (None)
  }

  test("JSON type checker with array"){
    val input = JArray(Seq(JString("this is text"), JNumber(222), JNull, JBoolean(true)))
    JsonColumnRep.forClientType(SoQLArray).fromJValue(input) must equal (Some(SoQLArray(input)))
  }

  test("JSON type checker with object"){
    val input = JObject(Map("key" -> JString("value")))
    JsonColumnRep.forClientType(SoQLObject).fromJValue(input) must equal (Some(SoQLObject(input)))
  }
}