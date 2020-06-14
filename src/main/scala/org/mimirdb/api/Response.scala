package org.mimirdb.api

import play.api.libs.json._

abstract class Response 

case class ErrorResponse (
            /* throwable class name */
                  errorType: String,
            /* throwable message */
                  errorMessage: String,
            /* throwable stack trace */
                  stackTrace: String
) extends Response

object ErrorResponse {
  implicit val format: Format[ErrorResponse] = Json.format

  def apply(error: Throwable, message: String, className: String = null): ErrorResponse = 
    ErrorResponse(
      Option(className).getOrElse { error.getClass.getCanonicalName() } ,
      message,
      error.getStackTrace.map(_.toString).mkString("\n")
    )
}

case class LensList (
    lensTypes: Seq[String]
) extends Response

object LensList {
  implicit val format: Format[LensList] = Json.format
}

