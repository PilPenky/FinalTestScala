import akka.actor.{Actor, ActorLogging}

// Главный актор, который координирует работу
class MainActor extends Actor with ActorLogging {

  import Color._
  import HttpClientActor._
  import RestOperationResult.restCodec
  import StorageActor._
  import XmlUtils._
  import akka.actor.Props
  import akka.util.Timeout
  import io.circe.Json
  import io.circe.generic.auto.exportEncoder
  import io.circe.parser.parse
  import io.circe.syntax.EncoderOps

  import java.nio.file.{Files, Paths}
  import scala.concurrent.ExecutionContextExecutor
  import scala.concurrent.duration._
  import scala.io.StdIn
  import scala.xml.Elem

  private val httpClient = context.actorOf(Props[HttpClientActor], "httpClient")
  private val storageActor = context.actorOf(Props[StorageActor], "storageActor")
  private val appConfig = AppConfig.load(context.system.settings.config)
  private val outputPathXML = appConfig.xmlPath
  private val outputPathJSON = appConfig.jsonPath

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val executionContext: ExecutionContextExecutor = context.dispatcher


  var deletedCount = 0
  var totalIds = 0

  var successfulPosts = 0
  var totalPosts = 0 // Будет установлено при отправке данных
  var serverResponses = Vector.empty[Json] // Храним распарсенные JSON

  override def receive: Receive = {
    case "start" =>
      log.info(colorStart + "Starting data processing..." + colorEnd)
      httpClient ! FetchData // <-- ЗДЕСЬ ПРОИСХОДИТ ЗАПРОС НА СЕРВЕР И ПОЛУЧЕНИЕ СПИСОКА ДАННЫХ
      Thread.sleep(1500)
      println(colorBeforeMsgStart + "Запрос на сервер осуществлен и получен весь список данных" + colorEnd)
      StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)

