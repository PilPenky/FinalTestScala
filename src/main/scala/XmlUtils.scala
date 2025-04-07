import Main.system.log
import io.circe.Json
import io.circe.parser.parse

import scala.xml.Elem

// Методы для работы с XML / JSON
object XmlUtils {
  def jsonToXml(json: Json): Elem = {
    json.fold(
      jsonNull = <null/>,
      jsonBoolean = b => <boolean>
        {b.toString}
      </boolean>,
      jsonNumber = n => <number>
        {n.toString}
      </number>,
      jsonString = s => <string>
        {s}
      </string>,
      jsonArray = arr => {
        <array>
          {arr.map(item => jsonToXml(item))}
        </array>
      },
      jsonObject = obj => {
        <object>
          {obj.toList.map { case (key, value) =>
          <field name={key}>
            {jsonToXml(value)}
          </field>
        }}
        </object>
      }
    )
  }

  import io.circe.{Json, JsonObject}
  import scala.xml.{Elem, Node}

  def xmlToJson(xml: Elem): Json = {
    def parseNode(node: Node): Json = {
      node match {
        case root if root.label == "object" && (root \ "field").exists(f => (f \ "@name").text == "data") =>
          // Сначала собираем весь data-объект
          val dataObj = (root \ "field").collectFirst {
            case field if (field \ "@name").text == "data" =>
              Json.obj(
                "data" -> Json.fromValues(
                  (field \ "array" \ "object").map(parseDataItem)
                )
              )
          }.getOrElse(Json.obj())

          // Затем создаем новый объект с правильным порядком полей
          Json.fromJsonObject(
            JsonObject.fromIterable(
              dataObj.asObject.get.toList :+ ("status" -> Json.fromString("success"))
            ))

        case _ => Json.Null
      }
    }

    def parseDataItem(obj: Node): Json = {
      val fields = (obj \ "field").flatMap {
        case f if (f \ "@name").text == "data" =>
          val items = (f \ "array" \ "object").map(parseDataItem)
          Some("data" -> Json.fromValues(items))

        case f if (f \ "@name").text == "restId" =>
          Some("restId" -> Json.fromString(f.child.text.trim))

        case f if (f \ "@name").text == "id" =>
          Some("id" -> Json.fromString(f.child.text.trim))

        case f if (f \ "@name").text == "validate" =>
          Some("validate" -> parseValidate(f))

        case f if (f \ "@name").text == "values" =>
          Some("values" -> parseValues(f))

        case _ => None
      }
      Json.fromJsonObject(JsonObject.fromIterable(fields))
    }

    def parseValidate(field: Node): Json = {
      val fields = (field \ "object" \ "field").collect {
        case f if (f \ "@name").text == "idValid" =>
          "idValid" -> Json.fromString(f.child.text.trim)
        case f if (f \ "@name").text == "isValid" =>
          "isValid" -> Json.fromBoolean(f.child.text.trim.toBoolean)
      }
      Json.fromJsonObject(JsonObject.fromIterable(fields))
    }

    def parseValues(field: Node): Json = {
      Json.fromValues(
        (field \ "array" \ "string").map(s => Json.fromString(s.text.trim))
      )
    }

    parseNode(xml)
  }

  private def handleObject(obj: xml.Elem): Json = {
    val fields = obj.child.collect {
      case field: xml.Elem if field.label == "field" =>
        val name = (field \ "@name").text
        name -> handleFieldContent(field)
    }
    Json.fromJsonObject(JsonObject.fromIterable(fields))
  }

  private def handleFieldContent(field: xml.Elem): Json = {
    field.child.headOption match {
      case Some(content: xml.Elem) => content.label match {
        case "object" => handleObject(content)
        case "array" => handleArray(content)
        case "string" => Json.fromString(content.text.trim)
        case "boolean" => Json.fromBoolean(content.text.trim.toBoolean)
        case _ => Json.Null
      }
      case _ => Json.Null
    }
  }

  private def handleArray(arr: xml.Elem): Json = {
    val items = arr.child.collect {
      case item: xml.Elem => item.label match {
        case "object" => handleObject(item)
        case "string" => Json.fromString(item.text.trim)
        case "boolean" => Json.fromBoolean(item.text.trim.toBoolean)
        case _ => Json.Null
      }
    }
    Json.fromValues(items)
  }

  def saveXmlToFile(xml: Elem, path: String): Unit = {
    val writer = new java.io.PrintWriter(new java.io.File(path))
    try {
      writer.write(new scala.xml.PrettyPrinter(80, 2).format(xml))
    } finally {
      writer.close()
    }
  }

  import io.circe.Printer

  // Новый метод с исправленным форматированием
  def formatJsonStrictly(jsonString: String): String = {
    parse(jsonString) match {
      case Right(json) =>
        val printer = Printer(
          indent = "    ",
          lbraceRight = "\n",
          rbraceLeft = "\n",
          lbracketRight = "\n",
          rbracketLeft = "\n",
          lrbracketsEmpty = "",
          arrayCommaRight = "\n",
          objectCommaRight = "\n",
          colonLeft = "",
          colonRight = " ",
          dropNullValues = false
        )
        printer.print(json)
          .replaceAll("\"values\": \\[\\s*\\]", "\"values\": []")

      case Left(error) =>
        log.error(s"Ошибка парсинга JSON: ${error.getMessage}")
        jsonString
    }
  }
}