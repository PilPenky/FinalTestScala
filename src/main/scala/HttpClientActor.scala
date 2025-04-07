// Сообщения для актора-клиента
object HttpClientActor {

  case object FetchData extends Commands

  case class DataReceived(json: io.circe.Json) extends Events

  case class FetchError(reason: String) extends Events

  case class DeleteData(ids: List[String]) extends Commands

  case class DataDeleted(id: String) extends Events

  case class DeleteError(id: String, reason: String) extends Events

  case class PostData(json: io.circe.Json) extends Commands

  case class DataPosted(response: String) extends Events

  case class PostError(reason: String) extends Events
}

import Color._
import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Актор-клиент для выполнения HTTP запросов
class HttpClientActor extends Actor with ActorLogging {

  import HttpClientActor._

  implicit val system: ActorSystem = context.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = context.dispatcher

  private val appConfig = AppConfig.load(context.system.settings.config)
  private val url = s"http://${appConfig.httpHost}:${appConfig.httpPort}"
  private val maxParallelDeletes = 1
  private val retryAttempts = 3
  private val delayBetweenRequests = 200.millis

  private def postDataToServer(json: Json): Future[String] = {
    val baseUrl = s"http://${appConfig.httpHost}:${appConfig.httpPort}${appConfig.httpBasePath}"
    val jsonString = json.noSpaces
    log.debug(s"Sending JSON to $url: $jsonString")

    val entity = HttpEntity(ContentTypes.`application/json`, jsonString)

    Http().singleRequest(HttpRequest(
      method = HttpMethods.POST,
      uri = baseUrl,
      entity = entity
    )).flatMap {
      case HttpResponse(StatusCodes.OK, _, entity, _) =>
        Unmarshal(entity).to[String].map { response =>
          log.debug(s"Server response: $response")
          response
        }
    }.recoverWith {
      case ex =>
        log.error(s"Request failed: ${ex.getMessage}")
        Future.failed(ex)
    }
  }

  override def receive: Receive = {
    case FetchData =>
      val originalSender = sender()
      log.info(colorStart + "Fetching data from server" + colorEnd)

      fetchDataFromServer().onComplete {
        case scala.util.Success(data) =>
          parse(data) match {
            case Right(json) =>
              self ! DataReceived(json)
              originalSender ! DataReceived(json)
            case Left(error) =>
              self ! FetchError(s"JSON parsing failed: ${error.getMessage}")
              originalSender ! FetchError(s"JSON parsing failed: ${error.getMessage}")
          }
        case scala.util.Failure(ex) =>
          self ! FetchError(ex.getMessage)
          originalSender ! FetchError(ex.getMessage)
      }

    case DeleteData(restIds) =>
      val originalSender = sender()
      log.info(colorStart + s"Starting deletion of ${restIds.size} restIds (max $maxParallelDeletes parallel)" + colorEnd)

      Source(restIds)
        .mapAsync(maxParallelDeletes) { restId =>
          akka.pattern.after(delayBetweenRequests, system.scheduler) {
            deleteDataFromServer(restId, retryAttempts)
              .map(_ => DataDeleted(restId))
              .recover { case ex => DeleteError(restId, ex.getMessage) }
          }
        }
        .runWith(Sink.foreach { result =>
          self ! result
          originalSender ! result
        })

    case DataReceived(json) =>
      log.info(colorStart + "Successfully received and parsed JSON data" + colorEnd)

    case FetchError(reason) =>
      log.error(s"Failed to fetch data: $reason")


    case PostData(json) =>
      val originalSender = sender()
      log.info(colorStart + "Posting data to server" + colorEnd)

      // Логируем входящий JSON
      log.debug(s"Incoming JSON to post: ${json.noSpaces}")

      postDataToServer(json).onComplete {
        case scala.util.Success(response) =>
          log.debug(s"Server response: $response")
          self ! DataPosted(response)
          originalSender ! DataPosted(response)
        case scala.util.Failure(ex) =>
          self ! PostError(ex.getMessage)
          originalSender ! PostError(ex.getMessage)
      }

    case DataPosted(response) =>
      log.info(colorStart + s"Successfully posted data to server. Response: $response" + colorEnd)

    case PostError(reason) =>
      log.error(s"Failed to post data: $reason")

  }

  private def fetchDataFromServer(): Future[String] = {
    val baseUrl = s"http://${appConfig.httpHost}:${appConfig.httpPort}${appConfig.httpBasePath}"
    Http().singleRequest(HttpRequest(uri = baseUrl))
      .flatMap {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          Unmarshal(entity).to[String]
        case resp =>
          Future.failed(new RuntimeException(s"Unexpected status code: ${resp.status}"))
      }
  }

  private def deleteDataFromServer(restId: String, attempts: Int = 3): Future[Unit] = {
    val baseUrl = s"http://${appConfig.httpHost}:${appConfig.httpPort}${appConfig.httpBasePath}"
    val urlExtension = s"$baseUrl/$restId"

    def attempt(n: Int): Future[Unit] = {
      if (n <= 0) {
        log.error(s"Failed to delete restId $restId after $attempts attempts")
        Future.failed(new RuntimeException(s"Final delete failed for: $restId"))
      } else {
        Http().singleRequest(HttpRequest(
          method = HttpMethods.DELETE,
          uri = urlExtension
        )).flatMap {
          case HttpResponse(StatusCodes.OK, _, _, _) =>
            log.info(s"Successfully deleted restId: $restId (attempt ${attempts - n + 1}/$attempts)")
            Future.successful(())
          case resp =>
            log.warning(s"Delete attempt failed for $restId (${resp.status}), retrying...")
            attempt(n - 1)
        }
      }
    }

    attempt(attempts)
  }
}