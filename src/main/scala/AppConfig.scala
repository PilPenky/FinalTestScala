import com.typesafe.config.Config

case class AppConfig(
                      httpHost: String,
                      httpPort: Int,
                      httpBasePath: String,
                      xmlPath: String,
                      jsonPath: String
                    )

object AppConfig {
  def load(config: Config): AppConfig = {
    AppConfig(
      httpHost = config.getString("akka.http.host"),
      httpPort = config.getInt("akka.http.port"),
      httpBasePath = config.getString("akka.http.base-path"),
      xmlPath = config.getString("akka.storage.xml-path"),
      jsonPath = config.getString("akka.storage.json-path")
    )
  }
}