    case DataReceived(json: Json) =>
      log.info(colorStart + "JSON received, converting to XML..." + colorEnd)
      val xmlData = jsonToXml(json) // <-- ЗДЕСЬ ПРОИСХОДИТ ПРЕОБРАЗОВАНИЕ ПОЛУЧЕННЫХ ДАННЫХ ИЗ СЕРВЕРА В XML-ФОРМАТ
      Thread.sleep(1000)
      println(colorBeforeMsgStart + "Полученные данные из сервера преобразованы в XML-формат" + colorEnd)
      StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)
      storageActor ! SaveXml(xmlData, outputPathXML) // <-- ЗДЕСЬ ПРОИСХОДИТ СОХРАНЕНИЕ XML-ФАЙЛА НА ЖЕСТКИЙ ДИСК
      Thread.sleep(1000)
      println(colorBeforeMsgStart + "Данные сервера в XML-формате сохранены на жестком диске" + colorEnd)
      StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)

    case XmlSaved(_) =>
      log.info(colorStart + "XML saved, extracting IDs for deletion..." + colorEnd)
      // Здесь нужно прочитать файл и извлечь IDs
      storageActor ! ReadXml(outputPathXML)


    // Модифицируем case DataDeleted
    case DataDeleted(id) =>
      deletedCount += 1
      log.info(colorStart + s"Successfully deleted data with ID: $id ($deletedCount/$totalIds)" + colorEnd)
      if (deletedCount == totalIds) {
        log.info(colorStart + "All data deleted from server, converting to JSON..." + colorEnd)
        storageActor ! ConvertXmlToJson(outputPathXML, outputPathJSON)
      }

    case XmlRead(xml) =>
      log.info(colorStart + "Extracting restIds from XML..." + colorEnd)

      // Извлекаем restIds (новый метод)
      val restIds = extractRestIdsFromXml(xml)

      log.info(colorStart + s"Found ${restIds.size} restIds to delete: ${restIds.take(5).mkString(", ")}${if (restIds.size > 5) ", ..." else ""}" + colorEnd)

      if (restIds.nonEmpty) {
        context.become(waitingForDeletion(restIds, restIds.size))
        httpClient ! DeleteData(restIds) // <-- ЗДЕСЬ ПРОИСХОДИТ УДАЛЕНИЕ ВСЕХ ДАННЫХ С СЕРВЕРА
        Thread.sleep(4000)
        println(colorBeforeMsgStart + "Все данные с сервера удалены" + colorEnd)
        StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)
      } else {
        log.info(colorStart + "No restIds found, skipping deletion" + colorEnd)
        storageActor ! ConvertXmlToJson(outputPathXML, outputPathJSON)
      }

      def waitingForDeletion(remainingIds: List[String], totalIds: Int): Receive = {
        case DataDeleted(id) =>
          val newRemaining = remainingIds.filterNot(_ == id)
          val deletedCount = totalIds - newRemaining.size
          log.info(colorStart + s"Deleted ID: $id ($deletedCount/$totalIds)" + colorEnd)

          if (newRemaining.isEmpty) {
            log.info(colorStart + "All data deleted from server, converting to JSON..." + colorEnd)
            storageActor ! ConvertXmlToJson(outputPathXML, outputPathJSON) // <-- ЗДЕСЬ ПРОИСХОДИТ СЧИТЫВАНИЕ РАННЕЕ СОХРАНЕННОГО XML-ФАЙЛА И ПРЕОБРАЗОВЫВАНИЕ ЕГО В JSON
            context.unbecome()
            Thread.sleep(1000)
            println(colorBeforeMsgStart + "Сохраненный раннее файл XML считан, преобразован в JSON-файл и сохранен на жестком диске " + colorEnd)
            StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)
          } else {
            context.become(waitingForDeletion(newRemaining, totalIds))
          }

        case DeleteError(id, reason) =>
          log.error(s"Failed to delete ID $id: $reason. Continuing with remaining IDs...")
          val newRemaining = remainingIds.filterNot(_ == id)
          if (newRemaining.isEmpty) {
            log.info(colorStart + "All possible deletions completed, converting to JSON..." + colorEnd)
            storageActor ! ConvertXmlToJson(outputPathXML, outputPathJSON)
            context.unbecome()
          } else {
            context.become(waitingForDeletion(newRemaining, totalIds))
          }
      }

    case DeleteError(id, reason) =>
      log.error(s"Failed to delete $id: $reason")

    case ConversionSuccessful(jsonPath) =>
      log.info(colorStart + s"JSON successfully saved to $jsonPath" + colorEnd)

      val jsonString = new String(Files.readAllBytes(Paths.get(jsonPath)), "UTF-8")
      parse(jsonString) match {
        case Right(json) =>
          json.hcursor.downField("data").as[List[Rest]] match {
            case Right(rests) =>
              totalPosts = rests.size // Устанавливаем общее количество записей
              rests.zipWithIndex.foreach { case (rest, index) =>
                context.system.scheduler.scheduleOnce((index * 100).millis) {
                  val restJson = Json.obj(
                    "restId" -> Json.fromString(rest.restId),
                    "data" -> rest.data.asJson
                  )
                  httpClient ! PostData(restJson) // <-- ЗДЕСЬ ПРОИСХОДИТ СОХРАНЕНИЕ ДАННЫХ JSON-ФАЙЛА НА СЕРВЕР (Для каждого элемента Rest из JSON формируется отдельный POST-запрос)
                }
              }
              Thread.sleep(1000)
              println(colorBeforeMsgStart + "Данные JSON-файла сохранены на сервер" + colorEnd)
              StdIn.readLine(colorMsgStart + "Нажмите \"Enter\" для продолжения" + colorEnd)

            case Left(error) =>
              log.error(s"Failed to parse data array: ${error.getMessage}")
              context.system.terminate()
          }

        case Left(error) =>
          log.error(s"Failed to parse JSON from file: ${error.getMessage}")
          context.system.terminate()
      }


    // Обновлённый обработчик DataPosted:
    case DataPosted(response) =>
      parse(response) match {
        case Right(json) =>
          successfulPosts += 1
          serverResponses = serverResponses :+ json
          log.info(colorStart + s"Успешно отправлено $successfulPosts/$totalPosts" + colorEnd)

          if (successfulPosts >= totalPosts) {
            log.info(colorStart + "Все данные отправлены. Начинаем сравнение..." + colorEnd)
            compareData() //////// <-- ЗДЕСЬ ЗАПУСКАЕТСЯ СРАВНЕНИЕ ДАННЫХ С СЕРВЕРА И ФАЙЛА XML
            Thread.sleep(1000)
            println(colorBeforeMsgStart + "Сохраненный раннее файл XML считан, данные с сервера (JSON) получены. Данные преобразованы в объектное представление Scala" + colorEnd)
            println(colorBeforeMsgStart + "Данные сравнены" + colorEnd)
          }

        case Left(error) =>
          log.error(s"Ошибка парсинга ответа сервера: ${error.getMessage}")
      }

    case PostError(reason) =>
      log.error(colorStart + s"Ошибка при отправке: $reason" + colorEnd)
      context.system.terminate()


    case FetchError(reason)
    =>
      log.error(s"Failed to fetch data: $reason")
      context.system.terminate()

    case SaveError(reason)
    =>
      log.error(s"Failed to save XML: $reason")
      context.system.terminate()
  }

  private def compareData(): Unit = {
    val xmlPath = "dataFromServerXML.xml"
    val combinedJson = Json.arr(serverResponses: _*) // Создаём валидный JSON-массив

    val isEqual = DataComparator.compareXmlWithServerJson(xmlPath, combinedJson.noSpaces)
    log.info(s"${Color.colorResultStart}Результат сравнения: ${if (isEqual) "ДАННЫЕ ИДЕНТИЧНЫ!" else "ДАННЫЕ РАЗЛИЧАЮТСЯ!"}${Color.colorEnd}")

    context.system.terminate()
  }

  private def extractRestIdsFromXml(xml: Elem): List[String] = {
    (xml \\ "field")
      .filter(f => (f \ "@name").text == "restId")
      .flatMap(_.child.collect {
        case e if e.label == "string" => e.text.trim
      })
      .toList
      .distinct
  }
}