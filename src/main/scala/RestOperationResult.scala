import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

object RestOperationResult {
  implicit val validateCodec: Codec[Validate] = deriveCodec
  implicit val dataCodec: Codec[Data] = deriveCodec
  implicit val restCodec: Codec[Rest] = deriveCodec
  implicit val restOperationResultCodec: Codec[RestOperationResult] = deriveCodec
}

// Модели данных для работы с REST API
case class Validate(idValid: String, isValid: Option[Boolean])

case class Data(id: String, values: List[String], validate: Option[Validate])

case class Rest(restId: String, data: List[Data])

case class RestOperationResult(status: String, data: Option[List[Rest]], error: Option[String])