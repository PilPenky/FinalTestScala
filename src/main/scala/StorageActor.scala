import Color._
import akka.actor.{Actor, ActorLogging}
import java.nio.file.{Files, Paths}
import scala.xml.{Elem, XML}

// Сообщения для актора-хранилища
object StorageActor {
  case class SaveXml(xml: Elem, path: String) extends Commands

  case class ConvertXmlToJson(xml: String, json: String) extends Commands

  case class XmlSaved(path: String) extends Events

  case class ConversionSuccessful(path: String) extends Events

  case class SaveError(reason: String) extends Events

  case class ReadXml(path: String) extends Commands

  case class XmlRead(xml: Elem) extends Events

  private case class ReadError(reason: String) extends Events
}

// Актор-хранилище для работы с файлами
class StorageActor extends Actor with ActorLogging {

  import StorageActor._
  import XmlUtils._

  override def receive: Receive = {
    case ConvertXmlToJson(xmlPath, jsonPath) =>
      try {
        val xml = XML.loadFile(xmlPath)
        val json = xmlToJson(xml)
        val jsonString = json.spaces2
        val formattedJson = XmlUtils.formatJsonStrictly(jsonString) // <- Здесь замена!
        Files.write(Paths.get(jsonPath), formattedJson.getBytes("UTF-8"))
        sender() ! ConversionSuccessful(jsonPath)
      } catch {
        case ex: Exception =>
          log.error(s"Ошибка конвертации: ${ex.getMessage}")
          sender() ! SaveError(ex.getMessage)
      }

    case SaveXml(xml, path) =>
      val originalSender = sender()
      try {
        saveXmlToFile(xml, path)
        self ! XmlSaved(path)
        originalSender ! XmlSaved(path)
      } catch {
        case ex: Exception =>
          self ! SaveError(ex.getMessage)
          originalSender ! SaveError(ex.getMessage)
      }

    case XmlSaved(path) =>
      log.info(colorStart + s"XML successfully saved to $path" + colorEnd)

    case ConversionSuccessful(json) =>
      log.info(colorStart + s"XML successfully converted to $json" + colorEnd)

    case SaveError(reason) =>
      log.error(s"Failed to save XML: $reason")

    case ReadXml(path) =>
      val originalSender = sender()
      try {
        val xml = scala.xml.XML.loadFile(path)
        self ! XmlRead(xml)
        originalSender ! XmlRead(xml)
      } catch {
        case ex: Exception =>
          self ! ReadError(ex.getMessage)
          originalSender ! ReadError(ex.getMessage)
      }

    case ReadError(reason) =>
      log.error(s"Failed to read XML: $reason")
  }
}