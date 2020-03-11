package org.mimirdb.api.request

import play.api.libs.json._
import org.apache.spark.sql.{ SparkSession, DataFrame }
import com.typesafe.scalalogging.LazyLogging

import org.mimirdb.api.{ Request, Response, Tuple }
import org.mimirdb.api.MimirAPI
import org.mimirdb.data.{ 
  Catalog, 
  FileFormat, 
  DataFrameConstructor, 
  DataFrameConstructorCodec 
}
import org.mimirdb.lenses.Lenses

case class LoadConstructor(
  url: String,
  format: String,
  sparkOptions: Map[String, String],
  lenses: Seq[(String, JsValue, String)] = Seq()
)
  extends DataFrameConstructor
  with LazyLogging
{
  def construct(
    spark: SparkSession, 
    context: Map[String, DataFrame] = Map()
  ): DataFrame =
  {
    var parser = spark.read.format(format)
    for((option, value) <- sparkOptions){
      parser = parser.option(option, value)
    }
    logger.trace(s"Creating dataframe for $format file from $url")
    return lenses.foldLeft(parser.load(url)) {
      (df, lens) => Lenses(lens._1).create(df, lens._2, lens._3)
    }

  }

  def withLens(
    spark: SparkSession, 
    lens: String, 
    contextText: String,
    initialConfig: JsValue = JsNull
  ) =
  {
    LoadConstructor(
      url,
      format,
      sparkOptions,
      lenses :+ (
        lens, 
        Lenses(lens).train(construct(spark), initialConfig), 
        contextText
      )
    )
  }
}

object LoadConstructor
  extends DataFrameConstructorCodec
{
  implicit val format: Format[LoadConstructor] = Json.format
  def apply(v: JsValue): DataFrameConstructor = v.as[LoadConstructor]
}

case class LoadRequest (
            /* file url of datasorce to load */
                  file: String,
            /* format of file for spark */
                  format: String,
            /* infer types in data source */
                  inferTypes: Boolean,
            /* detect headers in datasource */
                  detectHeaders: Boolean,
            /* optionally provide a name */
                  humanReadableName: Option[String],
            /* options for spark datasource api */
                  backendOption: Seq[Tuple],
            /* optionally provide dependencies */
                  dependencies: Seq[String],
            /* optionally provide an output name */
                  resultName: Option[String]
) extends Request {

  lazy val output = 
    resultName.getOrElse {
      val lensNameBase = (
        file 
        + format 
        + inferTypes.toString 
        + detectHeaders.toString 
        + humanReadableName.toString 
        + backendOption.toString 
        + dependencies.toString
      ).hashCode()
      val hint = humanReadableName.getOrElse { format }.replaceAll("[^a-zA-Z]", "")
      "DATASOURCE_" + hint + "_" + (lensNameBase.toString().replace("-", ""))
    }

  def handle: JsValue = {
    val sparkOptions = backendOption.map { tup => tup.name -> tup.value }

    // Some parameters may change during the loading process.  Var-ify them
    var url = file
    var storageFormat = format
    var finalSparkOptions = 
      Catalog.defaultLoadOptions(format, detectHeaders) ++ sparkOptions

    // Build a preliminary configuration of Mimir-specific metadata
    val mimirOptions = scala.collection.mutable.Map[String, JsValue]()

    val stagingIsMandatory = (
         file.startsWith("http://")
      || file.startsWith("https://")
    )
    // Do some pre-processing / default configuration for specific formats
    //  to make the API a little friendlier.
    storageFormat match {

      // The Google Sheets loader expects to see only the last two path components of 
      // the sheet URL.  Rewrite full URLs if the user wants.
      case FileFormat.GOOGLE_SHEETS => {
        url = url.split("/").reverse.take(2).reverse.mkString("/")
      }
      
      // For everything else do nothing
      case _ => {}
    }

    if(stagingIsMandatory) {
      // Preserve the original URL and configurations in the mimirOptions
      mimirOptions("preStagedUrl") = JsString(url)
      mimirOptions("preStagedSparkOptions") = Json.toJson(finalSparkOptions)
      mimirOptions("preStagedFormat") = JsString(storageFormat)
      val stagedConfig  = MimirAPI.catalog.stage(url, finalSparkOptions, storageFormat, output)
      url               = stagedConfig._1
      finalSparkOptions = stagedConfig._2
      storageFormat     = stagedConfig._3
    }

    var loadConstructor = LoadConstructor(
      url = url,
      format = storageFormat,
      sparkOptions = finalSparkOptions
    )

    // Infer types if necessary
    if(inferTypes){
      loadConstructor = loadConstructor.withLens(
        MimirAPI.sparkSession, 
        "INFER_TYPES", 
        humanReadableName.getOrElse { file }
      )
    }

    // And finally save the dataframe constructor
    MimirAPI.catalog.put(output, loadConstructor, Set())

    return Json.toJson(LoadResponse(output))
  }
}

object LoadRequest {
  implicit val format: Format[LoadRequest] = Json.format
}

case class LoadResponse (
            /* name of resulting table */
                  name: String
) extends Response

object LoadResponse {
  implicit val format: Format[LoadResponse] = Json.format
}

