object DataComparator {

  import RestOperationResult.restCodec
  import io.circe.parser.parse
  import scala.xml.XML

  // Модель для сравнения (упрощённая версия RestOperationResult)
  case class ComparableData(restId: String, id: String, values: List[String], isValid: Option[Boolean])

  /**
   * Сравнивает данные из XML-файла с данными, полученными от сервера (JSON).
   * @param xmlPath    путь к XML-файлу
   * @param serverJson JSON-строка с сервера
   * @return Boolean (true, если данные идентичны)
   */
  def compareXmlWithServerJson(xmlPath: String, serverJson: String): Boolean = {
    val xmlData = parseXml(xmlPath)
    val jsonData = parseJson(serverJson)

    xmlData == jsonData
  }

  private def parseXml(xmlPath: String): List[ComparableData] = {
    val xml = XML.loadFile(xmlPath)
    (xml \\ "object").flatMap { obj =>
      for {
        restId <- (obj \ "field" \@ "name").find(_ == "restId").map(_ => (obj \ "field" \ "string").text.trim)
        id <- (obj \ "field" \@ "name").find(_ == "id").map(_ => (obj \ "field" \ "string").text.trim)
        values = (obj \ "field" \ "array" \ "string").map(_.text.trim).toList
        isValid = (obj \ "field" \ "boolean").headOption.map(_.text.trim.toBoolean)
      } yield ComparableData(restId, id, values, isValid)
    }.toList
  }

  private def parseJson(jsonStr: String): List[ComparableData] = {
    parse(jsonStr).flatMap { json =>
      json.hcursor.downField("data").as[List[Rest]].map { rests =>
        rests.flatMap { rest =>
          rest.data.map { data =>
            ComparableData(
              restId = rest.restId,
              id = data.id,
              values = data.values,
              isValid = data.validate.flatMap(_.isValid)
            )
          }
        }
      }
    }.getOrElse(Nil)
  }
}